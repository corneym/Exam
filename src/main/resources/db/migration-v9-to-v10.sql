-- Each ExamBooklet may resolve to one AnswerFile. The relationship remains
-- nullable because legacy data and MCQ-only answers may not establish which
-- answer document belongs to the booklet.
ALTER TABLE exam_booklets
ADD COLUMN answer_file_id INTEGER
    REFERENCES answer_files(id);

-- Preserve relationships that existing answer-region data establishes without
-- guessing. A booklet is backfilled only when every usable stored region points
-- to exactly one AnswerFile belonging to the same Exam.
UPDATE exam_booklets
SET answer_file_id = (
    SELECT MIN(ar.answer_file_id)
    FROM questions q
    JOIN answers a
        ON a.question_id = q.id
    JOIN answer_regions ar
        ON ar.answer_id = a.id
    JOIN answer_files af
        ON af.id = ar.answer_file_id
    WHERE q.booklet_id = exam_booklets.id
      AND af.exam_id = exam_booklets.exam_id
)
WHERE (
    SELECT COUNT(DISTINCT ar.answer_file_id)
    FROM questions q
    JOIN answers a
        ON a.question_id = q.id
    JOIN answer_regions ar
        ON ar.answer_id = a.id
    JOIN answer_files af
        ON af.id = ar.answer_file_id
    WHERE q.booklet_id = exam_booklets.id
      AND af.exam_id = exam_booklets.exam_id
) = 1
AND NOT EXISTS (
    SELECT 1
    FROM questions q
    JOIN answers a
        ON a.question_id = q.id
    JOIN answer_regions ar
        ON ar.answer_id = a.id
    JOIN answer_files af
        ON af.id = ar.answer_file_id
    WHERE q.booklet_id = exam_booklets.id
      AND af.exam_id <> exam_booklets.exam_id
);

UPDATE schema_version
SET version = 10;
