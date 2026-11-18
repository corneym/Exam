-- Schema v21: revoke explicit booklet applicability reviews after
-- material changes to their source data, answers or classification.
-- These SQL triggers replace the former Java-installed definitions.
-- They run inside the caller-owned migration transaction.
-- Existing v20 review confirmations are not assumed current.

CREATE TRIGGER app_review_questions_insert
AFTER INSERT ON questions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT NEW.booklet_id
    );
END;

CREATE TRIGGER app_review_questions_update
AFTER UPDATE ON questions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.booklet_id
        UNION
        SELECT NEW.booklet_id
    );
END;

CREATE TRIGGER app_review_questions_delete
AFTER DELETE ON questions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.booklet_id
    );
END;

CREATE TRIGGER app_review_question_regions_insert
AFTER INSERT ON question_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_regions_update
AFTER UPDATE ON question_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
        UNION
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_regions_delete
AFTER DELETE ON question_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
    );
END;

CREATE TRIGGER app_review_question_images_insert
AFTER INSERT ON question_images
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_images_update
AFTER UPDATE ON question_images
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
        UNION
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_images_delete
AFTER DELETE ON question_images
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
    );
END;

CREATE TRIGGER app_review_question_content_parts_insert
AFTER INSERT ON question_content_parts
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_content_parts_update
AFTER UPDATE ON question_content_parts
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
        UNION
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_content_parts_delete
AFTER DELETE ON question_content_parts
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
    );
END;

CREATE TRIGGER app_review_question_output_exclusions_insert
AFTER INSERT ON question_output_exclusions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_output_exclusions_update
AFTER UPDATE ON question_output_exclusions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
        UNION
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_question_output_exclusions_delete
AFTER DELETE ON question_output_exclusions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
    );
END;

CREATE TRIGGER app_review_source_questions_insert
AFTER INSERT ON source_questions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT NEW.booklet_id
    );
END;

CREATE TRIGGER app_review_source_questions_update
AFTER UPDATE ON source_questions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.booklet_id
        UNION
        SELECT NEW.booklet_id
    );
END;

CREATE TRIGGER app_review_source_questions_delete
AFTER DELETE ON source_questions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.booklet_id
    );
END;

CREATE TRIGGER app_review_shared_question_contexts_insert
AFTER INSERT ON shared_question_contexts
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT NEW.booklet_id
    );
END;

CREATE TRIGGER app_review_shared_question_contexts_update
AFTER UPDATE ON shared_question_contexts
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.booklet_id
        UNION
        SELECT NEW.booklet_id
    );
END;

CREATE TRIGGER app_review_shared_question_contexts_delete
AFTER DELETE ON shared_question_contexts
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.booklet_id
    );
END;

CREATE TRIGGER app_review_shared_question_context_regions_insert
AFTER INSERT ON shared_question_context_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT c.booklet_id
        FROM shared_question_contexts c
        WHERE c.id = NEW.shared_context_id
    );
END;

CREATE TRIGGER app_review_shared_question_context_regions_update
AFTER UPDATE ON shared_question_context_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT c.booklet_id
        FROM shared_question_contexts c
        WHERE c.id = OLD.shared_context_id
        UNION
        SELECT c.booklet_id
        FROM shared_question_contexts c
        WHERE c.id = NEW.shared_context_id
    );
END;

CREATE TRIGGER app_review_shared_question_context_regions_delete
AFTER DELETE ON shared_question_context_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT c.booklet_id
        FROM shared_question_contexts c
        WHERE c.id = OLD.shared_context_id
    );
END;

CREATE TRIGGER app_review_answers_insert
AFTER INSERT ON answers
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_answers_update
AFTER UPDATE ON answers
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
        UNION
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = NEW.question_id
    );
END;

CREATE TRIGGER app_review_answers_delete
AFTER DELETE ON answers
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM questions q
        WHERE q.id = OLD.question_id
    );
END;

CREATE TRIGGER app_review_answer_regions_insert
AFTER INSERT ON answer_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM answers a
        JOIN questions q ON q.id = a.question_id
        WHERE a.id = NEW.answer_id
    );
END;

CREATE TRIGGER app_review_answer_regions_update
AFTER UPDATE ON answer_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM answers a
        JOIN questions q ON q.id = a.question_id
        WHERE a.id = OLD.answer_id
        UNION
        SELECT q.booklet_id
        FROM answers a
        JOIN questions q ON q.id = a.question_id
        WHERE a.id = NEW.answer_id
    );
END;

CREATE TRIGGER app_review_answer_regions_delete
AFTER DELETE ON answer_regions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT q.booklet_id
        FROM answers a
        JOIN questions q ON q.id = a.question_id
        WHERE a.id = OLD.answer_id
    );
END;

CREATE TRIGGER app_review_exam_booklets_insert
AFTER INSERT ON exam_booklets
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT NEW.id
    );
END;

CREATE TRIGGER app_review_exam_booklets_update
AFTER UPDATE ON exam_booklets
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.id
        UNION
        SELECT NEW.id
    );
END;

CREATE TRIGGER app_review_exam_booklets_delete
AFTER DELETE ON exam_booklets
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT OLD.id
    );
END;

CREATE TRIGGER app_review_answer_files_insert
AFTER INSERT ON answer_files
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        WHERE b.exam_id = NEW.exam_id
    );
END;

CREATE TRIGGER app_review_answer_files_update
AFTER UPDATE ON answer_files
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        WHERE b.exam_id = OLD.exam_id
        UNION
        SELECT b.id
        FROM exam_booklets b
        WHERE b.exam_id = NEW.exam_id
    );
END;

CREATE TRIGGER app_review_answer_files_delete
AFTER DELETE ON answer_files
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        WHERE b.exam_id = OLD.exam_id
    );
END;

CREATE TRIGGER app_review_source_documents_insert
AFTER INSERT ON source_documents
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        WHERE b.source_document_id = NEW.id

        UNION

        SELECT b.id
        FROM exam_booklets b
        JOIN answer_files a ON a.exam_id = b.exam_id
        WHERE a.source_document_id = NEW.id
    );
END;

CREATE TRIGGER app_review_source_documents_update
AFTER UPDATE ON source_documents
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        WHERE b.source_document_id = OLD.id

        UNION

        SELECT b.id
        FROM exam_booklets b
        JOIN answer_files a ON a.exam_id = b.exam_id
        WHERE a.source_document_id = OLD.id
        UNION
        SELECT b.id
        FROM exam_booklets b
        WHERE b.source_document_id = NEW.id

        UNION

        SELECT b.id
        FROM exam_booklets b
        JOIN answer_files a ON a.exam_id = b.exam_id
        WHERE a.source_document_id = NEW.id
    );
END;

CREATE TRIGGER app_review_source_documents_delete
AFTER DELETE ON source_documents
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        WHERE b.source_document_id = OLD.id

        UNION

        SELECT b.id
        FROM exam_booklets b
        JOIN answer_files a ON a.exam_id = b.exam_id
        WHERE a.source_document_id = OLD.id
    );
END;

CREATE TRIGGER app_review_curriculum_nodes_insert
AFTER INSERT ON curriculum_nodes
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        JOIN syllabus_versions s
          ON s.subject_id = e.subject_id
        WHERE s.id = NEW.syllabus_version_id
    );
END;

CREATE TRIGGER app_review_curriculum_nodes_update
AFTER UPDATE ON curriculum_nodes
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        JOIN syllabus_versions s
          ON s.subject_id = e.subject_id
        WHERE s.id = OLD.syllabus_version_id
        UNION
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        JOIN syllabus_versions s
          ON s.subject_id = e.subject_id
        WHERE s.id = NEW.syllabus_version_id
    );
END;

CREATE TRIGGER app_review_curriculum_nodes_delete
AFTER DELETE ON curriculum_nodes
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        JOIN syllabus_versions s
          ON s.subject_id = e.subject_id
        WHERE s.id = OLD.syllabus_version_id
    );
END;

CREATE TRIGGER app_review_syllabus_versions_insert
AFTER INSERT ON syllabus_versions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id = NEW.subject_id
    );
END;

CREATE TRIGGER app_review_syllabus_versions_update
AFTER UPDATE ON syllabus_versions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id = OLD.subject_id
        UNION
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id = NEW.subject_id
    );
END;

CREATE TRIGGER app_review_syllabus_versions_delete
AFTER DELETE ON syllabus_versions
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id = OLD.subject_id
    );
END;

CREATE TRIGGER app_review_curriculum_mappings_insert
AFTER INSERT ON curriculum_mappings
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id IN (
                NEW.source_node_id,
                NEW.target_node_id
            )
        )
    );
END;

CREATE TRIGGER app_review_curriculum_mappings_update
AFTER UPDATE ON curriculum_mappings
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id IN (
                OLD.source_node_id,
                OLD.target_node_id
            )
        )
        UNION
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id IN (
                NEW.source_node_id,
                NEW.target_node_id
            )
        )
    );
END;

CREATE TRIGGER app_review_curriculum_mappings_delete
AFTER DELETE ON curriculum_mappings
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id IN (
                OLD.source_node_id,
                OLD.target_node_id
            )
        )
    );
END;

CREATE TRIGGER app_review_curriculum_mapping_reviews_insert
AFTER INSERT ON curriculum_mapping_reviews
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id = NEW.source_node_id

            UNION

            SELECT s.subject_id
            FROM syllabus_versions s
            WHERE s.id =
                NEW.target_syllabus_version_id
        )
    );
END;

CREATE TRIGGER app_review_curriculum_mapping_reviews_update
AFTER UPDATE ON curriculum_mapping_reviews
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id = OLD.source_node_id

            UNION

            SELECT s.subject_id
            FROM syllabus_versions s
            WHERE s.id =
                OLD.target_syllabus_version_id
        )
        UNION
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id = NEW.source_node_id

            UNION

            SELECT s.subject_id
            FROM syllabus_versions s
            WHERE s.id =
                NEW.target_syllabus_version_id
        )
    );
END;

CREATE TRIGGER app_review_curriculum_mapping_reviews_delete
AFTER DELETE ON curriculum_mapping_reviews
BEGIN
    DELETE FROM booklet_applicability_reviews
    WHERE booklet_id IN (
        SELECT b.id
        FROM exam_booklets b
        JOIN exams e ON e.id = b.exam_id
        WHERE e.subject_id IN (
            SELECT s.subject_id
            FROM curriculum_nodes n
            JOIN syllabus_versions s
              ON s.id = n.syllabus_version_id
            WHERE n.id = OLD.source_node_id

            UNION

            SELECT s.subject_id
            FROM syllabus_versions s
            WHERE s.id =
                OLD.target_syllabus_version_id
        )
    );
END;

-- Existing reviews cannot be proven current under the new invalidation rules.
DELETE FROM booklet_applicability_reviews;

UPDATE schema_version
SET version = 21
WHERE version = 20;
