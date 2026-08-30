package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ArtworkService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArtworkService.class);
    private static final String ARTWORK_DIR = "artwork";

    @Inject
    @PersistenceUnit(unitName = "music")
    EntityManager em;

    public Path getArtworkDirectory() {
        Path dir = Paths.get(System.getProperty("user.home"), ".jmedia", ARTWORK_DIR);
        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to create artwork directory {}", dir, e);
        }
        return dir;
    }

    public String saveArtwork(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String extension = sniffExtension(imageBytes);
        String sha256 = sha256Hex(imageBytes);
        String relative = ARTWORK_DIR + "/" + sha256 + extension;
        Path target = resolveFile(relative);
        if (!Files.exists(target)) {
            Path temp = target.resolveSibling("." + sha256 + ".tmp");
            try {
                Files.write(temp, imageBytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.error("Failed to write artwork file {}", target, e);
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
                return null;
            }
        }
        return relative;
    }

    public String saveArtwork(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            return saveArtwork(Base64.getDecoder().decode(base64));
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Discarding invalid base64 artwork payload ({} chars)", base64.length());
            return null;
        }
    }

    public Path resolveFile(String relative) {
        Path base = getArtworkDirectory().toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Artwork path escapes artwork directory: " + relative);
        }
        return resolved;
    }

    public byte[] readArtwork(String relative) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path file = resolveFile(relative);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            LOGGER.error("Failed to read artwork file {}", file, e);
            return null;
        }
    }

    public boolean exists(String relative) {
        return relative != null && !relative.isBlank() && Files.exists(resolveFile(relative));
    }

    public String contentType(String relative) {
        if (relative == null) return "image/jpeg";
        String lower = relative.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    public void deleteIfUnreferenced(String relative) {
        if (relative == null || relative.isBlank()) {
            return;
        }
        long songRefs = (Long) em.createQuery("select count(s) from Song s where s.artworkPath = :p")
                .setParameter("p", relative).getSingleResult();
        long enrichRefs = (Long) em.createQuery("select count(e) from SongEnrichment e where e.artworkPath = :p")
                .setParameter("p", relative).getSingleResult();
        if (songRefs + enrichRefs > 0) {
            return;
        }
        try {
            Files.deleteIfExists(resolveFile(relative));
        } catch (IOException e) {
            LOGGER.error("Failed to delete unreferenced artwork file {}", relative, e);
        }
    }

    public int cleanupOrphans() {
        int removed = 0;
        try (Stream<Path> files = Files.list(getArtworkDirectory())) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (Files.isDirectory(file)) {
                    continue;
                }
                String relative = ARTWORK_DIR + "/" + file.getFileName().toString();
                long songRefs = (Long) em.createQuery("select count(s) from Song s where s.artworkPath = :p")
                        .setParameter("p", relative).getSingleResult();
                long enrichRefs = (Long) em.createQuery("select count(e) from SongEnrichment e where e.artworkPath = :p")
                        .setParameter("p", relative).getSingleResult();
                if (songRefs + enrichRefs == 0) {
                    if (Files.deleteIfExists(file)) {
                        removed++;
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to sweep orphaned artwork files", e);
        }
        return removed;
    }

    private static String sniffExtension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return ".png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return ".gif";
        }
        return ".jpg";
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public InputStream openArtwork(String relative) {
        Path file = resolveFile(relative);
        try {
            return Files.newInputStream(file);
        } catch (IOException e) {
            LOGGER.error("Failed to open artwork file {}", file, e);
            return null;
        }
    }
}
