#!/usr/bin/env python3
"""
Parakeet TDT v3 transcription wrapper for JMedia.
Transcribes audio/video files using NVIDIA Parakeet TDT 0.6B v3.
Reports progress to stderr in format: PROGRESS:<percent>
Reports output path to stderr in format: SRT:<path>
"""

import argparse
import os
import sys
import time
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")

def main():
    parser = argparse.ArgumentParser(description="Transcribe audio with Parakeet TDT v3")
    parser.add_argument("--audio", required=True, help="Path to audio/video file")
    parser.add_argument("--output", required=True, help="Output directory for SRT file")
    parser.add_argument("--language", default=None, help="Language code (optional, model auto-detects)")
    args = parser.parse_args()

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
        audio, sr = librosa.load(str(audio_path), sr=16000, mono=True)
    except Exception as e:
        print(f"ERROR:Failed to load audio: {e}", file=sys.stderr)
        sys.exit(1)

    duration_sec = len(audio) / sr
    print(f"PARAKEET:Audio loaded: {duration_sec:.1f}s at {sr}Hz", file=sys.stderr)

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
    chunk_len = min(10 * sr, len(audio))  # 10-second chunks
    all_segments = []
    total_samples = len(audio)

    print(f"PARAKEET:Transcribing...", file=sys.stderr)

    for start in range(0, total_samples, chunk_len):
        end = min(start + chunk_len, total_samples)
        chunk = audio[start:end]

        # Pad if too short
        if len(chunk) < sr:  # Less than 1 second
            chunk = np.pad(chunk, (0, sr - len(chunk)))

        # Skip silent chunks (RMS energy below threshold)
        rms = np.sqrt(np.mean(chunk ** 2))
        if rms < 0.015:
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

    # Build SRT content
    srt_lines = []
    subtitle_index = 1
    for seg in all_segments:
        if not seg["text"] or seg["text"].strip() == "":
            continue

        text = seg["text"].strip()
        chunk_start = seg["chunk_start"]
        chunk_end = seg["chunk_end"]

        # Parse timestamps if available
        words = []
        if seg["timestamps"]:
            # decoded_timestamps returns list of (token, start, end) tuples
            # Timestamps are relative to chunk start — offset by chunk_start
            ts = seg["timestamps"]
            if isinstance(ts, list) and len(ts) > 0:
                for t in ts:
                    if isinstance(t, dict) and "token" in t:
                        t["start"] = (t.get("start") or 0) + chunk_start
                        t["end"] = (t.get("end") or 0) + chunk_start
                        words.append(t)
                    elif isinstance(t, (list, tuple)) and len(t) >= 3:
                        words.append({"token": t[0], "start": (t[1] or 0) + chunk_start, "end": (t[2] or 0) + chunk_start})

        if words:
            srt_segments = group_words_into_subtitles(words)
            for sub in srt_segments:
                srt_lines.append(f"{subtitle_index}")
                srt_lines.append(format_srt_time(sub["start"]) + " --> " + format_srt_time(sub["end"]))
                srt_lines.append(sub["text"])
                srt_lines.append("")
                subtitle_index += 1
        else:
            # Simple single subtitle per chunk
            srt_lines.append(f"{subtitle_index}")
            srt_lines.append(format_srt_time(chunk_start) + " --> " + format_srt_time(chunk_end))
            srt_lines.append(text)
            srt_lines.append("")
            subtitle_index += 1

    # Write SRT file
    base_name = audio_path.stem
    srt_path = output_dir / f"{base_name}.srt"
    srt_path.write_text("\n".join(srt_lines), encoding="utf-8")

    print(f"SRT:{srt_path}", file=sys.stderr)
    print(f"PARAKEET:Done — {subtitle_index - 1} subtitles written", file=sys.stderr)


def group_words_into_subtitles(words, max_chars=42, max_duration=2.0):
    """Group word-level timestamps into subtitle segments."""
    if not words:
        return []

    segments = []
    current_segment = []
    current_start = None
    current_chars = 0

    for w in words:
        token = w.get("token", "")
        start = w.get("start", 0)
        end = w.get("end", 0)

        if isinstance(token, (int, float)):
            continue  # Skip numeric tokens (token IDs)

        token_str = str(token).strip()
        if not token_str:
            continue

        if current_segment is None:
            current_segment = [w]
            current_start = start
            current_chars = len(token_str)
        else:
            duration = end - (current_start if current_start is not None else 0)
            new_chars = current_chars + 1 + len(token_str)

            if new_chars > max_chars or (duration > max_duration and current_chars > 0):
                seg_text = " ".join(str(x.get("token", "")).strip() for x in current_segment if str(x.get("token", "")).strip())
                seg_end = current_segment[-1].get("end", current_segment[-1].get("start", 0))
                segments.append({
                    "start": current_start if current_start is not None else 0,
                    "end": seg_end,
                    "text": seg_text,
                })
                current_segment = [w]
                current_start = start
                current_chars = len(token_str)
            else:
                current_segment.append(w)
                current_chars = new_chars

    if current_segment:
        seg_text = " ".join(str(x.get("token", "")).strip() for x in current_segment if str(x.get("token", "")).strip())
        seg_end = current_segment[-1].get("end", current_segment[-1].get("start", 0))
        segments.append({
            "start": current_start if current_start is not None else 0,
            "end": seg_end,
            "text": seg_text,
        })

    return segments


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
