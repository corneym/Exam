-- Version 26 enables transactional, material-change revision tracking.
-- The migration installer creates and verifies the revision triggers
-- through JDBC before this schema-version update is executed.
--
-- Existing portable UUIDs, initial revision UUIDs, installation
-- identity, aliases, and retirement records remain unchanged.

UPDATE schema_version
SET version = 26
WHERE version = 25;
