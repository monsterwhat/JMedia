package Migrations;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idempotent startup migration that drops obsolete PlaybackState columns and
 * element-collection tables left behind by commit dc68819.
 *
 * Hibernate's "update" strategy never drops columns, so old H2 file DBs still
 * carry SECONDARYCUEINDEX, USINGSECONDARYQUEUE, SECONDARYCUE, and
 * SECONDARYORIGINALCUE — their NOT-NULL constraints cause H2 error 23502 on
 * every new insert (PlaybackStateService.getOrCreateState).
 *
 * This migration runs before any playback tick and is safe on fresh DBs
 * (all statements use IF EXISTS / IF NOT EXISTS).
 */
@ApplicationScoped
public class MusicPlaybackStateMigration {

    private static final Logger LOG = LoggerFactory.getLogger(MusicPlaybackStateMigration.class);

    @PersistenceContext(unitName = "music")
    EntityManager em;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        try {
            dropObsoleteColumns();
            dropObsoleteCollectionTables();
            LOG.info("[MusicPlaybackStateMigration] Obsolete PlaybackState schema cleanup completed");
        } catch (Exception e) {
            LOG.error("[MusicPlaybackStateMigration] Migration failed unexpectedly", e);
        }
    }

    // -------------------------------------------------------------------------
    //  Column drops (secondaryCueIndex / usingSecondaryQueue / secondaryCue /
    //  secondaryOriginalCue may exist as plain columns on legacy H2 schemas)
    // -------------------------------------------------------------------------

    @Transactional
    void dropObsoleteColumns() {
        String[] columnStatements = {
            "ALTER TABLE PlaybackState DROP COLUMN IF EXISTS SECONDARYCUEINDEX",
            "ALTER TABLE PlaybackState DROP COLUMN IF EXISTS USINGSECONDARYQUEUE",
            "ALTER TABLE PlaybackState DROP COLUMN IF EXISTS SECONDARYCUE",
            "ALTER TABLE PlaybackState DROP COLUMN IF EXISTS SECONDARYORIGINALCUE"
        };

        for (String sql : columnStatements) {
            try {
                em.createNativeQuery(sql).executeUpdate();
                LOG.info("[MusicPlaybackStateMigration] Executed: {}", sql);
            } catch (Exception e) {
                LOG.warn("[MusicPlaybackStateMigration] Statement failed (may not exist): {} — {}",
                        sql, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    //  Element-collection table drops (secondaryCue / secondaryOriginalCue
    //  stored as @ElementCollection tables).  H2 is case-insensitive for
    //  unquoted identifiers, but we cover both casings for safety.
    // -------------------------------------------------------------------------

    @Transactional
    void dropObsoleteCollectionTables() {
        String[] tableStatements = {
            "DROP TABLE IF EXISTS PlaybackState_secondaryCue",
            "DROP TABLE IF EXISTS PlaybackState_secondaryOriginalCue",
            "DROP TABLE IF EXISTS PLAYBACKSTATE_SECONDARYCUE",
            "DROP TABLE IF EXISTS PLAYBACKSTATE_SECONDARYORIGINALCUE"
        };

        for (String sql : tableStatements) {
            try {
                em.createNativeQuery(sql).executeUpdate();
                LOG.info("[MusicPlaybackStateMigration] Executed: {}", sql);
            } catch (Exception e) {
                LOG.warn("[MusicPlaybackStateMigration] Statement failed (may not exist): {} — {}",
                        sql, e.getMessage());
            }
        }
    }
}
