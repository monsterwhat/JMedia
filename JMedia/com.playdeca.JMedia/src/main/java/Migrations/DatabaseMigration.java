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
        runScript("/db/migrate-migration-state.sql", "Migration state table ready");
        runScript("/db/migrate-profile-session-state.sql", "ProfileSessionState migration applied");
        runScript("/db/migrate-scanstate-paths.sql", "ScanState paths migration applied");
        runScript("/db/migrate-series-season-episode-counts.sql", "Series seasonEpisodeCounts migration applied");
        runScript("/db/migrate-series-absolute-override.sql", "Series absoluteSeasonLengths override migration applied");
        runScript("/db/migrate-video-manual-season-flags.sql", "Video manual season/episode flags migration applied");
        runScript(settingsEm, "/db/migrate-profile-hls-streaming.sql", "Profile hlsStreaming migration applied");
        reclassifyImportedExtras();
        backfillAbsoluteNumbering();
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
            if (isStepDone("reclassify-imported-extras")) {
                LOGGER.debug("Extras backfill: already completed, skipping");
                return;
            }
            int reclassified = videoService.reclassifyExtras();
            if (reclassified > 0) {
                LOGGER.info("Extras backfill: reclassified {} episode row(s) as extras", reclassified);
            }
            markStepDone("reclassify-imported-extras");
        } catch (Exception e) {
            LOGGER.error("Extras backfill failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Phase 3 one-time data backfill for absolute episode numbering: rows
     * imported before per-season lengths existed keep their legacy
     * absolute (series-wide) episode numbers. Re-runs the current naming
     * detection (now provider-backed) and writes the remapped season/episode.
     * Self-terminating and idempotent — see VideoService.backfillAbsoluteNumbering().
     */
    private void backfillAbsoluteNumbering() {
        try {
            if (isStepDone("backfill-absolute-numbering")) {
                LOGGER.debug("Absolute backfill: already completed, skipping");
                return;
            }
            int remapped = videoService.backfillAbsoluteNumbering();
            if (remapped > 0) {
                LOGGER.info("Absolute backfill: remapped {} episode row(s) to season-relative numbers", remapped);
            }
            markStepDone("backfill-absolute-numbering");
        } catch (Exception e) {
            LOGGER.error("Absolute backfill failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Boot-once bookkeeping: data backfills run a single time, then record
     * completion in migration_state so later reboots skip the full-library
     * scan. Fail-open by design — any error means "not done", i.e. the
     * backfill runs again next boot exactly like before this change.
     */
    boolean isStepDone(String step) {
        try {
            Number n = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM migration_state WHERE step_name = ?1")
                    .setParameter(1, step)
                    .getSingleResult();
            return n != null && n.longValue() > 0;
        } catch (Exception e) {
            LOGGER.debug("Migration step check failed for '{}', will run backfill: {}", step, e.getMessage());
            return false;
        }
    }

    void markStepDone(String step) {
        try {
            em.createNativeQuery(
                    "MERGE INTO migration_state (step_name, completed_at) KEY (step_name) VALUES (?1, CURRENT_TIMESTAMP)")
                    .setParameter(1, step)
                    .executeUpdate();
        } catch (Exception e) {
            LOGGER.warn("Could not record migration step '{}' as done, it will re-run next boot: {}", step, e.getMessage());
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
