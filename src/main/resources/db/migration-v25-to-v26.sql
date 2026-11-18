-- Schema v26: material-change revision triggers.
-- Frozen definitions generated from the original Java v26 trigger templates.
-- Revision UUID values are produced at mutation time; existing IDs are preserved.

-- SUBJECT
CREATE TRIGGER app_revision_subjects_update
AFTER UPDATE ON subjects
WHEN OLD.subject_name IS NOT NEW.subject_name
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SUBJECT'
      AND local_id = NEW.id;
END;

-- SYLLABUS_VERSION
CREATE TRIGGER app_revision_syllabus_versions_update
AFTER UPDATE ON syllabus_versions
WHEN OLD.subject_id IS NOT NEW.subject_id OR OLD.syllabus_name IS NOT NEW.syllabus_name OR OLD.is_current IS NOT NEW.is_current OR OLD.curriculum_status IS NOT NEW.curriculum_status OR OLD.curriculum_finalised_at IS NOT NEW.curriculum_finalised_at OR OLD.source_pdf_path IS NOT NEW.source_pdf_path
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SYLLABUS_VERSION'
      AND local_id = NEW.id;
END;

-- CURRICULUM_NODE
CREATE TRIGGER app_revision_curriculum_nodes_update
AFTER UPDATE ON curriculum_nodes
WHEN OLD.syllabus_version_id IS NOT NEW.syllabus_version_id OR OLD.parent_id IS NOT NEW.parent_id OR OLD.curriculum_code IS NOT NEW.curriculum_code OR OLD.curriculum_name IS NOT NEW.curriculum_name OR OLD.curriculum_level IS NOT NEW.curriculum_level OR OLD.display_order IS NOT NEW.display_order OR OLD.source_page_number IS NOT NEW.source_page_number
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'CURRICULUM_NODE'
      AND local_id = NEW.id;
END;

-- EXAM_PROVIDER
CREATE TRIGGER app_revision_exam_providers_update
AFTER UPDATE ON exam_providers
WHEN OLD.provider_name IS NOT NEW.provider_name
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM_PROVIDER'
      AND local_id = NEW.id;
END;

-- SOURCE_DOCUMENT
CREATE TRIGGER app_revision_source_documents_update
AFTER UPDATE ON source_documents
WHEN OLD.relative_path IS NOT NEW.relative_path OR OLD.content_sha256 IS NOT NEW.content_sha256
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SOURCE_DOCUMENT'
      AND local_id = NEW.id;
END;

-- EXAM
CREATE TRIGGER app_revision_exams_update
AFTER UPDATE ON exams
WHEN OLD.subject_id IS NOT NEW.subject_id OR OLD.provider_id IS NOT NEW.provider_id OR OLD.exam_year IS NOT NEW.exam_year OR OLD.exam_name IS NOT NEW.exam_name OR OLD.capture_state IS NOT NEW.capture_state OR OLD.expected_question_booklet_count IS NOT NEW.expected_question_booklet_count OR OLD.expected_answer_file_count IS NOT NEW.expected_answer_file_count
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM'
      AND local_id = NEW.id;
END;

-- EXAM_BOOKLET
CREATE TRIGGER app_revision_exam_booklets_update
AFTER UPDATE ON exam_booklets
WHEN OLD.exam_id IS NOT NEW.exam_id OR OLD.source_document_id IS NOT NEW.source_document_id OR OLD.booklet_name IS NOT NEW.booklet_name OR OLD.question_format IS NOT NEW.question_format OR OLD.answer_file_id IS NOT NEW.answer_file_id OR OLD.pending_mcq_shared_context_id IS NOT NEW.pending_mcq_shared_context_id OR OLD.expected_question_count IS NOT NEW.expected_question_count OR OLD.legacy_paper_code IS NOT NEW.legacy_paper_code
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM_BOOKLET'
      AND local_id = NEW.id;
END;

-- QUESTION
CREATE TRIGGER app_revision_questions_update
AFTER UPDATE ON questions
WHEN OLD.booklet_id IS NOT NEW.booklet_id OR OLD.classification_node_id IS NOT NEW.classification_node_id OR OLD.question_code IS NOT NEW.question_code OR OLD.question_text IS NOT NEW.question_text OR OLD.marks IS NOT NEW.marks OR OLD.shared_context_capture_required IS NOT NEW.shared_context_capture_required OR OLD.source_question_id IS NOT NEW.source_question_id OR OLD.shared_context_id IS NOT NEW.shared_context_id OR OLD.response_type IS NOT NEW.response_type OR OLD.source_capture_required IS NOT NEW.source_capture_required
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.id;
END;

-- ANSWER_FILE
CREATE TRIGGER app_revision_answer_files_update
AFTER UPDATE ON answer_files
WHEN OLD.exam_id IS NOT NEW.exam_id OR OLD.source_document_id IS NOT NEW.source_document_id OR OLD.answer_file_name IS NOT NEW.answer_file_name OR OLD.contains_answer_explanations IS NOT NEW.contains_answer_explanations
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'ANSWER_FILE'
      AND local_id = NEW.id;
END;

-- ANSWER
CREATE TRIGGER app_revision_answers_update
AFTER UPDATE ON answers
WHEN OLD.question_id IS NOT NEW.question_id OR OLD.answer_text IS NOT NEW.answer_text
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'ANSWER'
      AND local_id = NEW.id;
END;

-- SOURCE_QUESTION
CREATE TRIGGER app_revision_source_questions_update
AFTER UPDATE ON source_questions
WHEN OLD.booklet_id IS NOT NEW.booklet_id OR OLD.source_question_code IS NOT NEW.source_question_code OR OLD.shared_context_status IS NOT NEW.shared_context_status
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SOURCE_QUESTION'
      AND local_id = NEW.id;
END;

-- SHARED_QUESTION_CONTEXT
CREATE TRIGGER app_revision_shared_question_contexts_update
AFTER UPDATE ON shared_question_contexts
WHEN OLD.booklet_id IS NOT NEW.booklet_id OR OLD.context_label IS NOT NEW.context_label
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.id;
END;

-- CURRICULUM_MAPPING
CREATE TRIGGER app_revision_curriculum_mappings_update
AFTER UPDATE ON curriculum_mappings
WHEN OLD.source_node_id IS NOT NEW.source_node_id OR OLD.target_node_id IS NOT NEW.target_node_id OR OLD.mapping_status IS NOT NEW.mapping_status
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'CURRICULUM_MAPPING'
      AND local_id = NEW.id;
END;

-- QUESTION_IMAGE
CREATE TRIGGER app_revision_question_images_update
AFTER UPDATE ON question_images
WHEN OLD.question_id IS NOT NEW.question_id OR OLD.image_png IS NOT NEW.image_png
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION_IMAGE'
      AND local_id = NEW.id;
END;

-- question_regions -> QUESTION
CREATE TRIGGER app_revision_question_regions_question_insert
AFTER INSERT ON question_regions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id;
END;

CREATE TRIGGER app_revision_question_regions_question_update
AFTER UPDATE ON question_regions
WHEN OLD.question_id IS NOT NEW.question_id OR OLD.region_order IS NOT NEW.region_order OR OLD.booklet_id IS NOT NEW.booklet_id OR OLD.page_number IS NOT NEW.page_number OR OLD.x IS NOT NEW.x OR OLD.y IS NOT NEW.y OR OLD.width IS NOT NEW.width OR OLD.height IS NOT NEW.height
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id
      AND NEW.question_id IS NOT OLD.question_id;
END;

CREATE TRIGGER app_revision_question_regions_question_delete
AFTER DELETE ON question_regions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;
END;

-- question_content_parts -> QUESTION
CREATE TRIGGER app_revision_question_content_parts_question_insert
AFTER INSERT ON question_content_parts
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id;
END;

CREATE TRIGGER app_revision_question_content_parts_question_update
AFTER UPDATE ON question_content_parts
WHEN OLD.question_id IS NOT NEW.question_id OR OLD.content_order IS NOT NEW.content_order OR OLD.content_type IS NOT NEW.content_type OR OLD.region_order IS NOT NEW.region_order OR OLD.image_id IS NOT NEW.image_id
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id
      AND NEW.question_id IS NOT OLD.question_id;
END;

CREATE TRIGGER app_revision_question_content_parts_question_delete
AFTER DELETE ON question_content_parts
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;
END;

-- question_images -> QUESTION
CREATE TRIGGER app_revision_question_images_question_insert
AFTER INSERT ON question_images
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id;
END;

CREATE TRIGGER app_revision_question_images_question_update
AFTER UPDATE ON question_images
WHEN OLD.id IS NOT NEW.id OR OLD.question_id IS NOT NEW.question_id OR OLD.image_png IS NOT NEW.image_png
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id
      AND NEW.question_id IS NOT OLD.question_id;
END;

CREATE TRIGGER app_revision_question_images_question_delete
AFTER DELETE ON question_images
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;
END;

-- question_output_exclusions -> QUESTION
CREATE TRIGGER app_revision_question_output_exclusions_question_insert
AFTER INSERT ON question_output_exclusions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id;
END;

CREATE TRIGGER app_revision_question_output_exclusions_question_update
AFTER UPDATE ON question_output_exclusions
WHEN OLD.question_id IS NOT NEW.question_id OR OLD.current_curriculum_node_id IS NOT NEW.current_curriculum_node_id
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = NEW.question_id
      AND NEW.question_id IS NOT OLD.question_id;
END;

CREATE TRIGGER app_revision_question_output_exclusions_question_delete
AFTER DELETE ON question_output_exclusions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'QUESTION'
      AND local_id = OLD.question_id;
END;

-- answer_regions -> ANSWER
CREATE TRIGGER app_revision_answer_regions_answer_insert
AFTER INSERT ON answer_regions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'ANSWER'
      AND local_id = NEW.answer_id;
END;

CREATE TRIGGER app_revision_answer_regions_answer_update
AFTER UPDATE ON answer_regions
WHEN OLD.answer_id IS NOT NEW.answer_id OR OLD.region_order IS NOT NEW.region_order OR OLD.answer_file_id IS NOT NEW.answer_file_id OR OLD.page_number IS NOT NEW.page_number OR OLD.x IS NOT NEW.x OR OLD.y IS NOT NEW.y OR OLD.width IS NOT NEW.width OR OLD.height IS NOT NEW.height
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'ANSWER'
      AND local_id = OLD.answer_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'ANSWER'
      AND local_id = NEW.answer_id
      AND NEW.answer_id IS NOT OLD.answer_id;
END;

CREATE TRIGGER app_revision_answer_regions_answer_delete
AFTER DELETE ON answer_regions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'ANSWER'
      AND local_id = OLD.answer_id;
END;

-- shared_question_context_regions -> SHARED_QUESTION_CONTEXT
CREATE TRIGGER app_revision_shared_question_context_regions_shared_question_context_insert
AFTER INSERT ON shared_question_context_regions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.shared_context_id;
END;

CREATE TRIGGER app_revision_shared_question_context_regions_shared_question_context_update
AFTER UPDATE ON shared_question_context_regions
WHEN OLD.shared_context_id IS NOT NEW.shared_context_id OR OLD.region_order IS NOT NEW.region_order OR OLD.page_number IS NOT NEW.page_number OR OLD.x IS NOT NEW.x OR OLD.y IS NOT NEW.y OR OLD.width IS NOT NEW.width OR OLD.height IS NOT NEW.height
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = OLD.shared_context_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.shared_context_id
      AND NEW.shared_context_id IS NOT OLD.shared_context_id;
END;

CREATE TRIGGER app_revision_shared_question_context_regions_shared_question_context_delete
AFTER DELETE ON shared_question_context_regions
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = OLD.shared_context_id;
END;

-- shared_question_context_images -> SHARED_QUESTION_CONTEXT
CREATE TRIGGER app_revision_shared_question_context_images_shared_question_context_insert
AFTER INSERT ON shared_question_context_images
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.shared_context_id;
END;

CREATE TRIGGER app_revision_shared_question_context_images_shared_question_context_update
AFTER UPDATE ON shared_question_context_images
WHEN OLD.shared_context_id IS NOT NEW.shared_context_id OR OLD.image_order IS NOT NEW.image_order OR OLD.image_png IS NOT NEW.image_png
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = OLD.shared_context_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.shared_context_id
      AND NEW.shared_context_id IS NOT OLD.shared_context_id;
END;

CREATE TRIGGER app_revision_shared_question_context_images_shared_question_context_delete
AFTER DELETE ON shared_question_context_images
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = OLD.shared_context_id;
END;

-- shared_question_context_content_parts -> SHARED_QUESTION_CONTEXT
CREATE TRIGGER app_revision_shared_question_context_content_parts_shared_question_context_insert
AFTER INSERT ON shared_question_context_content_parts
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.shared_context_id;
END;

CREATE TRIGGER app_revision_shared_question_context_content_parts_shared_question_context_update
AFTER UPDATE ON shared_question_context_content_parts
WHEN OLD.shared_context_id IS NOT NEW.shared_context_id OR OLD.content_order IS NOT NEW.content_order OR OLD.content_type IS NOT NEW.content_type OR OLD.region_order IS NOT NEW.region_order OR OLD.image_order IS NOT NEW.image_order
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = OLD.shared_context_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = NEW.shared_context_id
      AND NEW.shared_context_id IS NOT OLD.shared_context_id;
END;

CREATE TRIGGER app_revision_shared_question_context_content_parts_shared_question_context_delete
AFTER DELETE ON shared_question_context_content_parts
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'SHARED_QUESTION_CONTEXT'
      AND local_id = OLD.shared_context_id;
END;

-- booklet_applicability_reviews -> EXAM_BOOKLET
CREATE TRIGGER app_revision_booklet_applicability_reviews_exam_booklet_insert
AFTER INSERT ON booklet_applicability_reviews
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM_BOOKLET'
      AND local_id = NEW.booklet_id;
END;

CREATE TRIGGER app_revision_booklet_applicability_reviews_exam_booklet_update
AFTER UPDATE ON booklet_applicability_reviews
WHEN OLD.booklet_id IS NOT NEW.booklet_id
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM_BOOKLET'
      AND local_id = OLD.booklet_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM_BOOKLET'
      AND local_id = NEW.booklet_id
      AND NEW.booklet_id IS NOT OLD.booklet_id;
END;

CREATE TRIGGER app_revision_booklet_applicability_reviews_exam_booklet_delete
AFTER DELETE ON booklet_applicability_reviews
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'EXAM_BOOKLET'
      AND local_id = OLD.booklet_id;
END;

-- curriculum_mapping_reviews -> CURRICULUM_NODE
CREATE TRIGGER app_revision_curriculum_mapping_reviews_curriculum_node_insert
AFTER INSERT ON curriculum_mapping_reviews
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'CURRICULUM_NODE'
      AND local_id = NEW.source_node_id;
END;

CREATE TRIGGER app_revision_curriculum_mapping_reviews_curriculum_node_update
AFTER UPDATE ON curriculum_mapping_reviews
WHEN OLD.source_node_id IS NOT NEW.source_node_id OR OLD.target_syllabus_version_id IS NOT NEW.target_syllabus_version_id OR OLD.review_outcome IS NOT NEW.review_outcome
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'CURRICULUM_NODE'
      AND local_id = OLD.source_node_id;

    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'CURRICULUM_NODE'
      AND local_id = NEW.source_node_id
      AND NEW.source_node_id IS NOT OLD.source_node_id;
END;

CREATE TRIGGER app_revision_curriculum_mapping_reviews_curriculum_node_delete
AFTER DELETE ON curriculum_mapping_reviews
BEGIN
    UPDATE portable_entity_identities
    SET revision_id = lower(hex(randomblob(4))) || '-' ||
lower(hex(randomblob(2))) || '-4' ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
substr('89ab', (random() & 3) + 1, 1) ||
substr(lower(hex(randomblob(2))), 2) || '-' ||
lower(hex(randomblob(6)))

    WHERE entity_type = 'CURRICULUM_NODE'
      AND local_id = OLD.source_node_id;
END;

UPDATE schema_version
SET version = 26
WHERE version = 25;
