-- Phase 2 absolute-numbering: per-season episode counts (JSON, e.g. {"1":25,"2":25})
-- on Series, populated from the IMDb Dev episode list during enrichment.
-- Safe to re-run: ADD COLUMN IF NOT EXISTS is a no-op on H2 when present.
ALTER TABLE series ADD COLUMN IF NOT EXISTS seasonEpisodeCounts TEXT;
