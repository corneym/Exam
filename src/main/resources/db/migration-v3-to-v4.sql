CREATE TEMP TABLE v4_question_migration_guard (
    question_count INTEGER NOT NULL
        CHECK (question_count = 0)
);

INSERT INTO v4_question_migration_guard (question_count)
SELECT COUNT(*)
FROM questions;

DROP TABLE v4_question_migration_guard;

DROP TABLE answer_regions;
DROP TABLE answers;
DROP TABLE question_regions;
DROP TABLE questions;

CREATE TABLE questions (
    id INTEGER PRIMARY KEY,
    booklet_id INTEGER NOT NULL,
    classification_node_id INTEGER NOT NULL,
    question_code TEXT NOT NULL,
    question_text TEXT NOT NULL,
    marks INTEGER NOT NULL
        CHECK (marks >= 1),
    preamble_capture_required INTEGER NOT NULL
        CHECK (preamble_capture_required IN (0, 1)),
    FOREIGN KEY (booklet_id)
        REFERENCES exam_booklets(id),
    FOREIGN KEY (classification_node_id)
        REFERENCES curriculum_nodes(id),
    UNIQUE (booklet_id, question_code)
);

CREATE TABLE question_regions (
    question_id INTEGER NOT NULL,
    region_order INTEGER NOT NULL
        CHECK (region_order >= 0),
    booklet_id INTEGER NOT NULL,
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
    PRIMARY KEY (question_id, region_order),
    FOREIGN KEY (question_id)
        REFERENCES questions(id),
    FOREIGN KEY (booklet_id)
        REFERENCES exam_booklets(id),
    CHECK (x + width <= 1.0),
    CHECK (y + height <= 1.0)
);

CREATE TABLE answers (
    id INTEGER PRIMARY KEY,
    question_id INTEGER NOT NULL UNIQUE,
    answer_text TEXT,
    FOREIGN KEY (question_id)
        REFERENCES questions(id)
);

CREATE TABLE answer_regions (
    answer_id INTEGER NOT NULL,
    region_order INTEGER NOT NULL
        CHECK (region_order >= 0),
    answer_file_id INTEGER NOT NULL,
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
    PRIMARY KEY (answer_id, region_order),
    FOREIGN KEY (answer_id)
        REFERENCES answers(id),
    FOREIGN KEY (answer_file_id)
        REFERENCES answer_files(id),
    CHECK (x + width <= 1.0),
    CHECK (y + height <= 1.0)
);

UPDATE schema_version
SET version = 4;
