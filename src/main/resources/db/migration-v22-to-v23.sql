-- Version 23 selectively invalidates booklet applicability reviews.
-- The migration installer replaces only app_review_exam_booklets_update
-- through JDBC in the enclosing transaction before this resource runs.
--
-- Existing review confirmations remain authoritative: do not delete or
-- recreate them and do not backfill legacy Paper identities.

UPDATE schema_version
SET version = 23
WHERE version = 22;
