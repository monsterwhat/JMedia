-- Phase 4b per-show absolute-numbering override on Series.
-- absoluteSeasonLengths: raw JSON like {"1":25,"2":25}, highest precedence when
-- forceAbsoluteNumbering is TRUE. Both columns nullable; null = default-off.
-- Safe to re-run: ADD COLUMN IF NOT EXISTS is a no-op on H2 when present.
ALTER TABLE series ADD COLUMN IF NOT EXISTS absoluteSeasonLengths TEXT;
ALTER TABLE series ADD COLUMN IF NOT EXISTS forceAbsoluteNumbering BOOLEAN;
