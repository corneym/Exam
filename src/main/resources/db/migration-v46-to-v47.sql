-- Version 47: retain automatic Booklet applicability-review invalidation
-- during an authorised contribution application.
--
-- The normal locked-Exam guard remains active. Its only exception is
-- a matching transaction-scoped Question/Answer or Source/Shared Context
-- write permit. These permits are already checked against reviewed
-- QUEUE_APPLY decisions and released before assignment closure.

DROP TRIGGER trg_master_exam_structure_booklet_applicability_reviews_delete;

CREATE TRIGGER trg_master_exam_structure_booklet_applicability_reviews_delete
BEFORE DELETE ON booklet_applicability_reviews
WHEN EXISTS (
    SELECT 1
    FROM exam_booklets booklet
    JOIN portable_entity_identities exam_identity
      ON exam_identity.entity_type = 'EXAM'
     AND exam_identity.local_id = booklet.exam_id
     AND exam_identity.retired = 0
    JOIN exam_work_assignment_exam_locks locked
      ON locked.exam_portable_id = exam_identity.portable_id
    WHERE booklet.id = OLD.booklet_id
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_write_permits permit
    JOIN exam_booklets booklet
      ON booklet.id = OLD.booklet_id
     AND booklet.exam_id = permit.exam_local_id
    WHERE permit.singleton_id = 1
      AND permit.record_type IN ('QUESTION', 'ANSWER')
      AND (
          -- New Question/Answer inserts have not yet acquired
          -- their master portable identities.
          permit.operation = 'INSERT'
          OR (
              permit.operation = 'UPDATE'
              AND (
                  EXISTS (
                      SELECT 1
                      FROM portable_entity_identities identity
                      JOIN questions question
                        ON question.id = identity.local_id
                      WHERE identity.entity_type = 'QUESTION'
                        AND identity.portable_id = permit.record_id
                        AND identity.retired = 0
                        AND permit.record_type = 'QUESTION'
                        AND question.booklet_id = booklet.id
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM portable_entity_identities identity
                      JOIN answers answer
                        ON answer.id = identity.local_id
                      JOIN questions question
                        ON question.id = answer.question_id
                      WHERE identity.entity_type = 'ANSWER'
                        AND identity.portable_id = permit.record_id
                        AND identity.retired = 0
                        AND permit.record_type = 'ANSWER'
                        AND question.booklet_id = booklet.id
                  )
              )
          )
      )
)
AND NOT EXISTS (
    SELECT 1
    FROM master_contribution_graph_permits permit
    JOIN exam_booklets booklet
      ON booklet.id = OLD.booklet_id
     AND booklet.exam_id = permit.exam_local_id
    WHERE permit.singleton_id = 1
      AND permit.record_type IN (
          'SOURCE_QUESTION', 'SHARED_QUESTION_CONTEXT'
      )
      AND (
          -- Parent insertion can invalidate the Booklet review
          -- before the new local owner ID has been bound.
          (
              permit.operation = 'INSERT'
              AND permit.owner_local_id IS NULL
          )
          OR (
              permit.owner_local_id IS NOT NULL
              AND (
                  EXISTS (
                      SELECT 1
                      FROM source_questions source
                      WHERE permit.record_type = 'SOURCE_QUESTION'
                        AND source.id = permit.owner_local_id
                        AND source.booklet_id = booklet.id
                  )
                  OR EXISTS (
                      SELECT 1
                      FROM shared_question_contexts context
                      WHERE permit.record_type =
                            'SHARED_QUESTION_CONTEXT'
                        AND context.id = permit.owner_local_id
                        AND context.booklet_id = booklet.id
                  )
              )
          )
      )
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Assigned Exam applicability review is read-only'
    );
END;

UPDATE schema_version
SET version = 47
WHERE version = 46;
