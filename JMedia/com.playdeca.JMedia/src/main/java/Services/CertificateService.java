package Services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class CertificateService {
    private static final Logger LOG = LoggerFactory.getLogger(CertificateService.class);
    private static final String KEYSTORE_NAME = "keystore.p12";
    private static final String KEYSTORE_PASSWORD = "jmedia_secure";
    private static final String ALIAS = "jmedia";

    @Inject
    LoggingService loggingService;

    public boolean isHttpsConfigured() {
        Path keystorePath = Paths.get(KEYSTORE_NAME);
        return Files.exists(keystorePath);
    }

    public boolean generateSelfSignedCertificate() {
        String keytool = findKeytoolExecutable();
        LOG.info("Using keytool: {}", keytool);

        // Command to generate a 10-year self-signed cert with SAN for localhost
        String[] command = {
            keytool,
            "-genkeypair",
            "-storepass", KEYSTORE_PASSWORD,
            "-keypass", KEYSTORE_PASSWORD,
            "-keysize", "2048",
            "-keyalg", "RSA",
            "-keystore", KEYSTORE_NAME,
            "-storetype", "PKCS12",
            "-validity", "3650",
            "-alias", ALIAS,
            "-ext", "SAN=IP:127.0.0.1,DNS:localhost",
            "-dname", "CN=JMedia Self-Signed, OU=JMedia, O=JMedia, L=Local, ST=Local, C=US"
        };

        try {
            // Delete old keystore if it exists
            Files.deleteIfExists(Paths.get(KEYSTORE_NAME));

            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);

            if (finished && process.exitValue() == 0) {
                LOG.info("Keystore generated successfully.");
                applyConfigToProperties();
                return true;
            } else {
                LOG.error("Keytool failed with exit code: {}", process.exitValue());
                return false;
            }
        } catch (Exception e) {
            LOG.error("Error generating certificate: {}", e.getMessage(), e);
            return false;
        }
    }

    private void applyConfigToProperties() throws IOException {
        // Try both standard locations
        Path[] possiblePaths = {
            Paths.get("src/main/resources/application.properties"),
            Paths.get("target/classes/application.properties"),
            Paths.get("application.properties")
        };

        Path propsPath = null;
        for (Path p : possiblePaths) {
            if (Files.exists(p)) {
                propsPath = p;
                break;
            }
        }

        if (propsPath == null) {
            LOG.warn("Could not find application.properties to update");
            return;
        }

        List<String> lines = Files.readAllLines(propsPath);
        boolean hasSslFile = false;
        boolean hasSslPass = false;

        for (String line : lines) {
            if (line.contains("quarkus.http.ssl.certificate.key-store-file")) hasSslFile = true;
            if (line.contains("quarkus.http.ssl.certificate.key-store-password")) hasSslPass = true;
        }

        if (hasSslFile && hasSslPass) {
            LOG.info("SSL properties already exist in {}, skipping append.", propsPath);
            return;
        }

        StringBuilder newProps = new StringBuilder("\n# --- NATIVE HTTPS CONFIGURATION ---\n");
        newProps.append("quarkus.http.ssl.certificate.key-store-file=").append(KEYSTORE_NAME).append("\n");
        newProps.append("quarkus.http.ssl.certificate.key-store-password=").append(KEYSTORE_PASSWORD).append("\n");
        newProps.append("quarkus.http.ssl-port=8443\n");

        Files.write(propsPath, newProps.toString().getBytes(), StandardOpenOption.APPEND);
        LOG.info("SSL configuration appended to {}", propsPath);
    }

    private String findKeytoolExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            String extension = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
            File keytool = new File(javaHome, "bin/keytool" + extension);
            if (keytool.exists()) return keytool.getAbsolutePath();
        }
        return "keytool";
    }
}
