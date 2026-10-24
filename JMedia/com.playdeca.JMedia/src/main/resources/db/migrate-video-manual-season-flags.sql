-- Phase 0 manual-edit guards: per-field flags for season/episode (mirrors the
-- existing title/seriesTitle flags). Lets the import path stop overwriting
-- user-corrected season/episode numbers on rescan.
-- Safe to re-run: ADD COLUMN IF NOT EXISTS is a no-op on H2 when present.
ALTER TABLE video ADD COLUMN IF NOT EXISTS seasonManuallyEdited BOOLEAN DEFAULT FALSE;
ALTER TABLE video ADD COLUMN IF NOT EXISTS episodeManuallyEdited BOOLEAN DEFAULT FALSE;
