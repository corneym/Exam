-- Version 33: enforce classifiable curriculum levels for contributor
-- Question INSERT and classification-changing UPDATE operations.
--
-- The Java migration post-processor replaces the two affected
-- Question triggers within the same transaction.

UPDATE schema_version
SET version = 33
WHERE version = 32;
