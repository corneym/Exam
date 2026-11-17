-- Schema version 30: contributor assignment structural write barriers.
-- These SQL definitions match the original v30 Java-generated triggers.
-- All triggers and the version update run in one migration transaction.

-- subjects
CREATE TRIGGER trg_contributor_structure_subjects_insert
BEFORE INSERT ON subjects
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_subjects_update
BEFORE UPDATE ON subjects
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_subjects_delete
BEFORE DELETE ON subjects
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- syllabus_versions
CREATE TRIGGER trg_contributor_structure_syllabus_versions_insert
BEFORE INSERT ON syllabus_versions
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_syllabus_versions_update
BEFORE UPDATE ON syllabus_versions
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_syllabus_versions_delete
BEFORE DELETE ON syllabus_versions
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- curriculum_nodes
CREATE TRIGGER trg_contributor_structure_curriculum_nodes_insert
BEFORE INSERT ON curriculum_nodes
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_curriculum_nodes_update
BEFORE UPDATE ON curriculum_nodes
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_curriculum_nodes_delete
BEFORE DELETE ON curriculum_nodes
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- curriculum_mappings
CREATE TRIGGER trg_contributor_structure_curriculum_mappings_insert
BEFORE INSERT ON curriculum_mappings
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_curriculum_mappings_update
BEFORE UPDATE ON curriculum_mappings
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_curriculum_mappings_delete
BEFORE DELETE ON curriculum_mappings
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- curriculum_mapping_reviews
CREATE TRIGGER trg_contributor_structure_curriculum_mapping_reviews_insert
BEFORE INSERT ON curriculum_mapping_reviews
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_curriculum_mapping_reviews_update
BEFORE UPDATE ON curriculum_mapping_reviews
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_curriculum_mapping_reviews_delete
BEFORE DELETE ON curriculum_mapping_reviews
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- exam_providers
CREATE TRIGGER trg_contributor_structure_exam_providers_insert
BEFORE INSERT ON exam_providers
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_exam_providers_update
BEFORE UPDATE ON exam_providers
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_exam_providers_delete
BEFORE DELETE ON exam_providers
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- source_documents
CREATE TRIGGER trg_contributor_structure_source_documents_insert
BEFORE INSERT ON source_documents
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_source_documents_update
BEFORE UPDATE ON source_documents
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_source_documents_delete
BEFORE DELETE ON source_documents
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- exams
CREATE TRIGGER trg_contributor_structure_exams_insert
BEFORE INSERT ON exams
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_exams_update
BEFORE UPDATE ON exams
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_exams_delete
BEFORE DELETE ON exams
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- exam_booklets
CREATE TRIGGER trg_contributor_structure_exam_booklets_insert
BEFORE INSERT ON exam_booklets
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_exam_booklets_update
BEFORE UPDATE ON exam_booklets
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_exam_booklets_delete
BEFORE DELETE ON exam_booklets
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

-- answer_files
CREATE TRIGGER trg_contributor_structure_answer_files_insert
BEFORE INSERT ON answer_files
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_answer_files_update
BEFORE UPDATE ON answer_files
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

CREATE TRIGGER trg_contributor_structure_answer_files_delete
BEFORE DELETE ON answer_files
WHEN ( SELECT assignment_id FROM contributor_assignment_selection
       WHERE singleton_id = 1 ) IS NOT NULL
BEGIN SELECT RAISE( ABORT, 'Coordinator-owned structure is read-only in assignment mode' ); END;

UPDATE schema_version
SET version = 30
WHERE version = 29;
