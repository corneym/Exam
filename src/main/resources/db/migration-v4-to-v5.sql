CREATE TABLE source_questions (
    id INTEGER PRIMARY KEY,
    booklet_id INTEGER NOT NULL,
    source_question_code TEXT NOT NULL,
    FOREIGN KEY (booklet_id)
        REFERENCES exam_booklets(id),
    UNIQUE (booklet_id, source_question_code)
);

CREATE TABLE shared_question_contexts (
    id INTEGER PRIMARY KEY,
    booklet_id INTEGER NOT NULL,
    context_label TEXT NOT NULL,
    FOREIGN KEY (booklet_id)
        REFERENCES exam_booklets(id)
);

CREATE TABLE shared_question_context_regions (
    shared_context_id INTEGER NOT NULL,
    region_order INTEGER NOT NULL
        CHECK (region_order >= 0),
    page_number INTEGER NOT NULL
        CHECK (page_number >= 1),
    x REAL NOT NULL
        CHECK (x >= 0.0 AND x < 1.0),
    y REAL NOT NULL
        CHECK (y >= 0.0 AND y < 1.0),
    width REAL NOT NULL
        CHECK (width > 0.0 AND width <= 1.0),
    height REAL NOT NULL
        CHECK (height > 0.0 AND height <= 1.0),
    PRIMARY KEY (shared_context_id, region_order),
    FOREIGN KEY (shared_context_id)
        REFERENCES shared_question_contexts(id),
    CHECK (x + width <= 1.0),
    CHECK (y + height <= 1.0)
);

ALTER TABLE questions
ADD COLUMN source_question_id INTEGER
    REFERENCES source_questions(id);

ALTER TABLE questions
ADD COLUMN shared_context_id INTEGER
    REFERENCES shared_question_contexts(id);

UPDATE schema_version
SET version = 5;
