-- Schema v42: scoped PDF-region writes for an approved Question/Answer return.
-- Master PDFs are never replaced by this migration. The application must
-- reverify the original return archive and master source PDFs under the
-- managed-asset publication lock before it authorises region mutation.
-- All child permits are transient and cleared before assignment closure.

CREATE TABLE master_contribution_region_permits (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    review_id TEXT NOT NULL,
    record_type TEXT NOT NULL CHECK (record_type IN ('QUESTION', 'ANSWER')),
    record_id TEXT NOT NULL,
    exam_local_id INTEGER NOT NULL REFERENCES exams(id),
    owner_local_id INTEGER NOT NULL CHECK (owner_local_id > 0),
    FOREIGN KEY (review_id, record_type, record_id)
        REFERENCES contribution_review_decisions(review_id, record_type, record_id)
);

CREATE TRIGGER trg_master_region_permit_validate
BEFORE INSERT ON master_contribution_region_permits
WHEN NOT EXISTS (
    SELECT 1
    FROM master_contribution_write_permits scalar
    WHERE scalar.singleton_id = 1
      AND scalar.review_id = NEW.review_id
      AND scalar.record_type = NEW.record_type
      AND scalar.record_id = NEW.record_id
      AND scalar.exam_local_id = NEW.exam_local_id
      AND (
          scalar.operation = 'INSERT'
          OR EXISTS (
              SELECT 1 FROM portable_entity_identities owner_identity
              WHERE owner_identity.entity_type = NEW.record_type
                AND owner_identity.local_id = NEW.owner_local_id
                AND owner_identity.portable_id = NEW.record_id
                AND owner_identity.retired = 0
          )
      )
      AND (
          (NEW.record_type = 'QUESTION' AND EXISTS (
              SELECT 1 FROM questions question
              JOIN exam_booklets booklet ON booklet.id = question.booklet_id
              WHERE question.id = NEW.owner_local_id
                AND booklet.exam_id = NEW.exam_local_id
          ))
          OR
          (NEW.record_type = 'ANSWER' AND EXISTS (
              SELECT 1 FROM answers answer
              JOIN questions question ON question.id = answer.question_id
              JOIN exam_booklets booklet ON booklet.id = question.booklet_id
              WHERE answer.id = NEW.owner_local_id
                AND booklet.exam_id = NEW.exam_local_id
          ))
      )
)
BEGIN
    SELECT RAISE(ABORT, 'No approved master PDF-region permit');
END;

CREATE TRIGGER trg_master_region_permit_no_update
BEFORE UPDATE ON master_contribution_region_permits
BEGIN
    SELECT RAISE(ABORT, 'Master PDF-region permit is immutable');
END;

CREATE TRIGGER trg_master_region_main_permit_release_guard
BEFORE DELETE ON master_contribution_write_permits
WHEN EXISTS (SELECT 1 FROM master_contribution_region_permits)
BEGIN
    SELECT RAISE(ABORT, 'Master PDF-region permit must be released first');
END;

-- Replacement of exactly one v40 capture trigger: trg_master_capture_question_regions_insert.
DROP TRIGGER trg_master_capture_question_regions_insert;
CREATE TRIGGER trg_master_capture_question_regions_insert
BEFORE INSERT ON question_regions
WHEN EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id))
 AND NOT (EXISTS (
        SELECT 1 FROM master_contribution_region_permits region
        JOIN master_contribution_write_permits scalar
          ON scalar.review_id = region.review_id
         AND scalar.record_type = region.record_type
         AND scalar.record_id = region.record_id
         AND scalar.exam_local_id = region.exam_local_id
        WHERE region.singleton_id = 1
          AND region.record_type = 'QUESTION'
          AND region.owner_local_id = NEW.question_id
          
    ))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- Replacement of exactly one v40 capture trigger: trg_master_capture_question_regions_update.
DROP TRIGGER trg_master_capture_question_regions_update;
CREATE TRIGGER trg_master_capture_question_regions_update
BEFORE UPDATE ON question_regions
WHEN (EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id)) OR EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id)))
 AND NOT (EXISTS (
        SELECT 1 FROM master_contribution_region_permits region
        JOIN master_contribution_write_permits scalar
          ON scalar.review_id = region.review_id
         AND scalar.record_type = region.record_type
         AND scalar.record_id = region.record_id
         AND scalar.exam_local_id = region.exam_local_id
        WHERE region.singleton_id = 1
          AND region.record_type = 'QUESTION'
          AND region.owner_local_id = OLD.question_id
          AND NEW.question_id IS OLD.question_id
    ))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- Replacement of exactly one v40 capture trigger: trg_master_capture_question_regions_delete.
DROP TRIGGER trg_master_capture_question_regions_delete;
CREATE TRIGGER trg_master_capture_question_regions_delete
BEFORE DELETE ON question_regions
WHEN EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id))
 AND NOT (EXISTS (
        SELECT 1 FROM master_contribution_region_permits region
        JOIN master_contribution_write_permits scalar
          ON scalar.review_id = region.review_id
         AND scalar.record_type = region.record_type
         AND scalar.record_id = region.record_id
         AND scalar.exam_local_id = region.exam_local_id
        WHERE region.singleton_id = 1
          AND region.record_type = 'QUESTION'
          AND region.owner_local_id = OLD.question_id
          
    ))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- Replacement of exactly one v40 capture trigger: trg_master_capture_answer_regions_insert.
DROP TRIGGER trg_master_capture_answer_regions_insert;
CREATE TRIGGER trg_master_capture_answer_regions_insert
BEFORE INSERT ON answer_regions
WHEN EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = NEW.answer_id))
 AND NOT (EXISTS (
        SELECT 1 FROM master_contribution_region_permits region
        JOIN master_contribution_write_permits scalar
          ON scalar.review_id = region.review_id
         AND scalar.record_type = region.record_type
         AND scalar.record_id = region.record_id
         AND scalar.exam_local_id = region.exam_local_id
        WHERE region.singleton_id = 1
          AND region.record_type = 'ANSWER'
          AND region.owner_local_id = NEW.answer_id
          
    ))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- Replacement of exactly one v40 capture trigger: trg_master_capture_answer_regions_update.
DROP TRIGGER trg_master_capture_answer_regions_update;
CREATE TRIGGER trg_master_capture_answer_regions_update
BEFORE UPDATE ON answer_regions
WHEN (EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = OLD.answer_id)) OR EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = NEW.answer_id)))
 AND NOT (EXISTS (
        SELECT 1 FROM master_contribution_region_permits region
        JOIN master_contribution_write_permits scalar
          ON scalar.review_id = region.review_id
         AND scalar.record_type = region.record_type
         AND scalar.record_id = region.record_id
         AND scalar.exam_local_id = region.exam_local_id
        WHERE region.singleton_id = 1
          AND region.record_type = 'ANSWER'
          AND region.owner_local_id = OLD.answer_id
          AND NEW.answer_id IS OLD.answer_id
    ))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- Replacement of exactly one v40 capture trigger: trg_master_capture_answer_regions_delete.
DROP TRIGGER trg_master_capture_answer_regions_delete;
CREATE TRIGGER trg_master_capture_answer_regions_delete
BEFORE DELETE ON answer_regions
WHEN EXISTS (SELECT 1 FROM exam_work_assignment_exam_locks locked JOIN portable_entity_identities identity ON identity.portable_id = locked.exam_portable_id AND identity.entity_type = 'EXAM' WHERE identity.local_id = (SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = OLD.answer_id))
 AND NOT (EXISTS (
        SELECT 1 FROM master_contribution_region_permits region
        JOIN master_contribution_write_permits scalar
          ON scalar.review_id = region.review_id
         AND scalar.record_type = region.record_type
         AND scalar.record_id = region.record_id
         AND scalar.exam_local_id = region.exam_local_id
        WHERE region.singleton_id = 1
          AND region.record_type = 'ANSWER'
          AND region.owner_local_id = OLD.answer_id
          
    ))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

UPDATE schema_version SET version = 42 WHERE version = 41;
