package Utils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA attribute converter that stores a large JSON {@link String} as a
 * byte[] BLOB, gzip-compressed when it shrinks the payload.
 *
 * <p>Storage format: one header byte followed by the body.
 * <ul>
 *   <li>{@code 0x00} — body is raw UTF-8 bytes (used when gzip would not shrink, e.g. tiny or incompressible JSON).</li>
 *   <li>{@code 0x01} — body is the gzip-compressed UTF-8 bytes.</li>
 * </ul>
 *
 * <p>The converter is stateless and thread-safe. It is bound explicitly to a column
 * via {@code @Convert(converter = GzipJsonConverter.class)} — it is <em>not</em>
 * {@code autoApply = true}, so no other String column is affected.
 *
 * <p>All Java-side callers continue to read/write an uncompressed {@link String}
 * through the entity getter/setter; (de)compression is transparent.
 */
@Converter
public class GzipJsonConverter implements AttributeConverter<String, byte[]> {

    private static final Logger LOGGER = LoggerFactory.getLogger(GzipJsonConverter.class);
    private static final byte RAW = 0x00;
    private static final byte GZIP = 0x01;
    /** Below this many bytes gzip never helps measureably, so skip it. */
    private static final int MIN_GZIP_SIZE = 64;

    @Override
    public byte[] convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        byte[] raw = attribute.getBytes(StandardCharsets.UTF_8);
        if (raw.length < MIN_GZIP_SIZE) {
            byte[] out = new byte[raw.length + 1];
            out[0] = RAW;
            System.arraycopy(raw, 0, out, 1, raw.length);
            return out;
        }
        byte[] compressed = gzip(raw);
        // Only use the compressed form if it actually shrank the payload (plus the 1-byte header).
        if (compressed != null && compressed.length + 1 < raw.length) {
            byte[] out = new byte[compressed.length + 1];
            out[0] = GZIP;
            System.arraycopy(compressed, 0, out, 1, compressed.length);
            return out;
        }
        byte[] out = new byte[raw.length + 1];
        out[0] = RAW;
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    }

    @Override
    public String convertToEntityAttribute(byte[] dbData) {
        if (dbData == null || dbData.length == 0) {
            return null;
        }
        try {
            switch (dbData[0]) {
                case RAW:
                    return new String(dbData, 1, dbData.length - 1, StandardCharsets.UTF_8);
                case GZIP:
                    return new String(gunzip(dbData, 1, dbData.length - 1), StandardCharsets.UTF_8);
                default:
                    return new String(dbData, StandardCharsets.UTF_8);
            }
        } catch (RuntimeException e) {
            LOGGER.error("GzipJsonConverter: failed to decode {} bytes of stored data", dbData.length, e);
            return null;
        }
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        } catch (IOException e) {
            LOGGER.error("GzipJsonConverter: gzip compression failed for {} bytes", data.length, e);
            return null;
        }
        return bos.toByteArray();
    }

    private static byte[] gunzip(byte[] data, int offset, int length) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(length * 2);
        byte[] buffer = new byte[8192];
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data, offset, length))) {
            int n;
            while ((n = gz.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new IllegalStateException("GzipJsonConverter: gzip decompression failed", e);
        }
        return bos.toByteArray();
    }
}
