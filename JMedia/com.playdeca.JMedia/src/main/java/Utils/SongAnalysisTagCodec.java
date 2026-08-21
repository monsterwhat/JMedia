package Utils;

import Models.Music.SongAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagField;
import org.jaudiotagger.tag.TagTextField;
import org.jaudiotagger.tag.flac.FlacTag;
import org.jaudiotagger.tag.id3.AbstractID3v2Frame;
import org.jaudiotagger.tag.id3.AbstractID3v2Tag;
import org.jaudiotagger.tag.id3.ID3v23Frame;
import org.jaudiotagger.tag.id3.ID3v24Frame;
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX;
import org.jaudiotagger.tag.mp4.Mp4Tag;
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField;
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag;
import org.jaudiotagger.tag.wav.WavTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Codec for persisting {@link SongAnalysis} data inside audio file tags (Option B).
 * <p>
 * The analysis is serialized to a compact JSON envelope, gzip-compressed and
 * Base64-encoded into a single String that fits into a custom tag field:
 * <ul>
 *   <li>ID3v2.3/v2.4 (MP3, WAV): TXXX frame with description "JMEDIA_ANALYSIS"</li>
 *   <li>MP4/M4A: freeform (----) atom "----:com.jmedia:JMEDIA_ANALYSIS"</li>
 *   <li>Vorbis comments (FLAC, OGG): field "JMEDIA_ANALYSIS"</li>
 * </ul>
 */
public final class SongAnalysisTagCodec {

    /** Envelope version marker. */
    private static final int VERSION = 1;

    /** Generic custom-field key used for Vorbis comment style tags. */
    public static final String FIELD_KEY = "JMEDIA_ANALYSIS";

    /** MP4 freeform atom id ("----:mean:name"). */
    public static final String MP4_FREEFORM_ID = "----:com.jmedia:JMEDIA_ANALYSIS";

    /** ID3v2 TXXX description identifying our analysis frame. */
    public static final String ID3_TXXX_DESCRIPTION = "JMEDIA_ANALYSIS";

    /** MP4 freeform issuer/descriptor parts of {@link #MP4_FREEFORM_ID}. */
    public static final String MP4_ISSUER = "com.jmedia";
    public static final String MP4_DESCRIPTOR = "JMEDIA_ANALYSIS";

    /** Upper bound for the embedded payload; larger payloads are skipped. */
    private static final int MAX_PAYLOAD_CHARS = 4_000_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SongAnalysisTagCodec() {
    }

    /**
     * Encodes a COMPLETED analysis into a gzip+base64 JSON payload.
     *
     * @param analysis the analysis to encode
     * @return the payload string, or null if there is nothing meaningful to embed or on any error
     */
    public static String encode(SongAnalysis analysis) {
        try {
            // Only completed analyses are worth embedding.
            if (analysis == null || analysis.getStatus() != SongAnalysis.AnalysisStatus.COMPLETED) {
                return null;
            }

            ObjectNode root = MAPPER.createObjectNode();
            root.put("v", VERSION);
            root.put("beatCount", analysis.getBeatCount() != null ? analysis.getBeatCount() : 0);
            root.put("averageBpm", analysis.getAverageBpm() != null ? analysis.getAverageBpm() : 0.0);
            root.put("timestamp", analysis.getAnalysisTimestamp() != null ? analysis.getAnalysisTimestamp() : 0L);

            ArrayNode beatTimes = root.putArray("beatTimes");
            if (analysis.getBeatTimes() != null) {
                for (Double t : analysis.getBeatTimes()) {
                    beatTimes.add(t != null ? t : 0.0);
                }
            }

            // Embed the stored JSON blobs as raw nodes (not double-escaped strings).
            root.set("segmentFeatures", parseRawOrEmptyArray(analysis.getSegmentFeaturesJson()));
            root.set("similarBeats", parseRawOrEmptyArray(analysis.getSimilarBeatsJson()));
            root.set("beatMetadata", parseRawOrEmptyArray(analysis.getBeatMetadataJson()));

            byte[] json = MAPPER.writeValueAsBytes(root);

            ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(gzipped)) {
                gzip.write(json);
            }
            String payload = Base64.getEncoder().encodeToString(gzipped.toByteArray());

            if (payload.length() > MAX_PAYLOAD_CHARS) {
                System.out.println("[SongAnalysisTagCodec] Encoded analysis payload too large ("
                        + payload.length() + " chars), skipping tag embedding");
                return null;
            }
            return payload;
        } catch (Exception e) {
            System.out.println("[SongAnalysisTagCodec] Failed to encode analysis: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decodes a payload produced by {@link #encode(SongAnalysis)} back into a transient
     * {@link SongAnalysis} (status COMPLETED, no id/song set).
     *
     * @param payload the tag payload
     * @return the restored analysis, or null on any failure
     */
    public static SongAnalysis decode(String payload) {
        try {
            if (payload == null || payload.isBlank()) {
                return null;
            }

            byte[] json;
            try (GZIPInputStream gzip = new GZIPInputStream(
                    new ByteArrayInputStream(Base64.getDecoder().decode(payload)))) {
                json = gzip.readAllBytes();
            }

            ObjectNode root = (ObjectNode) MAPPER.readTree(json);

            SongAnalysis analysis = new SongAnalysis();
            List<Double> beatTimes = new java.util.ArrayList<>();
            JsonNode beatsNode = root.get("beatTimes");
            if (beatsNode != null && beatsNode.isArray()) {
                for (JsonNode beat : beatsNode) {
                    beatTimes.add(beat.asDouble());
                }
            }
            analysis.setBeatTimes(beatTimes);
            analysis.setBeatCount(root.path("beatCount").asInt(0));
            analysis.setAverageBpm(root.path("averageBpm").asDouble(0.0));
            analysis.setAnalysisTimestamp(root.path("timestamp").asLong(0L));
            analysis.setSegmentFeaturesJson(root.path("segmentFeatures").toString());
            analysis.setSimilarBeatsJson(root.path("similarBeats").toString());
            analysis.setBeatMetadataJson(root.path("beatMetadata").toString());
            analysis.setStatus(SongAnalysis.AnalysisStatus.COMPLETED);
            analysis.setErrorMessage(null);
            return analysis;
        } catch (Exception e) {
            System.out.println("[SongAnalysisTagCodec] Failed to decode analysis payload: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reads the JMEDIA_ANALYSIS payload from any supported tag type.
     * Single source of truth for key names on both the write-verification and read paths.
     *
     * @param tag the tag read from an audio file (may be null)
     * @return the payload string, or null when absent/unsupported/on failure
     */
    public static String readPayloadFromTag(Tag tag) {
        try {
            if (tag == null) {
                return null;
            }
            if (tag instanceof WavTag wavTag) {
                AbstractID3v2Tag id3 = wavTag.getID3Tag();
                return id3 != null ? readFromId3(id3) : null;
            }
            if (tag instanceof AbstractID3v2Tag id3) {
                return readFromId3(id3);
            }
            if (tag instanceof FlacTag || tag instanceof VorbisCommentTag) {
                return readFirstTextField(tag, FIELD_KEY);
            }
            if (tag instanceof Mp4Tag mp4Tag) {
                return readFirstTextField(mp4Tag, MP4_FREEFORM_ID);
            }
            return null;
        } catch (Exception e) {
            System.out.println("[SongAnalysisTagCodec] Failed to read analysis payload from tag: " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds our TXXX frame by description inside an ID3v2 tag.
     */
    private static String readFromId3(AbstractID3v2Tag id3) {
        List<TagField> fields = id3.getFields("TXXX");
        if (fields == null) {
            return null;
        }
        for (TagField field : fields) {
            if (field instanceof AbstractID3v2Frame frame
                    && frame.getBody() instanceof FrameBodyTXXX body
                    && ID3_TXXX_DESCRIPTION.equals(body.getDescription())) {
                return body.getTextWithoutTrailingNulls();
            }
        }
        return null;
    }

    /**
     * Reads the first text field with the given raw key/id from a tag.
     */
    private static String readFirstTextField(Tag tag, String key) {
        List<TagField> fields = tag.getFields(key);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        TagField field = fields.get(0);
        return field instanceof TagTextField textField ? textField.getContent() : null;
    }

    /**
     * Parses a stored JSON blob into a raw node, falling back to an empty array
     * when missing or invalid so the envelope always stays structurally valid.
     */
    private static JsonNode parseRawOrEmptyArray(String json) {
        if (json != null && !json.isBlank()) {
            try {
                return MAPPER.readTree(json);
            } catch (Exception ignored) {
                // fall through to empty array
            }
        }
        return MAPPER.createArrayNode();
    }
}
