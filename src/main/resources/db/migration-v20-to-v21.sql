-- Version 21 introduces database-enforced invalidation of booklet
-- applicability-review confirmations.
--
-- The migration installer creates the triggers through JDBC before
-- this resource runs. This preserves the existing simple SQL
-- script executor, which does not parse CREATE TRIGGER bodies.
--
-- Reviews made before invalidation was enforced cannot be proven
-- current. Conservatively require those booklets to be reviewed again.

DELETE FROM booklet_applicability_reviews;

UPDATE schema_version
SET version = 21
WHERE version = 20;
