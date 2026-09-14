package Migrations;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@ApplicationScoped
public class DatabaseMigration {

    @PersistenceContext(unitName = "video")
    EntityManager em;

    void onStart(@Observes StartupEvent event) {
        runScript("/db/migrate-profile-session-state.sql", "ProfileSessionState migration applied");
        runScript("/db/migrate-scanstate-paths.sql", "ScanState paths migration applied");
    }

    @Transactional
    void runScript(String resource, String okMessage) {
        try (
            InputStream is = getClass().getResourceAsStream(resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
        ) {
            String content = reader.lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            String[] statements = content.split(";");
            for (String statement : statements) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    em.createNativeQuery(trimmed).executeUpdate();
                }
            }
            System.out.println("[DatabaseMigration] " + okMessage);
        } catch (Exception e) {
            System.err.println("[DatabaseMigration] migration failed for " + resource + ": " + e.getMessage());
        }
    }
}
