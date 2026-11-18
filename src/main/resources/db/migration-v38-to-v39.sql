-- Schema v39: master Exam structural write barriers for active offline assignments.
-- Every trigger checks the authoritative exam_work_assignment_exam_locks ledger.
-- This slice protects Exam structure and its source documents; capture
-- Question/Answer content requires the authorised reconciliation boundary.
-- Do not edit definitions in v38. The v39 migration is a separate resource.

-- exams_update: UPDATE ON exams
CREATE TRIGGER trg_master_exam_structure_exams_update
BEFORE UPDATE ON exams
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = OLD.id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- exams_delete: DELETE ON exams
CREATE TRIGGER trg_master_exam_structure_exams_delete
BEFORE DELETE ON exams
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = OLD.id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- subjects_update: UPDATE ON subjects
CREATE TRIGGER trg_master_exam_structure_subjects_update
BEFORE UPDATE ON subjects
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.subject_id = OLD.id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- subjects_delete: DELETE ON subjects
CREATE TRIGGER trg_master_exam_structure_subjects_delete
BEFORE DELETE ON subjects
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.subject_id = OLD.id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- exam_providers_update: UPDATE ON exam_providers
CREATE TRIGGER trg_master_exam_structure_exam_providers_update
BEFORE UPDATE ON exam_providers
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.provider_id = OLD.id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- exam_providers_delete: DELETE ON exam_providers
CREATE TRIGGER trg_master_exam_structure_exam_providers_delete
BEFORE DELETE ON exam_providers
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.provider_id = OLD.id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- source_documents_update: UPDATE ON source_documents
CREATE TRIGGER trg_master_exam_structure_source_documents_update
BEFORE UPDATE ON source_documents
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (
        SELECT 1 FROM exam_booklets booklet
        WHERE booklet.exam_id = identity.local_id
          AND booklet.source_document_id = OLD.id
    ) OR EXISTS (
        SELECT 1 FROM answer_files answer_file
        WHERE answer_file.exam_id = identity.local_id
          AND answer_file.source_document_id = OLD.id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- source_documents_delete: DELETE ON source_documents
CREATE TRIGGER trg_master_exam_structure_source_documents_delete
BEFORE DELETE ON source_documents
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (
        SELECT 1 FROM exam_booklets booklet
        WHERE booklet.exam_id = identity.local_id
          AND booklet.source_document_id = OLD.id
    ) OR EXISTS (
        SELECT 1 FROM answer_files answer_file
        WHERE answer_file.exam_id = identity.local_id
          AND answer_file.source_document_id = OLD.id
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- exam_booklets_insert: INSERT ON exam_booklets
CREATE TRIGGER trg_master_exam_structure_exam_booklets_insert
BEFORE INSERT ON exam_booklets
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = NEW.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- exam_booklets_update: UPDATE ON exam_booklets
CREATE TRIGGER trg_master_exam_structure_exam_booklets_update
BEFORE UPDATE ON exam_booklets
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = OLD.exam_id
)) OR
(EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = NEW.exam_id
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- exam_booklets_delete: DELETE ON exam_booklets
CREATE TRIGGER trg_master_exam_structure_exam_booklets_delete
BEFORE DELETE ON exam_booklets
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = OLD.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- answer_files_insert: INSERT ON answer_files
CREATE TRIGGER trg_master_exam_structure_answer_files_insert
BEFORE INSERT ON answer_files
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = NEW.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- answer_files_update: UPDATE ON answer_files
CREATE TRIGGER trg_master_exam_structure_answer_files_update
BEFORE UPDATE ON answer_files
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = OLD.exam_id
)) OR
(EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = NEW.exam_id
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- answer_files_delete: DELETE ON answer_files
CREATE TRIGGER trg_master_exam_structure_answer_files_delete
BEFORE DELETE ON answer_files
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE identity.local_id = OLD.exam_id
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- syllabus_versions_insert: INSERT ON syllabus_versions
CREATE TRIGGER trg_master_exam_structure_syllabus_versions_insert
BEFORE INSERT ON syllabus_versions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.subject_id = NEW.subject_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- syllabus_versions_update: UPDATE ON syllabus_versions
CREATE TRIGGER trg_master_exam_structure_syllabus_versions_update
BEFORE UPDATE ON syllabus_versions
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.subject_id = OLD.subject_id)
)) OR
(EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.subject_id = NEW.subject_id)
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- syllabus_versions_delete: DELETE ON syllabus_versions
CREATE TRIGGER trg_master_exam_structure_syllabus_versions_delete
BEFORE DELETE ON syllabus_versions
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam WHERE exam.id = identity.local_id AND exam.subject_id = OLD.subject_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- curriculum_nodes_insert: INSERT ON curriculum_nodes
CREATE TRIGGER trg_master_exam_structure_curriculum_nodes_insert
BEFORE INSERT ON curriculum_nodes
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam JOIN syllabus_versions syllabus ON syllabus.subject_id = exam.subject_id WHERE exam.id = identity.local_id AND syllabus.id = NEW.syllabus_version_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- curriculum_nodes_update: UPDATE ON curriculum_nodes
CREATE TRIGGER trg_master_exam_structure_curriculum_nodes_update
BEFORE UPDATE ON curriculum_nodes
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam JOIN syllabus_versions syllabus ON syllabus.subject_id = exam.subject_id WHERE exam.id = identity.local_id AND syllabus.id = OLD.syllabus_version_id)
)) OR
(EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam JOIN syllabus_versions syllabus ON syllabus.subject_id = exam.subject_id WHERE exam.id = identity.local_id AND syllabus.id = NEW.syllabus_version_id)
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- curriculum_nodes_delete: DELETE ON curriculum_nodes
CREATE TRIGGER trg_master_exam_structure_curriculum_nodes_delete
BEFORE DELETE ON curriculum_nodes
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exams exam JOIN syllabus_versions syllabus ON syllabus.subject_id = exam.subject_id WHERE exam.id = identity.local_id AND syllabus.id = OLD.syllabus_version_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- booklet_applicability_reviews_insert: INSERT ON booklet_applicability_reviews
CREATE TRIGGER trg_master_exam_structure_booklet_applicability_reviews_insert
BEFORE INSERT ON booklet_applicability_reviews
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id AND booklet.exam_id = identity.local_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- booklet_applicability_reviews_update: UPDATE ON booklet_applicability_reviews
CREATE TRIGGER trg_master_exam_structure_booklet_applicability_reviews_update
BEFORE UPDATE ON booklet_applicability_reviews
WHEN (EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id AND booklet.exam_id = identity.local_id)
)) OR
(EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exam_booklets booklet WHERE booklet.id = NEW.booklet_id AND booklet.exam_id = identity.local_id)
))
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

-- booklet_applicability_reviews_delete: DELETE ON booklet_applicability_reviews
CREATE TRIGGER trg_master_exam_structure_booklet_applicability_reviews_delete
BEFORE DELETE ON booklet_applicability_reviews
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_exam_locks locked
    JOIN portable_entity_identities identity
      ON identity.portable_id = locked.exam_portable_id
     AND identity.entity_type = 'EXAM'
    WHERE EXISTS (SELECT 1 FROM exam_booklets booklet WHERE booklet.id = OLD.booklet_id AND booklet.exam_id = identity.local_id)
)
BEGIN
    SELECT RAISE(ABORT, 'Assigned Exam is read-only on the master');
END;

UPDATE schema_version SET version = 39 WHERE version = 38;
