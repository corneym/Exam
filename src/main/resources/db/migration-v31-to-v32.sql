-- Version 32: permit narrowly scoped pending MCQ Shared Context
-- updates during contributor assignment capture.
--
-- The Java migration post-processor replaces the original Booklet
-- UPDATE trigger and installs its pending-context ownership guard.
-- Both operations execute within the migration transaction.

UPDATE schema_version
SET version = 32
WHERE version = 31;
