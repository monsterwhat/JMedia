-- One-time bookkeeping for data backfills: records completed steps so reboots
-- skip already-finished work instead of re-scanning the whole library.
-- Safe to re-run: IF NOT EXISTS is a no-op on H2 when present.
CREATE TABLE IF NOT EXISTS migration_state (
    step_name VARCHAR(255) PRIMARY KEY,
    completed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
