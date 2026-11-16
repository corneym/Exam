-- Version 30: persistent structural write barriers for
-- contributor assignment mode.
--
-- SqliteAssignmentStructureTriggers installs the trigger bodies
-- in the same migration transaction.

UPDATE schema_version
SET version = 30
WHERE version = 29;
