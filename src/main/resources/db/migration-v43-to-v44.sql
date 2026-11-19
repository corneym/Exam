-- Schema v44: reviewed Question content-part ordering.
--
-- Question content parts may be changed only while the exact
-- reviewed Question PDF-region permit is active.
-- Image byte replacement is not authorised by this migration.
-- Ordinary master capture remains locked.

DROP TRIGGER trg_master_capture_question_content_parts_insert;

CREATE TRIGGER trg_master_capture_question_content_parts_insert
BEFORE INSERT ON question_content_parts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = identity.local_id
    JOIN questions question
      ON question.booklet_id = booklet.id
    WHERE question.id = NEW.question_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_region_permits region
    JOIN master_contribution_write_permits scalar
      ON scalar.review_id = region.review_id
     AND scalar.record_type = region.record_type
     AND scalar.record_id = region.record_id
     AND scalar.exam_local_id = region.exam_local_id
    WHERE region.singleton_id = 1
      AND region.record_type = 'QUESTION'
      AND region.owner_local_id = NEW.question_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Assigned Exam content parts are read-only on the master'
    );
END;

DROP TRIGGER trg_master_capture_question_content_parts_update;

CREATE TRIGGER trg_master_capture_question_content_parts_update
BEFORE UPDATE ON question_content_parts
WHEN (
    EXISTS (
        SELECT 1
        FROM exam_work_assignment_exam_locks locked
        JOIN portable_entity_identities identity
          ON identity.portable_id = locked.exam_portable_id
         AND identity.entity_type = 'EXAM'
        JOIN exam_booklets booklet
          ON booklet.exam_id = identity.local_id
        JOIN questions question
          ON question.booklet_id = booklet.id
        WHERE question.id = OLD.question_id
    )
    OR EXISTS (
        SELECT 1
        FROM exam_work_assignment_exam_locks locked
        JOIN portable_entity_identities identity
          ON identity.portable_id = locked.exam_portable_id
         AND identity.entity_type = 'EXAM'
        JOIN exam_booklets booklet
          ON booklet.exam_id = identity.local_id
        JOIN questions question
          ON question.booklet_id = booklet.id
        WHERE question.id = NEW.question_id
    )
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_region_permits region
    JOIN master_contribution_write_permits scalar
      ON scalar.review_id = region.review_id
     AND scalar.record_type = region.record_type
     AND scalar.record_id = region.record_id
     AND scalar.exam_local_id = region.exam_local_id
    WHERE region.singleton_id = 1
      AND region.record_type = 'QUESTION'
      AND region.owner_local_id = OLD.question_id
      AND NEW.question_id IS OLD.question_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Assigned Exam content parts are read-only on the master'
    );
END;

DROP TRIGGER trg_master_capture_question_content_parts_delete;

CREATE TRIGGER trg_master_capture_question_content_parts_delete
BEFORE DELETE ON question_content_parts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = identity.local_id
    JOIN questions question
      ON question.booklet_id = booklet.id
    WHERE question.id = OLD.question_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_region_permits region
    JOIN master_contribution_write_permits scalar
      ON scalar.review_id = region.review_id
     AND scalar.record_type = region.record_type
     AND scalar.record_id = region.record_id
     AND scalar.exam_local_id = region.exam_local_id
    WHERE region.singleton_id = 1
      AND region.record_type = 'QUESTION'
      AND region.owner_local_id = OLD.question_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Assigned Exam content parts are read-only on the master'
    );
END;

UPDATE schema_version
SET version = 44
WHERE version = 43;
