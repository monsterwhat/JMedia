package Migrations;

import Services.VideoService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class DatabaseMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseMigration.class);

    @PersistenceContext(unitName = "video")
    EntityManager em;

    // Default persistence unit = settings DB (Profile and friends live here,
    // NOT in the video DB above — running Profile DDL on the wrong unit silently
    // does nothing while every Profile query fails).
    @PersistenceContext
    EntityManager settingsEm;

    @Inject
    VideoService videoService;

    void onStart(@Observes StartupEvent event) {
        runScript("/db/migrate-profile-session-state.sql", "ProfileSessionState migration applied");
        runScript("/db/migrate-scanstate-paths.sql", "ScanState paths migration applied");
        runScript(settingsEm, "/db/migrate-profile-hls-streaming.sql", "Profile hlsStreaming migration applied");
        reclassifyImportedExtras();
    }

    /**
     * One-time data backfill for Blu-ray extras (PVs, trailers, menus, textless/
     * creditless OP/ED, music videos, LOOKBACKs, recaps, encyclopedias, bonus
     * features) that were imported before the extras-keyword detection existed and
     * therefore got parsed as real episodes. Self-terminating and idempotent — see
     * VideoService.reclassifyExtras(). Runs after the SQL migrations so the schema
     * is current.
     */
    private void reclassifyImportedExtras() {
        try {
            int reclassified = videoService.reclassifyExtras();
            if (reclassified > 0) {
                LOGGER.info("Extras backfill: reclassified {} episode row(s) as extras", reclassified);
            }
        } catch (Exception e) {
            LOGGER.error("Extras backfill failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    void runScript(String resource, String okMessage) {
        runScript(em, resource, okMessage);
    }

    @Transactional
    void runScript(EntityManager em, String resource, String okMessage) {
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
