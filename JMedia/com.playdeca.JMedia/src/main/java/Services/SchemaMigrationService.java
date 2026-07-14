package Services;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SchemaMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaMigrationService.class);

    @PersistenceContext
    EntityManager em;

    void onStart(@Observes StartupEvent ev) {
        migrateSyncLog();
        migrateSettings();
    }

    @Transactional
    void migrateSyncLog() {
        String[] statements = {
            // All columns that the SyncLog entity needs — ordered to match entity declaration
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS status VARCHAR(255)",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS syncType VARCHAR(50) DEFAULT 'ALL'",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS limitCount INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS totalItems INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS itemsProcessed INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS songsSent INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS songsReceived INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS songsUpdated INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS songsCreated INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS videosSent INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS videosReceived INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS videosUpdated INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS videosCreated INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS collectionsSent INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS collectionsReceived INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS playlistsSent INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS playlistsReceived INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS subtitlesSent INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS subtitlesReceived INT DEFAULT 0",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS syncedItemIds CLOB",
            "ALTER TABLE SyncLog ALTER COLUMN syncedItemIds CLOB",
            "ALTER TABLE SyncLog ADD COLUMN IF NOT EXISTS errorMessage VARCHAR(2000)"
        };
        runStatements(statements, "SyncLog");
    }

    @Transactional
    void migrateSettings() {
        String[] statements = {
            "ALTER TABLE Settings ADD COLUMN IF NOT EXISTS syncItemLimit INT DEFAULT 0",
            "ALTER TABLE Settings ADD COLUMN IF NOT EXISTS syncSubtitlesEnabled BOOLEAN DEFAULT FALSE"
        };
        runStatements(statements, "Settings");
    }

    private void runStatements(String[] statements, String table) {
        for (String sql : statements) {
            try {
                em.createNativeQuery(sql).executeUpdate();
                LOG.info("Schema migration: applied [{}] on table [{}]", sql, table);
            } catch (Exception e) {
                LOG.warn("Schema migration: skipped [{}] on table [{}] — {}", sql, table, e.getMessage());
            }
        }
    }

}
