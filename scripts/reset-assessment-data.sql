-- Exam Question Bank
-- Development-only assessment/capture data reset.
--
-- Purpose:
--   Remove disposable question/capture data while preserving:
--     - subjects and syllabus versions
--     - curriculum nodes
--     - curriculum mappings/reviews
--     - exam providers
--     - exams and exam booklets
--     - source documents
--     - answer files
--     - schema_version
--
-- IMPORTANT:
--   1. Close the application before running this script.
--   2. Make a backup copy of questionbank.db first.
--   3. Run this only against a development database whose capture work is disposable.
-- 
-- TO RUN:
--    sqlite3 "/path/to/questionbank.db" < scripts/reset-assessment-data.sql

PRAGMA foreign_keys = ON;

BEGIN IMMEDIATE;

DELETE FROM answer_regions;
DELETE FROM answers;

DELETE FROM question_regions;
DELETE FROM questions;

DELETE FROM shared_question_context_regions;
DELETE FROM shared_question_contexts;

DELETE FROM source_questions;

COMMIT;

-- Should return no rows.
PRAGMA foreign_key_check;

-- Verification: every count below should be zero.
SELECT 'questions' AS table_name, COUNT(*) AS row_count FROM questions
UNION ALL
SELECT 'question_regions', COUNT(*) FROM question_regions
UNION ALL
SELECT 'answers', COUNT(*) FROM answers
UNION ALL
SELECT 'answer_regions', COUNT(*) FROM answer_regions
UNION ALL
SELECT 'source_questions', COUNT(*) FROM source_questions
UNION ALL
SELECT 'shared_question_contexts', COUNT(*) FROM shared_question_contexts
UNION ALL
SELECT 'shared_question_context_regions', COUNT(*) FROM shared_question_context_regions;
