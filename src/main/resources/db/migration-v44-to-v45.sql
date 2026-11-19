-- Schema v45: reviewed insertion of new embedded Question images.
--
-- A Question image may be inserted into a locked Exam only while
-- the exact parent Question's reviewed scalar and region permits
-- are held in the current SQLite transaction.
--
-- Existing image UPDATE and DELETE guards remain unchanged.
-- No managed filesystem asset is published.

DROP TRIGGER trg_master_capture_question_images_insert;

CREATE TRIGGER trg_master_capture_question_images_insert
BEFORE INSERT ON question_images
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = exam_identity.local_id
    JOIN questions question
      ON question.booklet_id = booklet.id
    WHERE question.id = NEW.question_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_region_permits region
    JOIN master_contribution_write_permits scalar
      ON scalar.singleton_id = 1
     AND scalar.review_id = region.review_id
     AND scalar.record_type = region.record_type
     AND scalar.record_id = region.record_id
     AND scalar.exam_local_id = region.exam_local_id
    JOIN questions question
      ON question.id = region.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = question.booklet_id
    WHERE region.singleton_id = 1
      AND region.record_type = 'QUESTION'
      AND scalar.record_type = 'QUESTION'
      AND region.owner_local_id = NEW.question_id
      AND scalar.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Assigned Exam image insertion requires reviewed Question permit'
    );
END;

UPDATE schema_version
SET version = 45
WHERE version = 44;
