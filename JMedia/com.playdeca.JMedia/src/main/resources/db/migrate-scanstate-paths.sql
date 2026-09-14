-- Widens ScanState processed-path storage so long media paths no longer
-- abort scan-state commits (DataException: value too long for VARCHAR(255)).
-- Safe to re-run: setting the same type twice is a no-op on H2.
ALTER TABLE ScanState_processedPaths ALTER COLUMN processedPaths SET DATA TYPE VARCHAR(2048);
