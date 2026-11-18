-- Version 41: scoped, audited master application writes for approved
-- Question and Answer inserts/updates. Ordinary coordinator capture remains
-- blocked by the v40 master-capture guards while an assignment is active.
-- No edit to any prior migration is required.

CREATE TABLE master_contribution_write_permits (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    review_id TEXT NOT NULL,
    record_type TEXT NOT NULL CHECK (record_type IN ('QUESTION', 'ANSWER')),
    record_id TEXT NOT NULL CHECK (length(record_id) = 36),
    operation TEXT NOT NULL CHECK (operation IN ('INSERT', 'UPDATE')),
    exam_local_id INTEGER NOT NULL,
    FOREIGN KEY (review_id, record_type, record_id)
        REFERENCES contribution_review_decisions(review_id, record_type, record_id),
    FOREIGN KEY (review_id)
        REFERENCES contribution_application_batches(review_id),
    FOREIGN KEY (exam_local_id)
        REFERENCES exams(id)
);

-- Permit issuance is limited to queued decisions belonging to the
-- active, locked Exam and to a return under application in this transaction.
CREATE TRIGGER trg_master_application_permit_validate
BEFORE INSERT ON master_contribution_write_permits
WHEN NOT EXISTS (
    SELECT 1
    FROM contribution_review_decisions decision
    JOIN contribution_application_batches batch
      ON batch.review_id = decision.review_id
    JOIN contribution_intake_packages packet
      ON packet.package_id = batch.package_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.assignment_id = packet.assignment_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
     AND exam_identity.retired = 0
    WHERE decision.review_id = NEW.review_id
      AND decision.record_type = NEW.record_type
      AND decision.record_id = NEW.record_id
      AND decision.choice = 'QUEUE_APPLY'
      AND exam_identity.local_id = NEW.exam_local_id
      AND (
        (NEW.operation = 'INSERT'
         AND decision.preliminary_status = 'READY_NEW')
        OR
        (NEW.operation = 'UPDATE'
         AND decision.preliminary_status = 'READY_UPDATE')
      )
      AND NOT EXISTS (
        SELECT 1 FROM contribution_application_results done
        WHERE done.review_id = decision.review_id
          AND done.record_type = decision.record_type
          AND done.record_id = decision.record_id
      )
)
BEGIN
    SELECT RAISE(ABORT, 'No approved master contribution write permit');
END;

CREATE TRIGGER trg_master_application_permit_no_update
BEFORE UPDATE ON master_contribution_write_permits
BEGIN
    SELECT RAISE(ABORT, 'Contribution write permit is immutable');
END;

DROP TRIGGER trg_master_capture_questions_insert;

CREATE TRIGGER trg_master_capture_questions_insert
BEFORE INSERT ON questions
WHEN EXISTS (
    SELECT 1 FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    WHERE exam_identity.local_id = (SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id)
)
AND NOT EXISTS (
    SELECT 1 FROM master_contribution_write_permits permit
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'QUESTION'
      AND permit.operation = 'INSERT'
      AND permit.exam_local_id = (SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

DROP TRIGGER trg_master_capture_questions_update;

CREATE TRIGGER trg_master_capture_questions_update
BEFORE UPDATE ON questions
WHEN (EXISTS (
    SELECT 1 FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    WHERE exam_identity.local_id = (SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id)
)
OR EXISTS (
    SELECT 1 FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    WHERE exam_identity.local_id = (SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id)
))
AND NOT EXISTS (
    SELECT 1 FROM master_contribution_write_permits permit
    JOIN portable_entity_identities record_identity
      ON record_identity.entity_type = 'QUESTION'
     AND record_identity.local_id = OLD.id
     AND record_identity.portable_id = permit.record_id
     AND record_identity.retired = 0
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'QUESTION'
      AND permit.operation = 'UPDATE'
      AND permit.exam_local_id = (SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id)
      AND (NEW.booklet_id IS OLD.booklet_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

DROP TRIGGER trg_master_capture_answers_insert;

CREATE TRIGGER trg_master_capture_answers_insert
BEFORE INSERT ON answers
WHEN EXISTS (
    SELECT 1 FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    WHERE exam_identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id)
)
AND NOT EXISTS (
    SELECT 1 FROM master_contribution_write_permits permit
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'ANSWER'
      AND permit.operation = 'INSERT'
      AND permit.exam_local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

DROP TRIGGER trg_master_capture_answers_update;

CREATE TRIGGER trg_master_capture_answers_update
BEFORE UPDATE ON answers
WHEN (EXISTS (
    SELECT 1 FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    WHERE exam_identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id)
)
OR EXISTS (
    SELECT 1 FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities exam_identity
      ON exam_identity.portable_id = locked.exam_portable_id
     AND exam_identity.entity_type = 'EXAM'
    WHERE exam_identity.local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id)
))
AND NOT EXISTS (
    SELECT 1 FROM master_contribution_write_permits permit
    JOIN portable_entity_identities record_identity
      ON record_identity.entity_type = 'ANSWER'
     AND record_identity.local_id = OLD.id
     AND record_identity.portable_id = permit.record_id
     AND record_identity.retired = 0
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'ANSWER'
      AND permit.operation = 'UPDATE'
      AND permit.exam_local_id = (SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id)
      AND (NEW.question_id IS OLD.question_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

CREATE TABLE contribution_application_entity_mappings (
    review_id TEXT NOT NULL,
    record_type TEXT NOT NULL,
    record_id TEXT NOT NULL,
    canonical_record_id TEXT NOT NULL,
    local_id INTEGER NOT NULL CHECK (local_id > 0),
    PRIMARY KEY (review_id, record_type, record_id),
    FOREIGN KEY (review_id, record_type, record_id)
        REFERENCES contribution_application_results(review_id, record_type, record_id),
    CHECK (length(record_id) = 36),
    CHECK (length(canonical_record_id) = 36)
);

CREATE TRIGGER trg_master_application_mapping_no_update
BEFORE UPDATE ON contribution_application_entity_mappings
BEGIN
    SELECT RAISE(ABORT, 'Canonical contribution identity mapping is immutable');
END;

CREATE TRIGGER trg_master_application_mapping_no_delete
BEFORE DELETE ON contribution_application_entity_mappings
BEGIN
    SELECT RAISE(ABORT, 'Canonical contribution identity mapping is immutable');
END;

UPDATE schema_version SET version = 41 WHERE version = 40;
