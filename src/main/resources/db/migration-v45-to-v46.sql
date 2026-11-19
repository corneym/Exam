-- v46: Transaction-scoped Source Question and Shared Context writes.
-- Question-image replacement uses the existing reviewed Question permits.
-- Ordinary edits to an assigned master Exam remain prohibited.

CREATE TABLE master_contribution_graph_permits (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    review_id TEXT NOT NULL,
    record_type TEXT NOT NULL
        CHECK (record_type IN ('SOURCE_QUESTION', 'SHARED_QUESTION_CONTEXT')),
    record_id TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('INSERT', 'UPDATE')),
    exam_local_id INTEGER NOT NULL REFERENCES exams(id),
    owner_local_id INTEGER CHECK (owner_local_id > 0),

    FOREIGN KEY (review_id, record_type, record_id)
        REFERENCES contribution_review_decisions
            (review_id, record_type, record_id),
    FOREIGN KEY (review_id)
        REFERENCES contribution_application_batches(review_id)
);

CREATE TRIGGER trg_master_graph_permit_validate
BEFORE INSERT ON master_contribution_graph_permits
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
          (
              NEW.operation = 'INSERT'
              AND decision.preliminary_status = 'READY_NEW'
              AND NEW.owner_local_id IS NULL
          )
          OR (
              NEW.operation = 'UPDATE'
              AND decision.preliminary_status = 'READY_UPDATE'
              AND NEW.owner_local_id IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM portable_entity_identities owner_identity
                  WHERE owner_identity.entity_type = NEW.record_type
                    AND owner_identity.portable_id = NEW.record_id
                    AND owner_identity.local_id = NEW.owner_local_id
                    AND owner_identity.retired = 0
              )
          )
      )
      AND NOT EXISTS (
          SELECT 1
          FROM contribution_application_results done
          WHERE done.review_id = decision.review_id
            AND done.record_type = decision.record_type
            AND done.record_id = decision.record_id
      )
)
BEGIN
    SELECT RAISE(ABORT, 'No approved master graph write permit');
END;

-- A newly inserted parent acquires its SQLite identity before children
-- can be written. Binding is allowed exactly once.
CREATE TRIGGER trg_master_graph_permit_bind_owner
BEFORE UPDATE ON master_contribution_graph_permits
WHEN NOT (
    OLD.owner_local_id IS NULL
    AND NEW.owner_local_id IS NOT NULL
    AND OLD.operation = 'INSERT'
    AND NEW.singleton_id IS OLD.singleton_id
    AND NEW.review_id IS OLD.review_id
    AND NEW.record_type IS OLD.record_type
    AND NEW.record_id IS OLD.record_id
    AND NEW.operation IS OLD.operation
    AND NEW.exam_local_id IS OLD.exam_local_id
    AND (
        (
            NEW.record_type = 'SOURCE_QUESTION'
            AND EXISTS (
                SELECT 1
                FROM source_questions source
                JOIN exam_booklets booklet
                  ON booklet.id = source.booklet_id
                WHERE source.id = NEW.owner_local_id
                  AND booklet.exam_id = NEW.exam_local_id
            )
        )
        OR (
            NEW.record_type = 'SHARED_QUESTION_CONTEXT'
            AND EXISTS (
                SELECT 1
                FROM shared_question_contexts context
                JOIN exam_booklets booklet
                  ON booklet.id = context.booklet_id
                WHERE context.id = NEW.owner_local_id
                  AND booklet.exam_id = NEW.exam_local_id
            )
        )
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Invalid master graph owner binding');
END;

-- SOURCE QUESTION: reviewed INSERT.
DROP TRIGGER trg_master_capture_source_questions_insert;

CREATE TRIGGER trg_master_capture_source_questions_insert
BEFORE INSERT ON source_questions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = identity.local_id
    WHERE booklet.id = NEW.booklet_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN exam_booklets booklet
      ON booklet.id = NEW.booklet_id
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'SOURCE_QUESTION'
      AND permit.operation = 'INSERT'
      AND permit.owner_local_id IS NULL
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam Source Question is locked');
END;

-- SOURCE QUESTION: same-Booklet reviewed UPDATE only.
DROP TRIGGER trg_master_capture_source_questions_update;

CREATE TRIGGER trg_master_capture_source_questions_update
BEFORE UPDATE ON source_questions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = identity.local_id
    WHERE booklet.id = OLD.booklet_id
       OR booklet.id = NEW.booklet_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN portable_entity_identities source_identity
      ON source_identity.entity_type = 'SOURCE_QUESTION'
     AND source_identity.local_id = OLD.id
     AND source_identity.portable_id = permit.record_id
     AND source_identity.retired = 0
    JOIN exam_booklets booklet
      ON booklet.id = OLD.booklet_id
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'SOURCE_QUESTION'
      AND permit.operation = 'UPDATE'
      AND permit.owner_local_id = OLD.id
      AND permit.exam_local_id = booklet.exam_id
      AND NEW.booklet_id IS OLD.booklet_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam Source Question is locked');
END;

-- SHARED CONTEXT: reviewed INSERT.
DROP TRIGGER trg_master_capture_shared_question_contexts_insert;

CREATE TRIGGER trg_master_capture_shared_question_contexts_insert
BEFORE INSERT ON shared_question_contexts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = identity.local_id
    WHERE booklet.id = NEW.booklet_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN exam_booklets booklet
      ON booklet.id = NEW.booklet_id
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.operation = 'INSERT'
      AND permit.owner_local_id IS NULL
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam Shared Context is locked');
END;

-- SHARED CONTEXT: reviewed UPDATE, without reparenting.
DROP TRIGGER trg_master_capture_shared_question_contexts_update;

CREATE TRIGGER trg_master_capture_shared_question_contexts_update
BEFORE UPDATE ON shared_question_contexts
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    JOIN exam_booklets booklet
      ON booklet.exam_id = identity.local_id
    WHERE booklet.id = OLD.booklet_id
       OR booklet.id = NEW.booklet_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN portable_entity_identities context_identity
      ON context_identity.entity_type = 'SHARED_QUESTION_CONTEXT'
     AND context_identity.local_id = OLD.id
     AND context_identity.portable_id = permit.record_id
     AND context_identity.retired = 0
    JOIN exam_booklets booklet
      ON booklet.id = OLD.booklet_id
    WHERE permit.singleton_id = 1
      AND permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.operation = 'UPDATE'
      AND permit.owner_local_id = OLD.id
      AND permit.exam_local_id = booklet.exam_id
      AND NEW.booklet_id IS OLD.booklet_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam Shared Context is locked');
END;

-- Each Shared Context child may be inserted or deleted only while
-- its reviewed parent permit is bound to that exact local owner.

DROP TRIGGER trg_master_capture_shared_question_context_regions_insert;

CREATE TRIGGER trg_master_capture_shared_question_context_regions_insert
BEFORE INSERT ON shared_question_context_regions
WHEN EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet ON booklet.id = context.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE context.id = NEW.shared_context_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN shared_question_contexts context
      ON context.id = permit.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = context.booklet_id
    WHERE permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.owner_local_id = NEW.shared_context_id
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Shared Context regions are locked');
END;

DROP TRIGGER trg_master_capture_shared_question_context_regions_delete;

CREATE TRIGGER trg_master_capture_shared_question_context_regions_delete
BEFORE DELETE ON shared_question_context_regions
WHEN EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet ON booklet.id = context.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE context.id = OLD.shared_context_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN shared_question_contexts context
      ON context.id = permit.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = context.booklet_id
    WHERE permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.owner_local_id = OLD.shared_context_id
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Shared Context regions are locked');
END;

DROP TRIGGER trg_master_capture_shared_question_context_images_insert;

CREATE TRIGGER trg_master_capture_shared_question_context_images_insert
BEFORE INSERT ON shared_question_context_images
WHEN EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet ON booklet.id = context.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE context.id = NEW.shared_context_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN shared_question_contexts context
      ON context.id = permit.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = context.booklet_id
    WHERE permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.owner_local_id = NEW.shared_context_id
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Shared Context images are locked');
END;

DROP TRIGGER trg_master_capture_shared_question_context_images_delete;

CREATE TRIGGER trg_master_capture_shared_question_context_images_delete
BEFORE DELETE ON shared_question_context_images
WHEN EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet ON booklet.id = context.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE context.id = OLD.shared_context_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN shared_question_contexts context
      ON context.id = permit.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = context.booklet_id
    WHERE permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.owner_local_id = OLD.shared_context_id
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Shared Context images are locked');
END;

DROP TRIGGER trg_master_capture_shared_question_context_content_parts_insert;

CREATE TRIGGER trg_master_capture_shared_question_context_content_parts_insert
BEFORE INSERT ON shared_question_context_content_parts
WHEN EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet ON booklet.id = context.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE context.id = NEW.shared_context_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN shared_question_contexts context
      ON context.id = permit.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = context.booklet_id
    WHERE permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.owner_local_id = NEW.shared_context_id
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Shared Context parts are locked');
END;

DROP TRIGGER trg_master_capture_shared_question_context_content_parts_delete;

CREATE TRIGGER trg_master_capture_shared_question_context_content_parts_delete
BEFORE DELETE ON shared_question_context_content_parts
WHEN EXISTS (
    SELECT 1
    FROM shared_question_contexts context
    JOIN exam_booklets booklet ON booklet.id = context.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE context.id = OLD.shared_context_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN shared_question_contexts context
      ON context.id = permit.owner_local_id
    JOIN exam_booklets booklet
      ON booklet.id = context.booklet_id
    WHERE permit.record_type = 'SHARED_QUESTION_CONTEXT'
      AND permit.owner_local_id = OLD.shared_context_id
      AND permit.exam_local_id = booklet.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Shared Context parts are locked');
END;

-- Existing Question PNG replacement requires an image-specific reviewed
-- decision AND both existing permits for its exact owning Question.
DROP TRIGGER trg_master_capture_question_images_update;

CREATE TRIGGER trg_master_capture_question_images_update
BEFORE UPDATE ON question_images
WHEN EXISTS (
    SELECT 1
    FROM questions question
    JOIN exam_booklets booklet
      ON booklet.id = question.booklet_id
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE question.id = OLD.question_id
       OR question.id = NEW.question_id
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
    JOIN portable_entity_identities image_identity
      ON image_identity.entity_type = 'QUESTION_IMAGE'
     AND image_identity.local_id = OLD.id
     AND image_identity.retired = 0
    JOIN contribution_review_decisions decision
      ON decision.review_id = scalar.review_id
     AND decision.record_type = 'QUESTION_IMAGE'
     AND decision.record_id = image_identity.portable_id
    WHERE region.singleton_id = 1
      AND region.record_type = 'QUESTION'
      AND scalar.record_type = 'QUESTION'
      AND region.owner_local_id = OLD.question_id
      AND NEW.question_id IS OLD.question_id
      AND NEW.id IS OLD.id
      AND decision.choice = 'QUEUE_APPLY'
      AND decision.preliminary_status = 'READY_UPDATE'
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Question image replacement is locked');
END;

UPDATE schema_version
SET version = 46
WHERE version = 45;
