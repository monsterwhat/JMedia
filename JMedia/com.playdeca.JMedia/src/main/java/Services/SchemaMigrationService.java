package Services;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class SchemaMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrationService.class);

    @Inject
    EntityManager em;

    void onStart(@Observes StartupEvent ev) {
        LOG.info(">>> JMedia Universal Schema Migration (v2.1) Started <<<");
        migrateSubtitleTrackTable();
    }

    @Transactional
    public void migrateSubtitleTrackTable() {
        try {
            // 1. Find the SubtitleTrack table
            List<String> tables = em.createNativeQuery(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES " +
                "WHERE TABLE_NAME ILIKE 'SUBTITLETRACK' OR TABLE_NAME ILIKE 'subtitle_track'"
            ).getResultList();

            if (tables.isEmpty()) {
                LOG.warn("No SubtitleTrack table found. Manual migration skipped.");
                return;
            }

            for (String tableName : tables) {
                LOG.info("Processing table for migration: " + tableName);
                
                // 2. Try to add is_manual if missing
                try {
                    em.createNativeQuery("ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS is_manual BOOLEAN DEFAULT FALSE").executeUpdate();
                } catch (Exception e) {
                    LOG.info("Column is_manual already exists (or ALTER failed) in " + tableName + ": " + e.getMessage());
                }

                // 3. CRITICAL: Update existing NULL values to FALSE to avoid primitive boolean assignment errors
                try {
                    int updatedRows = em.createNativeQuery("UPDATE " + tableName + " SET is_manual = FALSE WHERE is_manual IS NULL").executeUpdate();
                    if (updatedRows > 0) {
                        LOG.info("Successfully updated " + updatedRows + " existing rows in " + tableName + " set is_manual to FALSE.");
                    }
                } catch (Exception e) {
                    LOG.error("Failed to update NULL values in " + tableName + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Critical error during Universal Migration: " + e.getMessage(), e);
        }
    }
}
