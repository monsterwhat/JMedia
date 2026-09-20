#!/usr/bin/env python3
"""
Parakeet TDT v3 transcription wrapper for JMedia.
Transcribes audio/video files using NVIDIA Parakeet TDT 0.6B v3.
When --language is given and differs from the source language, the resulting
subtitles are translated with facebook/nllb-200-distilled-600M and written as
{basename}.{language}.srt.
Reports progress to stderr in format: PROGRESS:<percent>
Reports stage to stderr in format: STAGE:<stage>
Reports output path to stderr in format: SRT:<path>
"""

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")

# --- Language / translation helpers ---------------------------------------

# ISO 639-2 (3-letter) -> ISO 639-1 (2-letter) for common languages.
THREE_TO_TWO = {
    "eng": "en", "spa": "es", "fre": "fr", "fra": "fr", "deu": "de", "ger": "de",
    "ita": "it", "por": "pt", "rus": "ru", "jpn": "ja", "kor": "ko", "chi": "zh",
    "zho": "zh", "nld": "nl", "dut": "nl", "pol": "pl", "swe": "sv", "dan": "da",
    "fin": "fi", "nor": "no", "nob": "no", "ces": "cs", "cze": "cs", "ell": "el",
    "gre": "el", "hun": "hu", "ron": "ro", "rum": "ro", "slk": "sk", "slo": "sk",
    "slv": "sl", "ukr": "uk", "hrv": "hr", "bul": "bg", "est": "et", "lav": "lv",
    "lit": "lt", "mlt": "mt", "ara": "ar", "hin": "hi", "tur": "tr", "tha": "th",
    "vie": "vi", "ind": "id", "msa": "ms", "may": "ms", "heb": "he",
}

# Inverse: 2-letter -> 3-letter (used to build FLORES-200 candidate codes).
TWO_TO_THREE = {v: k for k, v in THREE_TO_TWO.items()}

# ISO 639-1 (2-letter) -> FLORES-200 code for NLLB.
FLORES_MAP = {
    "en": "eng_Latn", "es": "spa_Latn", "fr": "fra_Latn", "de": "deu_Latn",
    "it": "ita_Latn", "pt": "por_Latn", "ru": "rus_Cyrl", "ja": "jpn_Jpan",
    "ko": "kor_Hang", "zh": "zho_Hans", "nl": "nld_Latn", "pl": "pol_Latn",
    "sv": "swe_Latn", "da": "dan_Latn", "fi": "fin_Latn", "no": "nob_Latn",
    "cs": "ces_Latn", "el": "ell_Grek", "hu": "hun_Latn", "ro": "ron_Latn",
    "sk": "slk_Latn", "sl": "slv_Latn", "uk": "ukr_Cyrl", "hr": "hrv_Latn",
    "bg": "bul_Cyrl", "et": "est_Latn", "lv": "lvs_Latn", "lt": "lit_Latn",
    "mt": "mlt_Latn", "ar": "arb_Arab", "hi": "hin_Deva", "tr": "tur_Latn",
    "th": "tha_Thai", "vi": "vie_Latn", "id": "ind_Latn", "ms": "zsm_Latn",
    "he": "heb_Hebr",
}


def normalize_lang(code):
    """Normalize a language code to ISO 639-1 (2-letter) when possible."""
    if not code:
        return None
    code = str(code).strip().lower()
    # "spl" is an OpenSubtitles sublanguage id for Spanish (Latin America); it is
    # not a valid Parakeet/NLLB code, so map it to ISO 639-1 "es" (spa_Latn).
    if code == "spl":
        return "es"
    if len(code) == 2:
        return code
    if len(code) == 3:
        return THREE_TO_TWO.get(code, code)
    return code


def detect_source_language(text_sample):
    """Detect the source language from a text sample using langdetect (optional dependency)."""
    try:
        from langdetect import detect
        return detect(text_sample)
    except Exception:
        return None


def _build_detection_sample(srt_lines, max_chars=500):
    """Build a text sample (first ~max_chars chars of transcribed text) for language detection."""
    parts = []
    for line in srt_lines:
        s = line.strip()
        if not s or s.isdigit() or "-->" in s:
            continue
        parts.append(s)
    return " ".join(parts)[:max_chars]


def _lang_code_to_id(tokenizer, flores_code):
    """Get the token id for a FLORES-200 language code across transformers versions."""
    lang_to_id = getattr(tokenizer, "lang_code_to_id", None)
    if lang_to_id is not None and flores_code in lang_to_id:
        return lang_to_id[flores_code]
    vocab = tokenizer.get_vocab()
    if flores_code in vocab:
        return vocab[flores_code]
    return None


def resolve_flores_code(code, tokenizer):
    """Resolve a language code to a FLORES-200 code supported by the NLLB tokenizer."""
    if code in FLORES_MAP:
        return FLORES_MAP[code]
    lang_to_id = getattr(tokenizer, "lang_code_to_id", None) or {}
    vocab = tokenizer.get_vocab()

    def _valid(cand):
        return cand in lang_to_id or cand in vocab

    candidates = []
    if len(code) == 2:
        candidates.append(f"{code}_Latn")
        candidates.append(f"{code}_Cyrl")
        candidates.append(f"{code}_Arab")
        candidates.append(f"{code}_Hant")
        three = TWO_TO_THREE.get(code)
        if three:
            candidates.append(f"{three}_Latn")
            candidates.append(f"{three}_Cyrl")
            candidates.append(f"{three}_Arab")
            candidates.append(f"{three}_Hant")
    else:
        candidates.append(f"{code}_Latn")
        candidates.append(f"{code}_Cyrl")
        candidates.append(f"{code}_Arab")
        candidates.append(f"{code}_Hant")
    for cand in candidates:
        if _valid(cand):
            return cand
    return None


def load_nllb(device, dtype):
    """Load the NLLB-200 distilled 600M translation model. Exits on failure."""
    try:
        from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
    except ImportError as e:
        print(f"ERROR:Missing translation dependency: {e}", file=sys.stderr)
        print("Run: pip install transformers sentencepiece sacremoses langdetect", file=sys.stderr)
        sys.exit(1)
    print(f"PARAKEET:Loading NLLB translation model facebook/nllb-200-distilled-600M...", file=sys.stderr)
    try:
        tokenizer = AutoTokenizer.from_pretrained("facebook/nllb-200-distilled-600M")
        try:
            model = AutoModelForSeq2SeqLM.from_pretrained(
                "facebook/nllb-200-distilled-600M",
                dtype=dtype,
                low_cpu_mem_usage=True,
            )
        except TypeError as te:
            print(f"WARN:Falling back to torch_dtype param for NLLB load: {te}", file=sys.stderr)
            model = AutoModelForSeq2SeqLM.from_pretrained(
                "facebook/nllb-200-distilled-600M",
                torch_dtype=dtype,
                low_cpu_mem_usage=True,
            )
        model = model.to(device)
        model.eval()
        return tokenizer, model
    except Exception as e:
        print(f"ERROR:Failed to load NLLB translation model: {e}", file=sys.stderr)
        sys.exit(1)


def parse_srt_blocks(srt_lines):
    """Parse flat SRT lines into blocks of (index_line, timecode_line, [text_lines])."""
    blocks = []
    i = 0
    n = len(srt_lines)
    while i < n:
        line = srt_lines[i]
        if line.strip() == "":
            i += 1
            continue
        index_line = line
        i += 1
        if i >= n:
            break
        timecode_line = srt_lines[i]
        i += 1
        text_lines = []
        while i < n and srt_lines[i].strip() != "":
            text_lines.append(srt_lines[i])
            i += 1
        blocks.append((index_line, timecode_line, text_lines))
        if i < n and srt_lines[i].strip() == "":
            i += 1
    return blocks


def translate_srt(srt_lines, source_lang, target_lang, device, dtype):
    """Translate the text lines of an SRT document, preserving structure byte-for-byte."""
    import torch  # already imported by main(); re-bind for module-level use

    tokenizer, model = load_nllb(device, dtype)

    src_flores = resolve_flores_code(source_lang, tokenizer)
    tgt_flores = resolve_flores_code(target_lang, tokenizer)
    if src_flores is None:
        print(f"WARN:No FLORES-200 code for source language '{source_lang}'; skipping translation", file=sys.stderr)
        return srt_lines
    if tgt_flores is None:
        print(f"WARN:No FLORES-200 code for target language '{target_lang}'; skipping translation", file=sys.stderr)
        return srt_lines

    tokenizer.src_lang = src_flores
    tokenizer.tgt_lang = tgt_flores
    tokenizer.set_src_lang_special_tokens(src_flores)
    tgt_token_id = _lang_code_to_id(tokenizer, tgt_flores)

    has_webvtt = False
    first_non_empty = 0
    while first_non_empty < len(srt_lines) and srt_lines[first_non_empty].strip() == "":
        first_non_empty += 1
    if first_non_empty < len(srt_lines) and srt_lines[first_non_empty].strip().startswith("WEBVTT"):
        has_webvtt = True

    filtered_lines = []
    if has_webvtt:
        i = first_non_empty + 1
        while i < len(srt_lines):
            line = srt_lines[i]
            if line.strip() == "":
                i += 1
                break
            if "-->" in line and re.match(r'^\d{2}:\d{2}(?::\d{2})?[\.,]\d{3}\s*-->', line.strip()):
                break
            i += 1
        while i < len(srt_lines):
            line = srt_lines[i]
            stripped = line.strip()
            if stripped.startswith("NOTE") or stripped == "STYLE" or stripped.startswith("STYLE ") or stripped == "REGION" or stripped.startswith("REGION "):
                i += 1
                while i < len(srt_lines) and srt_lines[i].strip() != "":
                    i += 1
                if i < len(srt_lines) and srt_lines[i].strip() == "":
                    i += 1
                continue
            filtered_lines.append(line)
            i += 1
        parse_source = filtered_lines
    else:
        has_metadata_block = False
        for _l in srt_lines:
            _st = _l.strip()
            if _st.startswith("NOTE") or _st == "STYLE" or _st.startswith("STYLE ") or _st == "REGION" or _st.startswith("REGION "):
                has_metadata_block = True
                break
        if has_metadata_block:
            i = 0
            while i < len(srt_lines):
                line = srt_lines[i]
                stripped = line.strip()
                if stripped.startswith("NOTE") or stripped == "STYLE" or stripped.startswith("STYLE ") or stripped == "REGION" or stripped.startswith("REGION "):
                    i += 1
                    while i < len(srt_lines) and srt_lines[i].strip() != "":
                        i += 1
                    if i < len(srt_lines) and srt_lines[i].strip() == "":
                        i += 1
                    continue
                filtered_lines.append(line)
                i += 1
            parse_source = filtered_lines
        else:
            parse_source = srt_lines

    timecode_dot_re = re.compile(r'^(?:\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->\s*(?:\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})')
    timecode_any_re = re.compile(r'^(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})\s*-->\s*(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})')
    use_vtt_parser = has_webvtt or any(timecode_dot_re.match(l.strip()) for l in parse_source)
    blocks = []
    if use_vtt_parser:
        n = len(parse_source)
        idx = 0
        block_counter = 1
        while idx < n:
            if parse_source[idx].strip() == "":
                idx += 1
                continue
            line = parse_source[idx]
            if line.strip().isdigit() and idx + 1 < n and "-->" in parse_source[idx + 1] and timecode_any_re.match(parse_source[idx + 1].strip()):
                index_line = line.strip()
                timecode_line = parse_source[idx + 1]
                idx += 2
            elif "-->" in line and timecode_any_re.match(line.strip()):
                index_line = str(block_counter)
                timecode_line = line
                idx += 1
            else:
                idx += 1
                continue
            text_lines = []
            while idx < n and parse_source[idx].strip() != "":
                text_lines.append(parse_source[idx])
                idx += 1
            if idx < n and parse_source[idx].strip() == "":
                idx += 1
            blocks.append((index_line, timecode_line, text_lines))
            block_counter += 1
        if len(blocks) == 0:
            try:
                blocks = parse_srt_blocks(parse_source)
            except Exception as e:
                print(f"WARN:VTT-aware parsing yielded 0 blocks, fallback failed: {e}", file=sys.stderr)
                blocks = parse_srt_blocks(parse_source)
    else:
        blocks = parse_srt_blocks(parse_source)
    new_texts = [None] * len(blocks)

    batch = []
    batch_tokens = 0
    MAX_BATCH_TOKENS = 200
    total_blocks = len(blocks)
    done_blocks = 0

    def flush():
        nonlocal batch, batch_tokens, done_blocks
        if not batch:
            return
        flushed = len(batch)
        try:
            texts = [t for _, t in batch]
            inputs = tokenizer(texts, return_tensors="pt", padding=True, truncation=True, max_length=256)
            inputs = {k: v.to(device) for k, v in inputs.items() if hasattr(v, "to")}
            gen_kwargs = dict(num_beams=5, max_new_tokens=256, no_repeat_ngram_size=3, do_sample=False)
            if tgt_token_id is not None:
                gen_kwargs["forced_bos_token_id"] = tgt_token_id
            else:
                print(f"WARN:No token id for target language '{tgt_flores}'; translating without forced BOS", file=sys.stderr)
            with torch.no_grad():
                outputs = model.generate(**inputs, **gen_kwargs)
            decoded = tokenizer.batch_decode(outputs, skip_special_tokens=True)
            for (block_idx, _), new_text in zip(batch, decoded):
                new_texts[block_idx] = new_text
        except Exception as e:
            print(f"WARN:Translation batch failed ({len(batch)} blocks): {e}", file=sys.stderr)
            for block_idx, _ in batch:
                new_texts[block_idx] = None  # keep original text for this block
        batch = []
        batch_tokens = 0
        done_blocks += flushed
        if total_blocks > 0:
            pct = done_blocks / total_blocks * 100
            if pct > 100:
                pct = 100
            print(f"PROGRESS:{pct:.1f}", file=sys.stderr, flush=True)

    for idx, (index_line, timecode_line, text_lines) in enumerate(blocks):
        text = "\n".join(text_lines)
        if not text.strip():
            new_texts[idx] = None  # nothing to translate; keep original
            done_blocks += 1
            if total_blocks > 0:
                pct = done_blocks / total_blocks * 100
                if pct > 100:
                    pct = 100
                print(f"PROGRESS:{pct:.1f}", file=sys.stderr, flush=True)
            continue
        batch_tokens += len(text.split())
        batch.append((idx, text))
        if batch_tokens >= MAX_BATCH_TOKENS:
            flush()
    flush()
    if total_blocks > 0:
        if done_blocks < total_blocks:
            print(f"PROGRESS:100.0", file=sys.stderr, flush=True)
        elif done_blocks == total_blocks:
            last_pct = done_blocks / total_blocks * 100
            if last_pct < 100:
                print(f"PROGRESS:100.0", file=sys.stderr, flush=True)

    # Rebuild the SRT lines, preserving index/timecode lines byte-for-byte, converting VTT dot timecodes to SRT commas.
    out = []
    dot_timecode_full_re = re.compile(r'^(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})\s*-->\s*(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})')
    for (index_line, timecode_line, text_lines), new_text in zip(blocks, new_texts):
        if dot_timecode_full_re.match(timecode_line.strip()):
            try:
                def _norm_tok(m):
                    tok = m.group(0)
                    if tok.count(':') == 2:
                        return tok.replace('.', ',')
                    else:
                        parts = re.split(r'[\.,]', tok)
                        return f"00:{parts[0]},{parts[1]}"
                timecode_line = re.sub(r'\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3}', _norm_tok, timecode_line)
            except Exception as e:
                print(f"WARN:Failed to convert VTT timecode dots to commas for '{timecode_line}': {e}", file=sys.stderr)
        out.append(index_line)
        out.append(timecode_line)
        if new_text is None:
            out.extend(text_lines)
        else:
            new_lines = new_text.split("\n")
            # NLLB often reflows newlines (e.g. 2-line block -> 1-line translation);
            # the old guard discarded the translation entirely. Warn but keep translated text.
            if len(new_lines) != len(text_lines):
                print(f"WARN:Translation line-count mismatch for block {index_line} ({len(text_lines)} -> {len(new_lines)}); using translated text as-is", file=sys.stderr)
            # Filter out empty lines that would create blank cues, but keep at least one line
            filtered = [l for l in new_lines if l.strip() != ""]
            if filtered:
                new_lines = filtered
            elif not new_text.strip():
                print(f"WARN:Empty translation for block {index_line}; keeping original", file=sys.stderr)
                out.extend(text_lines)
                out.append("")
                continue
            out.extend(new_lines)
        out.append("")
    return out


def run_translate_only(args):
    """Translate an existing subtitle file with NLLB (no Parakeet transcription)."""
    input_path = Path(args.translate)
    allowed_subtitle_extensions = {".srt", ".vtt", ".ass", ".ssa", ".sub", ".idx"}
    video_container_extensions = {".mp4", ".mkv", ".avi", ".webm", ".ts", ".mov", ".m4v", ".flv", ".wmv", ".mpg", ".mpeg"}
    ext = input_path.suffix.lower()
    if ext in video_container_extensions:
        print(f"ERROR: Refusing to translate video container '{input_path}' (extension '{ext}' is a video format, not a subtitle format); embedded tracks must be extracted via ffmpeg first", file=sys.stderr)
        sys.exit(1)
    if ext not in allowed_subtitle_extensions:
        print(f"ERROR: Unsupported subtitle extension '{ext}' for '{input_path}' (expected .srt/.vtt/.ass/.ssa/.sub/.idx); refusing to translate non-subtitle input", file=sys.stderr)
        sys.exit(1)
    if not input_path.exists():
        print(f"ERROR: Subtitle file not found: {args.translate}", file=sys.stderr)
        sys.exit(1)

    if not args.language:
        print(f"ERROR: --language is required with --translate", file=sys.stderr)
        sys.exit(1)

    target_lang = normalize_lang(args.language)
    if target_lang is None:
        print(f"ERROR: Invalid target language code: {args.language}", file=sys.stderr)
        sys.exit(1)

    try:
        import torch
    except ImportError as e:
        print(f"ERROR: Missing dependency: {e}", file=sys.stderr)
        print("Run: pip install transformers torch", file=sys.stderr)
        sys.exit(1)

    if torch.cuda.is_available():
        device = "cuda"
        dtype = torch.float16
        print(f"DEVICE:cuda", file=sys.stderr)
    elif torch.backends.mps.is_available() and torch.backends.mps.is_built():
        device = "mps"
        dtype = torch.float32
        print(f"DEVICE:mps", file=sys.stderr)
    else:
        device = "cpu"
        dtype = torch.float32
        print(f"DEVICE:cpu", file=sys.stderr)

    try:
        raw = input_path.read_text(encoding="utf-8-sig", errors="replace")
    except Exception as e:
        print(f"ERROR:Failed to read subtitle file {input_path}: {e}", file=sys.stderr)
        sys.exit(1)
    srt_lines = raw.splitlines()
    if ext in (".ass", ".ssa"):
        has_cue = any("dialogue:" in line.lower() for line in srt_lines)
        if not has_cue:
            print(f"ERROR: No valid subtitle cues found in '{input_path}' (extension '{ext}'); no Dialogue lines detected — refusing to translate non-subtitle input", file=sys.stderr)
            sys.exit(1)
    elif ext == ".sub":
        blocks = parse_srt_blocks(srt_lines)
        has_microdvd = any("{" in l and "}" in l for line in srt_lines for l in [line])
        if len(blocks) == 0 and not has_microdvd:
            print(f"ERROR: No valid subtitle cues found in '{input_path}' (extension '{ext}'); parsing yielded 0 blocks — refusing to translate non-subtitle input", file=sys.stderr)
            sys.exit(1)
    else:
        has_timecode = any("-->" in line for line in srt_lines)
        if not has_timecode:
            print(f"ERROR: No valid subtitle cues found in '{input_path}' (extension '{ext}'); no '-->' timecodes found — refusing to translate binary/video content", file=sys.stderr)
            sys.exit(1)
        blocks = parse_srt_blocks(srt_lines)
        if len(blocks) == 0:
            print(f"ERROR: No valid subtitle cues found in '{input_path}' (extension '{ext}'); parsing yielded 0 valid cue blocks — refusing to translate binary/video content", file=sys.stderr)
            sys.exit(1)

    source_lang = normalize_lang(args.source_language) if args.source_language else None
    if source_lang is None:
        detected = detect_source_language(_build_detection_sample(srt_lines))
        if detected:
            source_lang = normalize_lang(detected)
            print(f"PARAKEET:Detected source language: {detected} -> {source_lang}", file=sys.stderr)
        else:
            print(f"WARN:Could not detect source language (langdetect not installed or detection failed); skipping translation", file=sys.stderr)

    if source_lang is not None and source_lang == target_lang:
        print(f"PARAKEET:Source language ({source_lang}) matches target ({target_lang}); skipping translation", file=sys.stderr)
    elif source_lang is not None:
        print(f"STAGE:translating", file=sys.stderr)
        print(f"PARAKEET:Translating subtitles from {source_lang} to {target_lang} with NLLB...", file=sys.stderr)
        srt_lines = translate_srt(srt_lines, source_lang, target_lang, device, dtype)

    try:
        has_webvtt_final = False
        ne = 0
        while ne < len(srt_lines) and srt_lines[ne].strip() == "":
            ne += 1
        if ne < len(srt_lines) and srt_lines[ne].strip().startswith("WEBVTT"):
            has_webvtt_final = True
        has_meta = has_webvtt_final
        if not has_meta:
            for _l in srt_lines:
                _st = _l.strip()
                if _st.startswith("NOTE") or _st == "STYLE" or _st.startswith("STYLE ") or _st == "REGION" or _st.startswith("REGION "):
                    has_meta = True
                    break
        has_dot_tc = False
        dot_tc_pat = re.compile(r'^(?:\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})\s*-->\s*(?:\d{2}:\d{2}:\d{2}\.\d{3}|\d{2}:\d{2}\.\d{3})')
        for _l in srt_lines:
            if dot_tc_pat.match(_l.strip()):
                has_dot_tc = True
                break
        has_needs_norm = False
        general_pat = re.compile(r'^(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})\s*-->\s*(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})')
        valid_pat = re.compile(r'^\d{2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{2}:\d{2}:\d{2},\d{3}')
        for _l in srt_lines:
            s = _l.strip()
            if general_pat.match(s) and not valid_pat.match(s):
                has_needs_norm = True
                break
        if has_webvtt_final or has_meta or has_dot_tc or has_needs_norm:
            norm = []
            i2 = 0
            if has_webvtt_final:
                i2 = ne + 1
                while i2 < len(srt_lines):
                    if srt_lines[i2].strip() == "":
                        i2 += 1
                        break
                    if "-->" in srt_lines[i2] and re.match(r'^\d{2}:\d{2}(?::\d{2})?[\.,]\d{3}\s*-->', srt_lines[i2].strip()):
                        break
                    i2 += 1
            while i2 < len(srt_lines):
                stripped = srt_lines[i2].strip()
                if stripped.startswith("NOTE") or stripped == "STYLE" or stripped.startswith("STYLE ") or stripped == "REGION" or stripped.startswith("REGION "):
                    i2 += 1
                    while i2 < len(srt_lines) and srt_lines[i2].strip() != "":
                        i2 += 1
                    if i2 < len(srt_lines) and srt_lines[i2].strip() == "":
                        i2 += 1
                    continue
                line = srt_lines[i2]
                if re.match(r'^(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})\s*-->\s*(?:\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3})', line.strip()):
                    try:
                        def _norm_tok2(m):
                            tok = m.group(0)
                            if tok.count(':') == 2:
                                return tok.replace('.', ',')
                            else:
                                parts = re.split(r'[\.,]', tok)
                                return f"00:{parts[0]},{parts[1]}"
                        line = re.sub(r'\d{2}:\d{2}:\d{2}[\.,]\d{3}|\d{2}:\d{2}[\.,]\d{3}', _norm_tok2, line)
                    except Exception as e:
                        print(f"WARN:Failed to convert timecode line '{line}': {e}", file=sys.stderr)
                norm.append(line)
                i2 += 1
            if has_webvtt_final or has_meta:
                srt_lines = norm
            elif has_dot_tc or has_needs_norm:
                srt_lines = norm
    except Exception as e:
        print(f"WARN:Final SRT normalization failed: {e}", file=sys.stderr)

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)
    srt_path = output_dir / f"{input_path.stem}.{target_lang}.srt"
    print(f"STAGE:writing", file=sys.stderr)
    srt_path.write_text("\n".join(srt_lines), encoding="utf-8")

    print(f"SRT:{srt_path}", file=sys.stderr)
    print(f"PARAKEET:Done — {len(parse_srt_blocks(srt_lines))} subtitles written", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description="Transcribe audio with Parakeet TDT v3 and/or translate subtitles with NLLB")
    parser.add_argument("--audio", default=None, help="Path to audio/video file to transcribe (omit when using --translate)")
    parser.add_argument("--translate", default=None, help="Path to existing .srt/.vtt file to translate (skips transcription)")
    parser.add_argument("--output", required=True, help="Output directory for SRT file")
    parser.add_argument("--language", default=None, help="Target language code (ISO 639-1/639-2, optional). When set, subtitles are translated to this language and the output filename includes the code")
    parser.add_argument("--source-language", default=None, help="Source language code (ISO 639-2/639-1, optional; auto-detected from the transcription if omitted)")
    parser.add_argument("--audio-index", type=int, default=None, help="Absolute audio stream index to transcribe (0-based); defaults to the file's default audio stream")
    args = parser.parse_args()

    if args.translate:
        run_translate_only(args)
        return

    if not args.audio:
        print(f"ERROR: Either --audio or --translate is required", file=sys.stderr)
        sys.exit(1)

    audio_path = Path(args.audio)
    if not audio_path.exists():
        print(f"ERROR: Audio file not found: {args.audio}", file=sys.stderr)
        sys.exit(1)

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    try:
        import torch
        import numpy as np
        from transformers import AutoModelForTDT, AutoProcessor
    except ImportError as e:
        print(f"ERROR: Missing dependency: {e}", file=sys.stderr)
        print("Run: pip install transformers torch librosa", file=sys.stderr)
        sys.exit(1)

    # Device detection
    if torch.cuda.is_available():
        device = "cuda"
        dtype = torch.float16
        print(f"DEVICE:cuda", file=sys.stderr)
    elif torch.backends.mps.is_available() and torch.backends.mps.is_built():
        device = "mps"
        dtype = torch.float32
        print(f"DEVICE:mps", file=sys.stderr)
    else:
        device = "cpu"
        dtype = torch.float32
        print(f"DEVICE:cpu", file=sys.stderr)

    print(f"PARAKEET:Loading model nvidia/parakeet-tdt-0.6b-v3...", file=sys.stderr)

    try:
        processor = AutoProcessor.from_pretrained("nvidia/parakeet-tdt-0.6b-v3")
        model = AutoModelForTDT.from_pretrained(
            "nvidia/parakeet-tdt-0.6b-v3",
            torch_dtype=dtype,
            low_cpu_mem_usage=True,
        )
        model = model.to(device)
    except Exception as e:
        print(f"ERROR:Failed to load Parakeet model: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"PARAKEET:Model loaded successfully", file=sys.stderr)

    # Load audio with librosa
    try:
        import librosa
    except ImportError:
        print(f"ERROR: librosa not installed. Run: pip install librosa", file=sys.stderr)
        sys.exit(1)

    print(f"PARAKEET:Loading audio from {audio_path.name}...", file=sys.stderr)
    try:
        if args.audio_index is not None:
            # Extract the selected audio stream (absolute FFprobe stream index)
            # to a temporary mono 16k WAV, then transcribe that.
            tmp_wav = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
            tmp_wav.close()
            try:
                extract = subprocess.run(
                    ["ffmpeg", "-y", "-i", str(audio_path),
                     "-map", f"0:{args.audio_index}",
                     "-vn", "-ac", "1", "-ar", "16000",
                     "-f", "wav", tmp_wav.name],
                    capture_output=True, text=True
                )
                if extract.returncode != 0:
                    print(f"ERROR:Failed to extract audio stream {args.audio_index}: {extract.stderr.strip()[-500:]}", file=sys.stderr)
                    sys.exit(1)
                audio, sr = librosa.load(tmp_wav.name, sr=16000, mono=True)
            finally:
                os.unlink(tmp_wav.name)
        else:
            audio, sr = librosa.load(str(audio_path), sr=16000, mono=True)
    except Exception as e:
        print(f"ERROR:Failed to load audio: {e}", file=sys.stderr)
        sys.exit(1)

    duration_sec = len(audio) / sr
    overall_rms = float(np.sqrt(np.mean(audio ** 2)))
    silence_threshold = max(0.001, overall_rms * 0.1)
    print(f"PARAKEET:Audio loaded: {duration_sec:.1f}s at {sr}Hz (overall RMS {overall_rms:.4f}, silence gate {silence_threshold:.4f})", file=sys.stderr)

    # For long audio, use local attention mode
    use_local_attn = duration_sec > 1200  # >20 minutes
    if use_local_attn:
        try:
            model.change_attention_model(
                self_attention_model="rel_pos_local_attn",
                att_context_size=[256, 256]
            )
            print(f"PARAKEET:Using local attention for long audio", file=sys.stderr)
        except Exception:
            pass  # Not supported by all model versions

    # Process in chunks and transcribe
    WINDOW_SEC = 10
    OVERLAP_SEC = 2
    STEP_SEC = WINDOW_SEC - OVERLAP_SEC
    NUDGE_SEC = 1
    MIN_OVERLAP_SEC = 0.5
    chunk_len = WINDOW_SEC * sr
    step_len = STEP_SEC * sr
    nudge_len = NUDGE_SEC * sr
    min_overlap_len = int(MIN_OVERLAP_SEC * sr)
    all_segments = []
    total_samples = len(audio)
    actual_windows = []

    print(f"STAGE:transcribing", file=sys.stderr)
    print(f"PARAKEET:Transcribing...", file=sys.stderr)

    nominal_starts = []
    s = 0
    while s < total_samples:
        nominal_starts.append(s)
        if s + chunk_len >= total_samples:
            break
        s += step_len
    # Build actual windows with silence-aligned nudging
    for idx, nominal_start in enumerate(nominal_starts):
        is_first = idx == 0
        is_last = idx == len(nominal_starts) - 1
        if is_first:
            actual_start = 0
        else:
            prev_end = actual_windows[-1][1] if actual_windows else nominal_start
            raw = nominal_start
            lo = max(0, raw - nudge_len)
            hi = min(total_samples, raw + nudge_len)
            # guarantee >=0.5s overlap with previous
            max_allowed_start = prev_end - min_overlap_len
            if hi > max_allowed_start:
                hi = max_allowed_start
            if lo > hi:
                lo = hi
            candidate = _find_lowest_rms_cut(audio, raw, sr, nudge=nudge_len)
            actual_start = max(lo, min(candidate, hi))
            if actual_start < 0:
                actual_start = 0
            if actual_start > total_samples:
                actual_start = total_samples
        nominal_end = actual_start + chunk_len
        if is_last:
            if total_samples - actual_start < chunk_len:
                # backfill trailing short chunk with preceding real audio instead of zero-padding
                actual_start = max(0, total_samples - chunk_len)
                nominal_end = total_samples
                try:
                    print(f"PARAKEET:Backfilled trailing window {idx} to {actual_start/sr:.2f}-{nominal_end/sr:.2f}s (avoid zero-pad)", file=sys.stderr)
                except Exception:
                    pass
            else:
                nominal_end = min(total_samples, nominal_end)
        else:
            nominal_end = min(total_samples, nominal_end)
        # nudge end for non-last windows
        if not is_last and nominal_end < total_samples:
            raw_end = nominal_end
            # ensure next window will still have >=0.5s overlap: actual_start + chunk_len - next_start >= min_overlap
            # next nominal start is nominal_starts[idx+1]; its eventual actual_start will be within ±nudge, so keep end near raw
            lo_e = max(actual_start + min_overlap_len, raw_end - nudge_len)
            hi_e = min(total_samples, raw_end + nudge_len)
            if lo_e <= hi_e:
                cand_e = _find_lowest_rms_cut(audio, raw_end, sr, nudge=nudge_len)
                nominal_end = max(lo_e, min(cand_e, hi_e))
        actual_end = min(total_samples, nominal_end)
        if actual_end <= actual_start:
            actual_end = min(total_samples, actual_start + sr)
        actual_windows.append((actual_start, actual_end))
    # Iterate actual windows
    for win_idx, (start, end) in enumerate(actual_windows):
        chunk = audio[start:end]
        if len(chunk) < sr:
            # Very short file (<1s) after backfill still short -> pad minimal
            if len(chunk) < chunk_len and total_samples >= chunk_len:
                # backfill already handled; this is tiny file case
                chunk = np.pad(chunk, (0, sr - len(chunk)))
            elif len(chunk) < sr:
                chunk = np.pad(chunk, (0, sr - len(chunk)))

        # Skip digitally-silent chunks (RMS below the adaptive gate derived from
        # the file's overall level; a fixed 0.015 gate silently dropped quiet-dialogue audio)
        rms = np.sqrt(np.mean(chunk ** 2))
        if rms < silence_threshold:
            all_segments.append({
                "text": "",
                "timestamps": None,
                "chunk_start": start / sr,
                "chunk_end": end / sr,
            })
            progress = (end / total_samples) * 100.0
            print(f"PROGRESS:{progress:.1f}", file=sys.stderr)
            continue

        inputs = processor([chunk], sampling_rate=16000, return_tensors="pt")
        inputs = {k: v.to(device) for k, v in inputs.items() if hasattr(v, 'to')}

        with torch.no_grad():
            output = model.generate(**inputs, return_dict_in_generate=True)

        sequences = output.sequences
        durations = getattr(output, 'durations', None)

        decoded_text = processor.decode(sequences, skip_special_tokens=True)
        decoded_text = decoded_text[0] if isinstance(decoded_text, list) else decoded_text

        decoded_timestamps = None
        if durations is not None:
            try:
                decoded_timestamps = processor.decode(
                    sequences,
                    durations=durations,
                    skip_special_tokens=True,
                )
            except Exception as _dur_e:
                try:
                    print(f"WARN:durations decode failed for window {win_idx} [{start/sr:.2f}-{end/sr:.2f}s]: {_dur_e}", file=sys.stderr)
                except Exception:
                    pass

        all_segments.append({
            "text": decoded_text,
            "timestamps": decoded_timestamps,
            "chunk_start": start / sr,
            "chunk_end": end / sr,
        })

        progress = (end / total_samples) * 100.0
        print(f"PROGRESS:{progress:.1f}", file=sys.stderr)

    try:
        _ts_cnt = sum(1 for _s in all_segments if _s.get("timestamps"))
        _fb_cnt = len(all_segments) - _ts_cnt
        print(f"PARAKEET:Chunk timestamp summary: {_ts_cnt} timestamped / {_fb_cnt} fallback (total {len(all_segments)})", file=sys.stderr)
    except Exception:
        pass

    # Build SRT content
    srt_lines = []
    subtitle_index = 1
    per_window_words = []
    windows_sec = []
    for seg in all_segments:
        chunk_start = seg["chunk_start"]
        chunk_end = seg["chunk_end"]
        windows_sec.append((chunk_start, chunk_end))
        words = []
        if seg["timestamps"]:
            ts = seg["timestamps"]
            if isinstance(ts, list) and len(ts) > 0:
                for t in ts:
                    if isinstance(t, dict) and "token" in t:
                        t["start"] = (t.get("start") or 0) + chunk_start
                        t["end"] = (t.get("end") or 0) + chunk_start
                        words.append(t)
                    elif isinstance(t, (list, tuple)) and len(t) >= 3:
                        words.append({"token": t[0], "start": (t[1] or 0) + chunk_start, "end": (t[2] or 0) + chunk_start})
        per_window_words.append(words)
    # Merge overlapping windows into single word stream
    try:
        merged_words = _merge_overlapping_word_lists(per_window_words, windows_sec, sr)
    except Exception as e:
        try:
            print(f"WARN:merged word stream failed: {e}", file=sys.stderr)
        except Exception:
            pass
        merged_words = []
        for wl in per_window_words:
            merged_words.extend(wl or [])
        merged_words.sort(key=lambda x: float(x.get("start", 0)))
    if merged_words:
        srt_segments = group_words_into_subtitles(merged_words, max_chars=42, max_duration=5.0)
        for sub in srt_segments:
            srt_lines.append(f"{subtitle_index}")
            srt_lines.append(format_srt_time(sub["start"]) + " --> " + format_srt_time(sub["end"]))
            srt_lines.append(sub["text"])
            srt_lines.append("")
            subtitle_index += 1
    if not merged_words:
        _fallback_seen = {}
        _fallback_deduped = 0
        for seg in all_segments:
            if not seg["text"] or seg["text"].strip() == "":
                continue
            text = seg["text"].strip()
            chunk_start = seg["chunk_start"]
            chunk_end = seg["chunk_end"]
            has_words = False
            if seg["timestamps"]:
                ts = seg["timestamps"]
                if isinstance(ts, list) and len(ts) > 0:
                    has_words = True
            if has_words:
                continue
            # Fallback: no word timestamps — sentence-aware split with proportional timing capped ~7s
            import re as _re_fallback
            raw_fb = text.strip()
            if not raw_fb:
                srt_lines.append(f"{subtitle_index}")
                srt_lines.append(format_srt_time(chunk_start) + " --> " + format_srt_time(chunk_end))
                srt_lines.append(text)
                srt_lines.append("")
                subtitle_index += 1
            else:
                # Split chunk text into sentences on . ! ? terminators (keep terminator)
                _sent_pat = _re_fallback.compile(r'[^.!?]+[.!?]+')
                _matches = _sent_pat.findall(raw_fb)
                _sentences = []
                if _matches:
                    for _m in _matches:
                        _s = _m.strip()
                        if _s:
                            _sentences.append(_s)
                    _leftover = _sent_pat.sub('', raw_fb).strip()
                    if _leftover:
                        _sentences.append(_leftover)
                else:
                    _sentences = [raw_fb]
                # Helper: wrap text to <=42 chars per line, <=2 lines, balanced
                def _fb_wrap(t, mc=42):
                    t = t.strip()
                    if len(t) <= mc:
                        return t
                    _ws = t.split()
                    if len(_ws) < 2:
                        return t
                    best = None
                    best_score = float("inf")
                    for k in range(1, len(_ws)):
                        left = " ".join(_ws[:k])
                        right = " ".join(_ws[k:])
                        if len(left) > mc or len(right) > mc:
                            continue
                        diff = abs(len(left) - len(right))
                        score = diff - (0.5 if len(right) >= len(left) else 0)
                        if score < best_score:
                            best_score = score
                            best = (left, right)
                    if best is not None:
                        return best[0] + "\n" + best[1]
                    k = len(_ws) // 2
                    return " ".join(_ws[:k]) + "\n" + " ".join(_ws[k:])
                total_chars_fb = sum(len(s) for s in _sentences) or 1
                total_dur_fb = max(0.0, chunk_end - chunk_start)
                # Guard: avoid zero duration (e.g. tiny chunk) -> ensure at least 1s per cue or total/ N
                if total_dur_fb <= 0:
                    total_dur_fb = 1.0
                cur_t = chunk_start
                for _sent in _sentences:
                    _sent_stripped = _sent.strip()
                    if not _sent_stripped:
                        continue
                    _norm_fb = _re_fallback.sub(r'\W+', ' ', _sent_stripped.lower()).strip()
                    _is_dup = False
                    if _norm_fb and _norm_fb in _fallback_seen:
                        _prev_end = _fallback_seen[_norm_fb]
                        if cur_t < _prev_end + 2.0:
                            _is_dup = True
                            _fallback_deduped += 1
                            try:
                                print(f"PARAKEET:Deduped fallback duplicate sentence '{_sent_stripped[:40]}' at {cur_t:.2f}s (prev end {_prev_end:.2f}s)", file=sys.stderr)
                            except Exception:
                                pass
                    ratio = len(_sent) / total_chars_fb if total_chars_fb else 1.0 / len(_sentences)
                    sent_dur = total_dur_fb * ratio
                    sent_start = cur_t
                    sent_end = cur_t + sent_dur
                    if sent_end > chunk_end:
                        sent_end = chunk_end
                    if _is_dup:
                        cur_t = sent_end
                        if cur_t >= chunk_end:
                            cur_t = chunk_end
                        continue
                    # If this sentence's allocated duration exceeds ~7s, sub-split proportionally
                    if (sent_end - sent_start) > 7.0 + 1e-9:
                        _words = _sent_stripped.split()
                        if not _words:
                            srt_lines.append(f"{subtitle_index}")
                            srt_lines.append(format_srt_time(sent_start) + " --> " + format_srt_time(sent_end))
                            srt_lines.append(_fb_wrap(_sent_stripped))
                            srt_lines.append("")
                            subtitle_index += 1
                        else:
                            # ceil division for ~7s cap
                            _sent_len = max(0.0, sent_end - sent_start)
                            num_sub = int((_sent_len + 7.0 - 1e-9) // 7.0)
                            if num_sub < 1:
                                num_sub = 1
                            # Split words into num_sub groups as evenly as possible (by word count, then char-proportional time)
                            # Distribute words evenly; if words < num_sub, reduce num_sub
                            num_sub = min(num_sub, len(_words))
                            chunk_w = (len(_words) + num_sub - 1) // num_sub
                            sub_texts = []
                            for idx in range(0, len(_words), chunk_w):
                                sub_texts.append(" ".join(_words[idx:idx + chunk_w]))
                            # Re-evaluate num_sub after chunking
                            total_sub_chars = sum(len(t) for t in sub_texts) or 1
                            sub_cur = sent_start
                            for sub in sub_texts:
                                sub_ratio = len(sub) / total_sub_chars if total_sub_chars else 1.0 / len(sub_texts)
                                sub_dur = _sent_len * sub_ratio
                                # Clamp sub duration to ~7s (should already be)
                                if sub_dur > 7.0:
                                    sub_dur = 7.0
                                sub_end = sub_cur + sub_dur
                                if sub_end > sent_end:
                                    sub_end = sent_end
                                if sub_end <= sub_cur:
                                    sub_end = sub_cur + min(1.0, max(0.5, sub_dur))
                                srt_lines.append(f"{subtitle_index}")
                                srt_lines.append(format_srt_time(sub_cur) + " --> " + format_srt_time(sub_end))
                                srt_lines.append(_fb_wrap(sub))
                                srt_lines.append("")
                                subtitle_index += 1
                                sub_cur = sub_end
                                if sub_cur >= chunk_end:
                                    break
                    else:
                        # Single cue for this sentence (apply wrap)
                        if sent_end <= sent_start:
                            sent_end = sent_start + 1.0
                        srt_lines.append(f"{subtitle_index}")
                        srt_lines.append(format_srt_time(sent_start) + " --> " + format_srt_time(sent_end))
                        srt_lines.append(_fb_wrap(_sent_stripped))
                        srt_lines.append("")
                        subtitle_index += 1
                        if _norm_fb:
                            _fallback_seen[_norm_fb] = sent_end
                    if (sent_end - sent_start) > 7.0 + 1e-9 and _norm_fb:
                        _fallback_seen[_norm_fb] = sent_end
                    cur_t = sent_end
                    if cur_t >= chunk_end:
                        # Guard against drift; clamp remainder
                        cur_t = chunk_end
                try:
                    print(f"PARAKEET:Fallback sentence split: {len(_sentences)} sentences -> {subtitle_index - 1} cues (chunk {chunk_start:.2f}-{chunk_end:.2f}s)", file=sys.stderr)
                except Exception:
                    pass
        try:
            if _fallback_deduped:
                print(f"PARAKEET:Fallback dedupe: dropped {_fallback_deduped} exact-duplicate cues in overlap regions", file=sys.stderr)
        except Exception:
            pass

    # Determine output filename
    base_name = audio_path.stem
    target_lang = normalize_lang(args.language) if args.language else None
    if target_lang:
        srt_path = output_dir / f"{base_name}.{target_lang}.srt"
    else:
        srt_path = output_dir / f"{base_name}.srt"

    # Translate to the target language when requested and it differs from the source.
    if target_lang:
        source_lang = normalize_lang(args.source_language) if args.source_language else None
        if source_lang is None:
            detected = detect_source_language(_build_detection_sample(srt_lines))
            if detected:
                source_lang = normalize_lang(detected)
                print(f"PARAKEET:Detected source language: {detected} -> {source_lang}", file=sys.stderr)
            else:
                print(f"WARN:Could not detect source language (langdetect not installed or detection failed); skipping translation", file=sys.stderr)
        if source_lang is not None and source_lang == target_lang:
            print(f"PARAKEET:Source language ({source_lang}) matches target ({target_lang}); skipping translation", file=sys.stderr)
        elif source_lang is not None:
            print(f"STAGE:translating", file=sys.stderr)
            print(f"PARAKEET:Translating subtitles from {source_lang} to {target_lang} with NLLB...", file=sys.stderr)
            srt_lines = translate_srt(srt_lines, source_lang, target_lang, device, dtype)

    # Write SRT file
    print(f"STAGE:writing", file=sys.stderr)
    srt_path.write_text("\n".join(srt_lines), encoding="utf-8")

    print(f"SRT:{srt_path}", file=sys.stderr)
    print(f"PARAKEET:Done — {subtitle_index - 1} subtitles written", file=sys.stderr)


def _find_lowest_rms_cut(audio, nominal, sr, nudge=16000, frame_ms=200):
    """Find lowest-RMS cut point within ±nudge of nominal (FluidAudio silence-aligned)."""
    try:
        total = len(audio)
        lo = max(0, int(nominal - nudge))
        hi = min(total, int(nominal + nudge))
        if hi <= lo:
            return int(nominal)
        frame_len = max(1, int(sr * frame_ms / 1000.0))
        best_pos = int(nominal)
        best_rms = None
        # stride 10ms for resolution
        stride = max(1, int(sr * 0.01))
        for p in range(lo, hi - frame_len + 1, stride):
            try:
                win = audio[p:p + frame_len]
                rms = float((win.astype(float) ** 2).mean() ** 0.5) if len(win) else float("inf")
            except Exception:
                continue
            if best_rms is None or rms < best_rms:
                best_rms = rms
                best_pos = p
        return best_pos
    except Exception as e:
        try:
            print(f"WARN:low-RMS cut search failed at {nominal}: {e}", file=sys.stderr)
        except Exception:
            pass
        return int(nominal)


def _merge_overlapping_word_lists(per_window_words, windows_sec, sr):
    """Merge overlapping window word streams cutting at largest inter-word gap >=120ms else midpoint."""
    try:
        if not per_window_words:
            return []
        # windows_sec: list of (start_sec, end_sec) for each window
        merged = list(per_window_words[0]) if per_window_words[0] else []
        for idx in range(1, len(per_window_words)):
            cur_words = per_window_words[idx] if per_window_words[idx] else []
            if not cur_words:
                continue
            if not merged:
                merged = list(cur_words)
                continue
            prev_start, prev_end = windows_sec[idx - 1]
            cur_start, cur_end = windows_sec[idx]
            overlap_start = cur_start
            overlap_end = prev_end
            if overlap_end <= overlap_start:
                # no overlap (should not happen)
                merged.extend(cur_words)
                try:
                    print(f"PARAKEET:No overlap between windows {idx-1} and {idx} ({overlap_start:.2f}-{overlap_end:.2f}s); concatenating", file=sys.stderr)
                except Exception:
                    pass
                continue
            # Find cut time: largest gap inside overlap
            # Collect words from both sides that lie in or near overlap for gap analysis
            # Use merged tail + cur_words head inside overlap
            overlap_words = []
            for w in merged:
                try:
                    ws = float(w.get("start", 0))
                    we = float(w.get("end", ws))
                except Exception:
                    continue
                if ws >= overlap_start and ws < overlap_end:
                    overlap_words.append(w)
                elif we > overlap_start and we <= overlap_end:
                    overlap_words.append(w)
                elif ws <= overlap_start and we >= overlap_end:
                    overlap_words.append(w)
            for w in cur_words:
                try:
                    ws = float(w.get("start", 0))
                    we = float(w.get("end", ws))
                except Exception:
                    continue
                if ws >= overlap_start and ws < overlap_end:
                    overlap_words.append(w)
                elif we > overlap_start and we <= overlap_end:
                    overlap_words.append(w)
                elif ws <= overlap_start and we >= overlap_end:
                    overlap_words.append(w)
            overlap_words = sorted(overlap_words, key=lambda x: float(x.get("start", 0)))
            # Also consider sorted unique words in overlap for gap
            cut = None
            if len(overlap_words) >= 2:
                best_gap = -1
                best_cut = None
                for a, b in zip(overlap_words, overlap_words[1:]):
                    try:
                        a_end = float(a.get("end", a.get("start", 0)))
                        b_start = float(b.get("start", 0))
                        gap = b_start - a_end
                    except Exception:
                        continue
                    if gap > best_gap:
                        best_gap = gap
                        # cut midway between a_end and b_start
                        best_cut = (a_end + b_start) / 2.0
                if best_gap >= 0.12 and best_cut is not None:
                    cut = best_cut
                    try:
                        print(f"PARAKEET:Overlap {idx-1}->{idx} cut at largest gap {best_gap:.3f}s -> {cut:.2f}s", file=sys.stderr)
                    except Exception:
                        pass
                else:
                    cut = (overlap_start + overlap_end) / 2.0
                    try:
                        print(f"PARAKEET:Overlap {idx-1}->{idx} gap {best_gap:.3f}s <120ms; cut at midpoint {cut:.2f}s", file=sys.stderr)
                    except Exception:
                        pass
            else:
                cut = (overlap_start + overlap_end) / 2.0
                try:
                    print(f"PARAKEET:Overlap {idx-1}->{idx} <2 words in overlap; cut at midpoint {cut:.2f}s", file=sys.stderr)
                except Exception:
                    pass
            # Keep merged words before cut, cur_words at/after cut
            new_merged = [w for w in merged if float(w.get("start", 0)) < cut]
            # For cur, keep words starting at/after cut, but also handle words that start before cut yet end after — use start threshold
            filtered_cur = [w for w in cur_words if float(w.get("start", 0)) >= cut]
            # Deduplicate tokens: if same token at same start appears in both, keep first only — already handled by cut
            # Also guard against duplicate token strings consecutively with near-identical times
            merged = new_merged + filtered_cur
        # Final sort and deduplicate exact duplicates
        merged.sort(key=lambda x: float(x.get("start", 0)))
        deduped = []
        seen = set()
        for w in merged:
            key = (str(w.get("token", "")).strip(), round(float(w.get("start", 0)), 3), round(float(w.get("end", 0)), 3))
            if key in seen:
                continue
            seen.add(key)
            deduped.append(w)
        try:
            print(f"PARAKEET:Merged {len(per_window_words)} windows -> {len(deduped)} words (overlap 2s step 8s)", file=sys.stderr)
        except Exception:
            pass
        return deduped
    except Exception as e:
        try:
            print(f"WARN:merge overlapping words failed: {e}", file=sys.stderr)
        except Exception:
            pass
        # fallback: concatenate all words sorted
        all_w = []
        for wl in per_window_words:
            all_w.extend(wl or [])
        all_w.sort(key=lambda x: float(x.get("start", 0)))
        return all_w


def group_words_into_subtitles(words, max_chars=42, max_duration=5.0):
    """Group word-level timestamps into subtitle segments (sentence-aware pipeline)."""
    if not words:
        return []
    MIN_DISPLAY = 1.0
    MAX_DISPLAY = 7.0
    MIN_GAP = 0.083
    FLICKER_THRESH = 0.5
    SHORT_DUR = 2.5
    SHORT_GAP = 0.5
    INTER_WORD_GAP = 0.8
    MAX_CPS = 20
    CONJUNCTIONS = {
        "and", "or", "but", "so", "yet", "for", "nor", "as", "because", "although", "though",
        "while", "if", "when", "where", "which", "that", "who", "whom", "whose", "whether",
        "however", "therefore", "thus", "then", "than", "until", "unless", "since", "after",
        "before", "once", "whereas", "wherever", "whenever", "with", "about", "against", "among",
        "between", "through", "during", "without", "within",
    }

    def _clamp(v, default=0.0):
        try:
            if v is None:
                return default
            fv = float(v)
            if fv != fv:
                return default
            if fv < 0:
                return 0.0
            return fv
        except Exception:
            return default

    cleaned = []
    for w in words:
        token = w.get("token", "")
        if isinstance(token, (int, float)):
            continue
        token_str = str(token).strip()
        if not token_str:
            continue
        s = _clamp(w.get("start", 0), 0.0)
        e = _clamp(w.get("end", s), s)
        if e < s:
            e = s
        cleaned.append({"token": token_str, "start": s, "end": e})
    if not cleaned:
        return []
    cleaned.sort(key=lambda x: x["start"])

    sentences = []
    cur = []
    for w in cleaned:
        cur.append(w)
        tok = w["token"]
        if tok and tok[-1] in ".!?":
            sentences.append(cur)
            cur = []
    if cur:
        sentences.append(cur)

    def _is_comma_tok(tok):
        return tok.endswith(",")

    def _is_conj(tok):
        base = tok.lower().strip().strip(",.!?;:\"'()[]{}")
        return base in CONJUNCTIONS

    def _find_tiered_split(buf, _mc=42):
        n = len(buf)
        if n < 2:
            return None
        candidates_by_tier = {1: [], 2: [], 3: []}
        for i in range(1, n):
            left_tok = buf[i - 1]["token"]
            right_tok = buf[i]["token"] if i < n else ""
            left_is_comma = _is_comma_tok(left_tok)
            right_is_conj = _is_conj(right_tok)
            if left_is_comma and right_is_conj:
                candidates_by_tier[1].append(i)
            elif left_is_comma:
                candidates_by_tier[2].append(i)
            elif right_is_conj:
                candidates_by_tier[3].append(i)
        mid = n / 2.0
        for tier in (1, 2, 3):
            cands = candidates_by_tier[tier]
            if cands:
                best = min(cands, key=lambda c: abs(c - mid))
                return best
        return n // 2

    def _wrap_text(text, mc):
        text = text.strip()
        if not text:
            return text
        if len(text) <= mc:
            return text
        words_txt = text.split()
        if len(words_txt) < 2:
            return text
        best = None
        best_score = float("inf")
        for k in range(1, len(words_txt)):
            left = " ".join(words_txt[:k])
            right = " ".join(words_txt[k:])
            if len(left) > mc or len(right) > mc:
                continue
            diff = abs(len(left) - len(right))
            score = diff - (0.5 if len(right) >= len(left) else 0)
            if score < best_score:
                best_score = score
                best = (left, right)
        if best is not None:
            return best[0] + "\n" + best[1]
        k = len(words_txt) // 2
        return " ".join(words_txt[:k]) + "\n" + " ".join(words_txt[k:])

    char_limit = max_chars * 2
    raw_cues = []
    for sent in sentences:
        buf = []
        for w in sent:
            if buf:
                gap_start = w["start"] - buf[-1]["start"]
                if gap_start > INTER_WORD_GAP:
                    raw_cues.append(buf)
                    buf = []
            tentative = buf + [w]
            text = " ".join(x["token"] for x in tentative)
            t_start = tentative[0]["start"]
            t_end = tentative[-1]["end"]
            dur = max(0.0, t_end - t_start)
            cps = (len(text) / dur) if dur > 0 else float("inf")
            exceeds = False
            if len(text) > char_limit:
                exceeds = True
            if dur > max_duration:
                exceeds = True
            if cps > MAX_CPS:
                exceeds = True
            if exceeds and buf:
                split_idx = _find_tiered_split(tentative, max_chars)
                if split_idx is None or split_idx <= 0 or split_idx >= len(tentative):
                    raw_cues.append(buf)
                    buf = [w]
                else:
                    first = tentative[:split_idx]
                    second = tentative[split_idx:]
                    raw_cues.append(first)
                    buf = second
                    while len(buf) > 1:
                        b_text = " ".join(x["token"] for x in buf)
                        b_dur = max(0.0, buf[-1]["end"] - buf[0]["start"])
                        b_cps = (len(b_text) / b_dur) if b_dur > 0 else float("inf")
                        if len(b_text) <= char_limit and b_dur <= max_duration and b_cps <= MAX_CPS:
                            break
                        inner = _find_tiered_split(buf, max_chars)
                        if inner is None or inner <= 0 or inner >= len(buf):
                            inner = len(buf) // 2
                        first2 = buf[:inner]
                        second2 = buf[inner:]
                        raw_cues.append(first2)
                        buf = second2
                        if not buf:
                            break
            else:
                buf = tentative
        if buf:
            raw_cues.append(buf)

    segments = []
    for grp in raw_cues:
        if not grp:
            continue
        s = grp[0]["start"]
        e = grp[-1]["end"]
        if e <= s:
            e = s + MIN_DISPLAY
        txt = " ".join(x["token"] for x in grp)
        segments.append({"start": s, "end": e, "text": txt, "words": list(grp)})

    if not segments:
        return []

    try:
        print(f"PARAKEET:Sentence-aware regroup: {len(cleaned)} words -> {len(segments)} raw cues from {len(sentences)} sentences", file=sys.stderr)
    except Exception:
        pass

    merged = []
    i = 0
    while i < len(segments):
        cur_seg = segments[i]
        if i + 1 < len(segments):
            nxt = segments[i + 1]
            cur_dur = cur_seg["end"] - cur_seg["start"]
            gap = nxt["start"] - cur_seg["end"]
            should_merge = False
            if cur_dur < FLICKER_THRESH:
                should_merge = True
            elif cur_dur < SHORT_DUR and gap < SHORT_GAP:
                should_merge = True
            if should_merge:
                combined_text = cur_seg["text"] + " " + nxt["text"]
                if len(combined_text) <= char_limit:
                    comb_start = cur_seg["start"]
                    comb_end = nxt["end"]
                    merged_words = cur_seg.get("words", []) + nxt.get("words", [])
                    merged_seg = {"start": comb_start, "end": comb_end, "text": combined_text, "words": merged_words}
                    try:
                        print(f"PARAKEET:Merged short cues {i} and {i+1} (dur {cur_dur:.2f}s gap {gap:.2f}s) -> 2-line cue", file=sys.stderr)
                    except Exception:
                        pass
                    merged.append(merged_seg)
                    i += 2
                    continue
        merged.append(cur_seg)
        i += 1
    segments = merged

    final_after_cps = []
    for seg in segments:
        txt = seg["text"]
        dur = max(0.0, seg["end"] - seg["start"])
        cps = len(txt) / dur if dur > 0 else float("inf")
        if cps <= MAX_CPS or dur <= 0:
            final_after_cps.append(seg)
        else:
            words_grp = seg.get("words")
            if not words_grp or len(words_grp) < 2:
                words_txt = txt.split()
                mid = len(words_txt) // 2
                if mid == 0:
                    final_after_cps.append(seg)
                    continue
                left_txt = " ".join(words_txt[:mid])
                right_txt = " ".join(words_txt[mid:])
                total_len = len(txt)
                left_ratio = len(left_txt) / total_len if total_len else 0.5
                mid_time = seg["start"] + dur * left_ratio
                left_seg = {"start": seg["start"], "end": mid_time, "text": left_txt, "words": words_grp[:mid] if words_grp else []}
                right_seg = {"start": mid_time + 0.001, "end": seg["end"], "text": right_txt, "words": words_grp[mid:] if words_grp else []}
                try:
                    print(f"PARAKEET:CPS {cps:.1f} >{MAX_CPS} split cue at tiered point", file=sys.stderr)
                except Exception:
                    pass
                final_after_cps.extend([left_seg, right_seg])
            else:
                split_idx = _find_tiered_split(words_grp, max_chars)
                if split_idx is None or split_idx <= 0 or split_idx >= len(words_grp):
                    split_idx = len(words_grp) // 2
                left_words = words_grp[:split_idx]
                right_words = words_grp[split_idx:]
                left_txt = " ".join(x["token"] for x in left_words)
                right_txt = " ".join(x["token"] for x in right_words)
                left_end = left_words[-1]["end"]
                right_start = right_words[0]["start"]
                if right_start <= left_end:
                    total_len = len(txt)
                    left_ratio = len(left_txt) / total_len if total_len else 0.5
                    mid_time = seg["start"] + dur * left_ratio
                    left_end = mid_time
                    right_start = mid_time + 0.001
                    if right_start >= seg["end"]:
                        right_start = seg["end"] - 0.1
                        left_end = right_start - 0.001
                left_seg = {"start": seg["start"], "end": left_end, "text": left_txt, "words": left_words}
                right_seg = {"start": right_start, "end": seg["end"], "text": right_txt, "words": right_words}
                try:
                    print(f"PARAKEET:CPS {cps:.1f} >{MAX_CPS} split cue at tiered point (words {len(left_words)}/{len(right_words)})", file=sys.stderr)
                except Exception:
                    pass
                final_after_cps.extend([left_seg, right_seg])
    segments = final_after_cps

    segments.sort(key=lambda x: x["start"])
    for seg in segments:
        dur = seg["end"] - seg["start"]
        if dur > MAX_DISPLAY:
            seg["end"] = seg["start"] + MAX_DISPLAY
            try:
                print(f"WARN:Capped max display to {MAX_DISPLAY}s for cue starting {seg['start']:.2f}", file=sys.stderr)
            except Exception:
                pass
    for idx, seg in enumerate(segments):
        dur = seg["end"] - seg["start"]
        if dur < MIN_DISPLAY:
            desired_end = seg["start"] + MIN_DISPLAY
            _nxt_start_for_warn = None
            if idx + 1 < len(segments):
                nxt_start = segments[idx + 1]["start"]
                _nxt_start_for_warn = nxt_start
                clamped_end = nxt_start - MIN_GAP
                if clamped_end > seg["start"]:
                    desired_end = min(desired_end, clamped_end)
                else:
                    desired_end = clamped_end
            if desired_end <= seg["start"]:
                desired_end = seg["start"] + 0.5
                try:
                    print(f"WARN:Overcrowded timing at {seg['start']:.2f}s: cue would flash (<0.5s), forced to 0.5s (next cue at {_nxt_start_for_warn})", file=sys.stderr)
                except Exception:
                    pass
            elif desired_end - seg["start"] < 0.5:
                try:
                    print(f"WARN:Clamped cue at {seg['start']:.2f}s to {desired_end - seg['start']:.2f}s (<0.5s) due to next cue at {_nxt_start_for_warn:.2f}s (overcrowded)", file=sys.stderr)
                except Exception:
                    pass
            seg["end"] = desired_end
            try:
                print(f"PARAKEET:Extended min display to {seg['end']-seg['start']:.2f}s", file=sys.stderr)
            except Exception:
                pass
    for idx in range(len(segments) - 1):
        cur = segments[idx]
        nxt = segments[idx + 1]
        gap = nxt["start"] - cur["end"]
        if gap < MIN_GAP:
            new_end = nxt["start"] - MIN_GAP
            if new_end > cur["start"]:
                if new_end - cur["start"] < 0.5:
                    try:
                        print(f"WARN:Overcrowded gap at {cur['start']:.2f}s: adjusted end {new_end - cur['start']:.2f}s (<0.5s) before next cue {nxt['start']:.2f}s", file=sys.stderr)
                    except Exception:
                        pass
                cur["end"] = new_end
                try:
                    print(f"WARN:Adjusted gap to {MIN_GAP*1000:.0f}ms between cues {idx} and {idx+1}", file=sys.stderr)
                except Exception:
                    pass
            else:
                cur["end"] = cur["start"] + 0.5
                try:
                    print(f"WARN:Overcrowded gap at {cur['start']:.2f}s: forced to 0.5s despite overlap with next cue {nxt['start']:.2f}s", file=sys.stderr)
                except Exception:
                    pass

    for seg in segments:
        wrapped = _wrap_text(seg["text"], max_chars)
        seg["text"] = wrapped

    for seg in segments:
        seg["start"] = _clamp(seg["start"], 0.0)
        seg["end"] = _clamp(seg["end"], seg["start"] + 0.5)
        if seg["end"] <= seg["start"]:
            seg["end"] = seg["start"] + 0.5
    for idx in range(len(segments) - 1):
        cur = segments[idx]
        nxt = segments[idx + 1]
        gap = _clamp(nxt["start"], 0.0) - _clamp(cur["end"], cur["start"] + 0.5)
        if gap < MIN_GAP:
            new_end = _clamp(nxt["start"], 0.0) - MIN_GAP
            if new_end > cur["start"]:
                if new_end - cur["start"] < 0.5:
                    try:
                        print(f"WARN:Overcrowded final gap at {cur['start']:.2f}s: {new_end - cur['start']:.2f}s (<0.5s) before next {nxt['start']:.2f}s", file=sys.stderr)
                    except Exception:
                        pass
                cur["end"] = new_end
            else:
                cur["end"] = cur["start"] + 0.5
                try:
                    print(f"WARN:Overcrowded final gap at {cur['start']:.2f}s: forced to 0.5s despite overlap with next {nxt['start']:.2f}s", file=sys.stderr)
                except Exception:
                    pass
            try:
                print(f"WARN:Adjusted gap to {MIN_GAP*1000:.0f}ms between cues {idx} and {idx+1} (final)", file=sys.stderr)
            except Exception:
                pass

    try:
        import difflib as _difflib_dup
        import re as _re_dup
        _DUP_THRESH = 0.85
        def _norm_dup(t):
            t = t.lower()
            t = _re_dup.sub(r'\W+', ' ', t)
            t = _re_dup.sub(r'\s+', ' ', t).strip()
            return t
        _deduped = []
        _dropped_near = 0
        _i = 0
        while _i < len(segments):
            if _i + 1 < len(segments):
                _cur = segments[_i]
                _nxt = segments[_i + 1]
                _gap = _nxt["start"] - _cur["end"]
                if _gap < 1.0:
                    _a = _norm_dup(_cur["text"])
                    _b = _norm_dup(_nxt["text"])
                    if _a and _b:
                        _ratio = _difflib_dup.SequenceMatcher(None, _a, _b).ratio()
                        if _ratio >= _DUP_THRESH:
                            _cur_dur = _cur["end"] - _cur["start"]
                            _nxt_dur = _nxt["end"] - _nxt["start"]
                            if _nxt_dur > _cur_dur:
                                try:
                                    print(f"PARAKEET:Near-duplicate suppression (ratio {_ratio:.2f} >= {_DUP_THRESH:.2f}): dropping cue {_i} '{_cur['text'][:30]}' for {_i+1}", file=sys.stderr)
                                except Exception:
                                    pass
                                _deduped.append(_nxt)
                            else:
                                _cur["end"] = max(_cur["end"], _nxt["end"])
                                try:
                                    print(f"PARAKEET:Near-duplicate suppression (ratio {_ratio:.2f} >= {_DUP_THRESH:.2f}): dropping cue {_i+1} '{_nxt['text'][:30]}' merging into {_i}", file=sys.stderr)
                                except Exception:
                                    pass
                                _deduped.append(_cur)
                            _dropped_near += 1
                            _i += 2
                            continue
            _deduped.append(segments[_i])
            _i += 1
        if _dropped_near:
            try:
                print(f"PARAKEET:Near-duplicate suppression dropped {_dropped_near} cues (threshold {_DUP_THRESH:.2f})", file=sys.stderr)
            except Exception:
                pass
        else:
            try:
                print(f"PARAKEET:Near-duplicate suppression checked {len(segments)} cues (threshold {_DUP_THRESH:.2f}): no dupes", file=sys.stderr)
            except Exception:
                pass
        segments = _deduped
    except Exception as _dup_e:
        try:
            print(f"WARN:Near-duplicate suppression failed: {_dup_e}", file=sys.stderr)
        except Exception:
            pass

    out = []
    for seg in segments:
        out.append({"start": seg["start"], "end": seg["end"], "text": seg["text"]})
    try:
        print(f"PARAKEET:Final cue count after sentence-aware pipeline: {len(out)}", file=sys.stderr)
    except Exception:
        pass
    return out


def format_srt_time(seconds):
    """Convert seconds to SRT time format: HH:MM:SS,mmm"""
    if seconds is None:
        seconds = 0.0
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = seconds % 60
    return f"{hours:02d}:{minutes:02d}:{secs:06.3f}".replace(".", ",")


if __name__ == "__main__":
    main()
