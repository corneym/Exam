-- Version 20 records explicit human confirmation that every Question
-- in a booklet has had its Revision Output applicability reviewed.
--
-- Existing booklets receive no review records. In particular, existing
-- COMPLETE Exams remain COMPLETE but are not assumed to be reviewed.

CREATE TABLE booklet_applicability_reviews (
    booklet_id INTEGER PRIMARY KEY NOT NULL,
    FOREIGN KEY (booklet_id)
        REFERENCES exam_booklets(id)
        ON DELETE CASCADE
);

UPDATE schema_version
SET version = 20
WHERE version = 19;
