package Services;

import Models.Video.AudioTrack;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Audio auto-selection engine mirroring {@link SubtitlePreferenceEngine} conventions.
 * <p>
 * Fallback order (only when no saved per-video / per-profile preference exists):
 * 1) preferred-language stereo (channels == 2, audible)
 * 2) preferred-language any channels (audible)
 * 3) container "default" flag (audible)
 * 4) first audible track
 * <p>
 * Within each tier: known language beats unknown/missing language, and
 * commentary / descriptive variants are deprioritized but not excluded.
 * Tracks with zero/null channels are never selected when an audible alternative exists.
 */
@ApplicationScoped
public class AudioPreferenceEngine {

    private static final Logger LOG = LoggerFactory.getLogger(AudioPreferenceEngine.class);

    private static final Set<String> UNKNOWN_CODES = Set.of("und", "unknown", "");

    private static final Map<String, String> TWO_LETTER_MAP = Map.ofEntries(
            Map.entry("eng", "en"), Map.entry("fre", "fr"), Map.entry("spa", "es"), Map.entry("deu", "de"),
            Map.entry("ita", "it"), Map.entry("por", "pt"), Map.entry("rus", "ru"), Map.entry("jpn", "ja"),
            Map.entry("kor", "ko"), Map.entry("chi", "zh"),
            Map.entry("en", "en"), Map.entry("es", "es"), Map.entry("fr", "fr"), Map.entry("de", "de"),
            Map.entry("it", "it"), Map.entry("pt", "pt"), Map.entry("ru", "ru"), Map.entry("ja", "ja"),
            Map.entry("ko", "ko"), Map.entry("zh", "zh")
    );

    /**
     * Select the best audio track according to the fallback order.
     * Caller must check saved per-video/per-profile preference BEFORE calling this method;
     * this engine implements the automatic fallback only.
     *
     * @param tracks            all audio tracks for the video (in ffprobe order)
     * @param preferredLanguage ISO 639-2 or 639-1 code, e.g. "eng" or "en"; null defaults to "eng"
     * @return best track or null if list is empty
     */
    public AudioTrack selectBestAudioTrack(List<AudioTrack> tracks, String preferredLanguage) {
        if (tracks == null || tracks.isEmpty()) {
            return null;
        }

        String prefLang = normalizeLanguage(preferredLanguage);
        LOG.debug("Audio auto-selection: {} tracks, preferredLanguage={}", tracks.size(), prefLang);

        // Never select a zero-channel track when an audible alternative exists
        List<AudioTrack> audible = tracks.stream()
                .filter(t -> t.channels != null && t.channels > 0)
                .collect(Collectors.toList());
        List<AudioTrack> candidates = audible.isEmpty() ? tracks : audible;
        if (audible.isEmpty()) {
            LOG.warn("All audio tracks have zero/null channels; falling back to silent track selection");
        }

        // Priority 1: preferred-language stereo (channels == 2), non-descriptive first
        AudioTrack pick = findPreferredStereo(candidates, prefLang);
        if (pick != null) {
            LOG.info("Audio auto-selected preferred-language stereo track id={} lang={} channels={}", pick.id, pick.languageCode, pick.channels);
            return pick;
        }

        // Priority 2: preferred-language any channels, non-descriptive first
        pick = findPreferredAnyChannel(candidates, prefLang);
        if (pick != null) {
            LOG.info("Audio auto-selected preferred-language track id={} lang={} channels={}", pick.id, pick.languageCode, pick.channels);
            return pick;
        }

        // Priority 3: container default flag among audible tracks
        pick = findContainerDefault(candidates);
        if (pick != null) {
            LOG.info("Audio auto-selected container-default track id={} lang={} channels={}", pick.id, pick.languageCode, pick.channels);
            return pick;
        }

        // Priority 4: first audible track (known language first, non-descriptive first)
        pick = findFirstAudible(candidates);
        if (pick != null) {
            LOG.info("Audio auto-selected first audible track id={} lang={} channels={}", pick.id, pick.languageCode, pick.channels);
            return pick;
        }

        // Ultimate fallback: first track even if silent (all were silent)
        LOG.info("Audio auto-selected fallback first track id={}", tracks.get(0).id);
        return tracks.get(0);
    }

    private AudioTrack findPreferredStereo(List<AudioTrack> candidates, String prefLang) {
        List<AudioTrack> stereo = candidates.stream()
                .filter(t -> isPreferredLanguage(t.languageCode, prefLang))
                .filter(t -> t.channels != null && t.channels == 2)
                .collect(Collectors.toList());
        if (stereo.isEmpty()) return null;
        stereo.sort(this::compareByVariantAndLanguage);
        // Prefer non-descriptive; if all are descriptive, still return the first
        Optional<AudioTrack> nonDescriptive = stereo.stream().filter(t -> !isDescriptiveVariant(t)).findFirst();
        return nonDescriptive.orElse(stereo.get(0));
    }

    private AudioTrack findPreferredAnyChannel(List<AudioTrack> candidates, String prefLang) {
        List<AudioTrack> langMatches = candidates.stream()
                .filter(t -> isPreferredLanguage(t.languageCode, prefLang))
                .collect(Collectors.toList());
        if (langMatches.isEmpty()) return null;
        langMatches.sort(this::compareByVariantAndLanguage);
        Optional<AudioTrack> nonDescriptive = langMatches.stream().filter(t -> !isDescriptiveVariant(t)).findFirst();
        if (nonDescriptive.isPresent()) return nonDescriptive.get();
        return langMatches.get(0);
    }

    private AudioTrack findContainerDefault(List<AudioTrack> candidates) {
        List<AudioTrack> defaults = candidates.stream()
                .filter(t -> t.isDefault)
                .collect(Collectors.toList());
        if (defaults.isEmpty()) return null;
        defaults.sort(this::compareByVariantAndLanguage);
        Optional<AudioTrack> nonDescriptive = defaults.stream().filter(t -> !isDescriptiveVariant(t)).findFirst();
        if (nonDescriptive.isPresent()) return nonDescriptive.get();
        return defaults.get(0);
    }

    private AudioTrack findFirstAudible(List<AudioTrack> candidates) {
        if (candidates.isEmpty()) return null;
        List<AudioTrack> sorted = new ArrayList<>(candidates);
        sorted.sort(this::compareByVariantAndLanguage);
        Optional<AudioTrack> nonDescriptive = sorted.stream().filter(t -> !isDescriptiveVariant(t)).findFirst();
        if (nonDescriptive.isPresent()) return nonDescriptive.get();
        return sorted.get(0);
    }

    /**
     * Comparator for ordering within a tier:
     * known language before unknown, non-descriptive before descriptive.
     */
    private int compareByVariantAndLanguage(AudioTrack a, AudioTrack b) {
        boolean aUnknown = isUnknownLanguage(a.languageCode);
        boolean bUnknown = isUnknownLanguage(b.languageCode);
        if (aUnknown != bUnknown) {
            return aUnknown ? 1 : -1;
        }
        boolean aDesc = isDescriptiveVariant(a);
        boolean bDesc = isDescriptiveVariant(b);
        if (aDesc != bDesc) {
            return aDesc ? 1 : -1;
        }
        return 0;
    }

    private boolean isPreferredLanguage(String trackLang, String prefLang) {
        if (trackLang == null || prefLang == null) return false;
        String tl = trackLang.trim().toLowerCase(Locale.ROOT);
        String pl = prefLang.trim().toLowerCase(Locale.ROOT);
        if (tl.equals(pl)) return true;
        String tl2 = TWO_LETTER_MAP.get(tl);
        String pl2 = TWO_LETTER_MAP.get(pl);
        if (tl2 != null && pl2 != null) {
            return tl2.equals(pl2);
        }
        return false;
    }

    private boolean isUnknownLanguage(String code) {
        if (code == null || code.isBlank()) return true;
        return UNKNOWN_CODES.contains(code.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Descriptive / commentary variants are deprioritized within each tier.
     * Read-before-ordering: ffprobeAudioService captures title from tags.title
     * (e.g. "Director's Commentary", "Descriptive Audio"). Never exclude, only rank lower.
     */
    private boolean isDescriptiveVariant(AudioTrack track) {
        if (track == null) return false;
        String title = track.title != null ? track.title.toLowerCase(Locale.ROOT) : "";
        String display = track.displayName != null ? track.displayName.toLowerCase(Locale.ROOT) : "";
        String haystack = title + " " + display;
        return haystack.contains("commentary")
                || haystack.contains("descriptive")
                || haystack.contains("audio description")
                || haystack.contains(" visually impaired")
                || haystack.contains(" descriptive");
    }

    private String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank()) return "eng";
        String trimmed = lang.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() == 2) {
            // Map 2-letter to 3-letter for consistent comparison, keep as 2-letter normalized form for map lookup
            // Return the 3-letter form if known, else keep original
            for (Map.Entry<String, String> e : TWO_LETTER_MAP.entrySet()) {
                if (e.getValue().equals(trimmed) && e.getKey().length() == 3) {
                    return e.getKey();
                }
            }
        }
        if (trimmed.length() == 3) return trimmed;
        return trimmed;
    }

    /**
     * Convenience overload that resolves preferred language from subtitle preferences convention:
     * uses the provided language or defaults to English.
     */
    public AudioTrack selectBestAudioTrack(List<AudioTrack> tracks) {
        return selectBestAudioTrack(tracks, "eng");
    }
}
