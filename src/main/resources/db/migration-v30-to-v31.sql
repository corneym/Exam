-- Version 31: persistent contributor capture and classification
-- scope enforcement.
--
-- The migration post-processor installs all scope triggers in
-- the same transaction as this version change.

UPDATE schema_version
SET version = 31
WHERE version = 30;
