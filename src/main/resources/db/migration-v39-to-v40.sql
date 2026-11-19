-- Schema v40: master capture lock and atomic return-completion verification.
-- Existing Exam structure is guarded by v39. These triggers protect the
-- Question/Answer capture graph while a coordinator assignment is ACTIVE.
-- The only exception is one approved, existing Question text update inside
-- the same SQLite transaction that records its application and closes the
-- assignment. Unrelated Exams remain editable.
-- This new file is append-only schema history; do not edit migrations v38/v39.

-- questions: INSERT guard
CREATE TRIGGER trg_master_capture_questions_insert
BEFORE INSERT ON questions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- questions: UPDATE guard
CREATE TRIGGER trg_master_capture_questions_update
BEFORE UPDATE ON questions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id
    )
))
AND NOT (
    OLD.question_text IS NOT NEW.question_text
    AND OLD.id IS NEW.id
    AND OLD.booklet_id IS NEW.booklet_id
    AND OLD.classification_node_id IS NEW.classification_node_id
    AND OLD.question_code IS NEW.question_code
    AND OLD.marks IS NEW.marks
    AND OLD.shared_context_capture_required IS NEW.shared_context_capture_required
    AND OLD.source_question_id IS NEW.source_question_id
    AND OLD.shared_context_id IS NEW.shared_context_id
    AND OLD.response_type IS NEW.response_type
    AND OLD.source_capture_required IS NEW.source_capture_required
    AND EXISTS (
        SELECT 1
        FROM contribution_application_batches batch
        JOIN contribution_review_decisions decision
          ON decision.review_id = batch.review_id
        JOIN contribution_intake_packages packet
          ON packet.package_id = batch.package_id
        JOIN exam_work_assignment_exam_locks locked
          ON locked.assignment_id = packet.assignment_id
        JOIN portable_entity_identities question_identity
          ON question_identity.entity_type = 'QUESTION'
         AND question_identity.local_id = OLD.id
         AND question_identity.portable_id = decision.record_id
        JOIN exam_booklets booklet
          ON booklet.id = OLD.booklet_id
        JOIN portable_entity_identities exam_identity
          ON exam_identity.entity_type = 'EXAM'
         AND exam_identity.local_id = booklet.exam_id
         AND exam_identity.portable_id = locked.exam_portable_id
        WHERE decision.record_type = 'QUESTION'
          AND decision.choice = 'QUEUE_APPLY'
          AND decision.preliminary_status = 'READY_UPDATE'
          AND packet.intake_status = 'READY_FOR_REVIEW'
          AND packet.return_number = 1
          AND NOT EXISTS (
              SELECT 1
              FROM contribution_application_results outcome
              WHERE outcome.review_id = batch.review_id
                AND outcome.record_type = 'QUESTION'
                AND outcome.record_id = decision.record_id
          )
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- questions: DELETE guard
CREATE TRIGGER trg_master_capture_questions_delete
BEFORE DELETE ON questions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_regions: INSERT guard
CREATE TRIGGER trg_master_capture_question_regions_insert
BEFORE INSERT ON question_regions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_regions: UPDATE guard
CREATE TRIGGER trg_master_capture_question_regions_update
BEFORE UPDATE ON question_regions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_regions: DELETE guard
CREATE TRIGGER trg_master_capture_question_regions_delete
BEFORE DELETE ON question_regions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_images: INSERT guard
CREATE TRIGGER trg_master_capture_question_images_insert
BEFORE INSERT ON question_images
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_images: UPDATE guard
CREATE TRIGGER trg_master_capture_question_images_update
BEFORE UPDATE ON question_images
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_images: DELETE guard
CREATE TRIGGER trg_master_capture_question_images_delete
BEFORE DELETE ON question_images
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_content_parts: INSERT guard
CREATE TRIGGER trg_master_capture_question_content_parts_insert
BEFORE INSERT ON question_content_parts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_content_parts: UPDATE guard
CREATE TRIGGER trg_master_capture_question_content_parts_update
BEFORE UPDATE ON question_content_parts
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_content_parts: DELETE guard
CREATE TRIGGER trg_master_capture_question_content_parts_delete
BEFORE DELETE ON question_content_parts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_output_exclusions: INSERT guard
CREATE TRIGGER trg_master_capture_question_output_exclusions_insert
BEFORE INSERT ON question_output_exclusions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_output_exclusions: UPDATE guard
CREATE TRIGGER trg_master_capture_question_output_exclusions_update
BEFORE UPDATE ON question_output_exclusions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- question_output_exclusions: DELETE guard
CREATE TRIGGER trg_master_capture_question_output_exclusions_delete
BEFORE DELETE ON question_output_exclusions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- source_questions: INSERT guard
CREATE TRIGGER trg_master_capture_source_questions_insert
BEFORE INSERT ON source_questions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- source_questions: UPDATE guard
CREATE TRIGGER trg_master_capture_source_questions_update
BEFORE UPDATE ON source_questions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- source_questions: DELETE guard
CREATE TRIGGER trg_master_capture_source_questions_delete
BEFORE DELETE ON source_questions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_contexts: INSERT guard
CREATE TRIGGER trg_master_capture_shared_question_contexts_insert
BEFORE INSERT ON shared_question_contexts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_contexts: UPDATE guard
CREATE TRIGGER trg_master_capture_shared_question_contexts_update
BEFORE UPDATE ON shared_question_contexts
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_contexts: DELETE guard
CREATE TRIGGER trg_master_capture_shared_question_contexts_delete
BEFORE DELETE ON shared_question_contexts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_regions: INSERT guard
CREATE TRIGGER trg_master_capture_shared_question_context_regions_insert
BEFORE INSERT ON shared_question_context_regions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = NEW.shared_context_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_regions: UPDATE guard
CREATE TRIGGER trg_master_capture_shared_question_context_regions_update
BEFORE UPDATE ON shared_question_context_regions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = OLD.shared_context_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = NEW.shared_context_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_regions: DELETE guard
CREATE TRIGGER trg_master_capture_shared_question_context_regions_delete
BEFORE DELETE ON shared_question_context_regions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = OLD.shared_context_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_images: INSERT guard
CREATE TRIGGER trg_master_capture_shared_question_context_images_insert
BEFORE INSERT ON shared_question_context_images
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = NEW.shared_context_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_images: UPDATE guard
CREATE TRIGGER trg_master_capture_shared_question_context_images_update
BEFORE UPDATE ON shared_question_context_images
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = OLD.shared_context_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = NEW.shared_context_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_images: DELETE guard
CREATE TRIGGER trg_master_capture_shared_question_context_images_delete
BEFORE DELETE ON shared_question_context_images
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = OLD.shared_context_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_content_parts: INSERT guard
CREATE TRIGGER trg_master_capture_shared_question_context_content_parts_insert
BEFORE INSERT ON shared_question_context_content_parts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = NEW.shared_context_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_content_parts: UPDATE guard
CREATE TRIGGER trg_master_capture_shared_question_context_content_parts_update
BEFORE UPDATE ON shared_question_context_content_parts
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = OLD.shared_context_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = NEW.shared_context_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- shared_question_context_content_parts: DELETE guard
CREATE TRIGGER trg_master_capture_shared_question_context_content_parts_delete
BEFORE DELETE ON shared_question_context_content_parts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM shared_question_contexts context JOIN exam_booklets booklet ON booklet.id = context.booklet_id WHERE context.id = OLD.shared_context_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- answers: INSERT guard
CREATE TRIGGER trg_master_capture_answers_insert
BEFORE INSERT ON answers
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- answers: UPDATE guard
CREATE TRIGGER trg_master_capture_answers_update
BEFORE UPDATE ON answers
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = NEW.question_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- answers: DELETE guard
CREATE TRIGGER trg_master_capture_answers_delete
BEFORE DELETE ON answers
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM questions question JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE question.id = OLD.question_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- answer_regions: INSERT guard
CREATE TRIGGER trg_master_capture_answer_regions_insert
BEFORE INSERT ON answer_regions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = NEW.answer_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- answer_regions: UPDATE guard
CREATE TRIGGER trg_master_capture_answer_regions_update
BEFORE UPDATE ON answer_regions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = OLD.answer_id
    )
)
OR EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = NEW.answer_id
    )
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- answer_regions: DELETE guard
CREATE TRIGGER trg_master_capture_answer_regions_delete
BEFORE DELETE ON answer_regions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id IN (
        SELECT booklet.exam_id FROM answers answer JOIN questions question ON question.id = answer.question_id JOIN exam_booklets booklet ON booklet.id = question.booklet_id WHERE answer.id = OLD.answer_id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam capture is read-only on the master');
END;

-- A reconciled return closes exactly one ready, first return.
-- Other received packages, including held submissions, require explicit
-- coordinator resolution before any new assignment can be issued.
CREATE TRIGGER trg_master_return_closure_requires_reconciled_application
BEFORE INSERT ON exam_work_assignment_closures
WHEN NEW.closure_kind = 'RECONCILED_RETURN'
AND (
    EXISTS (
        SELECT 1
        FROM contribution_intake_packages packages
        WHERE packages.assignment_id = NEW.assignment_id
          AND (
              packages.package_id <> NEW.return_package_id
              OR packages.intake_status <> 'READY_FOR_REVIEW'
              OR packages.return_number <> 1
          )
    )
    OR NOT EXISTS (
        SELECT 1
        FROM contribution_application_batches batch
        JOIN contribution_review_batches review
          ON review.review_id = batch.review_id
         AND review.package_id = batch.package_id
        WHERE batch.package_id = NEW.return_package_id
          AND NOT EXISTS (
              SELECT 1
              FROM contribution_review_decisions decision
              WHERE decision.review_id = batch.review_id
                AND (
                    decision.choice = 'HOLD'
                    OR NOT EXISTS (
                        SELECT 1
                        FROM contribution_application_results result
                        WHERE result.review_id = decision.review_id
                          AND result.record_type = decision.record_type
                          AND result.record_id = decision.record_id
                    )
                )
          )
    )
)
BEGIN
    SELECT RAISE(ABORT,
        'Assignment has unresolved returns or application results');
END;

UPDATE schema_version
SET version = 40
WHERE version = 39;
