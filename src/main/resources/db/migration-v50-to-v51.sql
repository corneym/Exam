-- Schema v51: generated mock examinations and annual Question usage.
-- Portable identity references preserve historical usage independently
-- of the continued existence of local corpus records.

CREATE TABLE mock_examinations (
    exam_id TEXT NOT NULL PRIMARY KEY
        CHECK (length(exam_id) = 36),
    subject_portable_id TEXT NOT NULL
        CHECK (length(subject_portable_id) = 36),
    subject_name TEXT NOT NULL
        CHECK (length(trim(subject_name)) > 0),
    cohort_year INTEGER NOT NULL
        CHECK (cohort_year > 0),
    paper_one_perusal_minutes INTEGER NOT NULL
        CHECK (paper_one_perusal_minutes >= 0),
    paper_one_working_minutes INTEGER NOT NULL
        CHECK (paper_one_working_minutes > 0),
    paper_two_perusal_minutes INTEGER NOT NULL
        CHECK (paper_two_perusal_minutes >= 0),
    paper_two_working_minutes INTEGER NOT NULL
        CHECK (paper_two_working_minutes > 0),
    include_paper_two INTEGER NOT NULL DEFAULT 1
        CHECK (include_paper_two IN (0, 1)),
    finalised_at TEXT
        CHECK (finalised_at IS NULL
               OR length(trim(finalised_at)) > 0),
    UNIQUE (exam_id, subject_portable_id, cohort_year)
);

CREATE TABLE mock_exam_selections (
    exam_id TEXT NOT NULL,
    section TEXT NOT NULL
        CHECK (
            section IN (
                'MULTIPLE_CHOICE',
                'PAPER_ONE_WRITTEN',
                'PAPER_TWO_WRITTEN'
            )
        ),
    position INTEGER NOT NULL
        CHECK (position >= 0),
    question_portable_id TEXT NOT NULL
        CHECK (length(question_portable_id) = 36),
    source_question_portable_id TEXT
        CHECK (source_question_portable_id IS NULL
               OR length(source_question_portable_id) = 36),
    question_code TEXT NOT NULL,
    marks INTEGER NOT NULL
        CHECK (marks > 0),
    PRIMARY KEY (exam_id, section, position),
    UNIQUE (exam_id, question_portable_id),
    FOREIGN KEY (exam_id)
        REFERENCES mock_examinations(exam_id)
        ON DELETE CASCADE
);

CREATE TABLE mock_exam_reservations (
    exam_id TEXT NOT NULL,
    subject_portable_id TEXT NOT NULL,
    cohort_year INTEGER NOT NULL
        CHECK (cohort_year > 0),
    entity_type TEXT NOT NULL
        CHECK (entity_type IN ('QUESTION', 'SOURCE_QUESTION')),
    entity_portable_id TEXT NOT NULL
        CHECK (length(entity_portable_id) = 36),
    released_at TEXT
        CHECK (released_at IS NULL
               OR length(trim(released_at)) > 0),
    PRIMARY KEY (exam_id, entity_type, entity_portable_id),
    FOREIGN KEY (exam_id, subject_portable_id, cohort_year)
        REFERENCES mock_examinations (
            exam_id,
            subject_portable_id,
            cohort_year
        )
);

CREATE UNIQUE INDEX ux_mock_exam_active_reservation
ON mock_exam_reservations (
    subject_portable_id,
    cohort_year,
    entity_type,
    entity_portable_id
)
WHERE released_at IS NULL;

CREATE TRIGGER trg_mock_exam_insert_draft
BEFORE INSERT ON mock_examinations
WHEN NEW.finalised_at IS NOT NULL
BEGIN
    SELECT RAISE(
        ABORT,
        'Mock examinations must initially be drafts'
    );
END;

CREATE TRIGGER trg_mock_exam_immutable_update
BEFORE UPDATE ON mock_examinations
WHEN OLD.finalised_at IS NOT NULL
    OR NEW.exam_id IS NOT OLD.exam_id
    OR NEW.subject_portable_id IS NOT OLD.subject_portable_id
BEGIN
    SELECT RAISE(
        ABORT,
        'Mock examination identity or finalised definition is immutable'
    );
END;

CREATE TRIGGER trg_mock_exam_immutable_delete
BEFORE DELETE ON mock_examinations
WHEN OLD.finalised_at IS NOT NULL
BEGIN
    SELECT RAISE(
        ABORT,
        'Finalised mock examinations cannot be deleted'
    );
END;

CREATE TRIGGER trg_mock_exam_require_selections
BEFORE UPDATE OF finalised_at ON mock_examinations
WHEN OLD.finalised_at IS NULL
    AND NEW.finalised_at IS NOT NULL
    AND NOT EXISTS (
        SELECT 1
        FROM mock_exam_selections
        WHERE exam_id = NEW.exam_id
    )
BEGIN
    SELECT RAISE(
        ABORT,
        'Cannot finalise an empty mock examination'
    );
END;

CREATE TRIGGER trg_mock_exam_validate_paper_two
BEFORE UPDATE OF finalised_at ON mock_examinations
WHEN OLD.finalised_at IS NULL
    AND NEW.finalised_at IS NOT NULL
    AND NEW.include_paper_two = 0
    AND EXISTS (
        SELECT 1
        FROM mock_exam_selections
        WHERE exam_id = NEW.exam_id
          AND section = 'PAPER_TWO_WRITTEN'
    )
BEGIN
    SELECT RAISE(
        ABORT,
        'Paper 2 is excluded but contains selections'
    );
END;

CREATE TRIGGER trg_mock_exam_selection_insert
BEFORE INSERT ON mock_exam_selections
WHEN EXISTS (
    SELECT 1
    FROM mock_examinations
    WHERE exam_id = NEW.exam_id
      AND finalised_at IS NOT NULL
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Finalised mock examination selections are immutable'
    );
END;

CREATE TRIGGER trg_mock_exam_selection_update
BEFORE UPDATE ON mock_exam_selections
WHEN EXISTS (
    SELECT 1
    FROM mock_examinations
    WHERE exam_id IN (OLD.exam_id, NEW.exam_id)
      AND finalised_at IS NOT NULL
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Finalised mock examination selections are immutable'
    );
END;

CREATE TRIGGER trg_mock_exam_selection_delete
BEFORE DELETE ON mock_exam_selections
WHEN EXISTS (
    SELECT 1
    FROM mock_examinations
    WHERE exam_id = OLD.exam_id
      AND finalised_at IS NOT NULL
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Finalised mock examination selections are immutable'
    );
END;

CREATE TRIGGER trg_mock_exam_reservation_insert
BEFORE INSERT ON mock_exam_reservations
WHEN NEW.released_at IS NOT NULL
    OR NOT EXISTS (
        SELECT 1
        FROM mock_examinations e
        JOIN mock_exam_selections s ON s.exam_id = e.exam_id
        WHERE e.exam_id = NEW.exam_id
          AND e.subject_portable_id = NEW.subject_portable_id
          AND e.cohort_year = NEW.cohort_year
          AND e.finalised_at IS NOT NULL
          AND (
              (
                  NEW.entity_type = 'QUESTION'
                  AND s.question_portable_id = NEW.entity_portable_id
              )
              OR
              (
                  NEW.entity_type = 'SOURCE_QUESTION'
                  AND s.source_question_portable_id = NEW.entity_portable_id
              )
          )
    )
BEGIN
    SELECT RAISE(ABORT, 'Invalid mock examination reservation');
END;

CREATE TRIGGER trg_mock_exam_reservation_update
BEFORE UPDATE ON mock_exam_reservations
WHEN OLD.released_at IS NOT NULL
    OR NEW.released_at IS NULL
    OR NEW.exam_id IS NOT OLD.exam_id
    OR NEW.subject_portable_id IS NOT OLD.subject_portable_id
    OR NEW.cohort_year IS NOT OLD.cohort_year
    OR NEW.entity_type IS NOT OLD.entity_type
    OR NEW.entity_portable_id IS NOT OLD.entity_portable_id
BEGIN
    SELECT RAISE(
        ABORT,
        'Mock examination reservation history is immutable'
    );
END;

CREATE TRIGGER trg_mock_exam_reservation_delete
BEFORE DELETE ON mock_exam_reservations
BEGIN
    SELECT RAISE(
        ABORT,
        'Mock examination reservation history cannot be deleted'
    );
END;

-- Explicit conflict validation also protects the finalisation
-- operation from alternative outer SQLite conflict policies.

CREATE TRIGGER trg_mock_exam_finalise_conflict
BEFORE UPDATE OF finalised_at ON mock_examinations
WHEN OLD.finalised_at IS NULL
    AND NEW.finalised_at IS NOT NULL
    AND EXISTS (
        SELECT 1
        FROM mock_exam_selections s
        JOIN mock_exam_reservations r
          ON (
              (
                  r.entity_type = 'QUESTION'
                  AND r.entity_portable_id = s.question_portable_id
              )
              OR
              (
                  r.entity_type = 'SOURCE_QUESTION'
                  AND r.entity_portable_id = s.source_question_portable_id
              )
          )
        WHERE s.exam_id = NEW.exam_id
          AND r.subject_portable_id = NEW.subject_portable_id
          AND r.cohort_year = NEW.cohort_year
          AND r.released_at IS NULL
    )
BEGIN
    SELECT RAISE(
        ABORT,
        'Mock examination contains an already reserved Question'
    );
END;

CREATE TRIGGER trg_mock_exam_finalise
AFTER UPDATE OF finalised_at ON mock_examinations
WHEN OLD.finalised_at IS NULL
    AND NEW.finalised_at IS NOT NULL
BEGIN
    INSERT INTO mock_exam_reservations (
        exam_id,
        subject_portable_id,
        cohort_year,
        entity_type,
        entity_portable_id
    )
    SELECT
        NEW.exam_id,
        NEW.subject_portable_id,
        NEW.cohort_year,
        'QUESTION',
        question_portable_id
    FROM mock_exam_selections
    WHERE exam_id = NEW.exam_id;

    INSERT INTO mock_exam_reservations (
        exam_id,
        subject_portable_id,
        cohort_year,
        entity_type,
        entity_portable_id
    )
    SELECT DISTINCT
        NEW.exam_id,
        NEW.subject_portable_id,
        NEW.cohort_year,
        'SOURCE_QUESTION',
        source_question_portable_id
    FROM mock_exam_selections
    WHERE exam_id = NEW.exam_id
      AND source_question_portable_id IS NOT NULL;
END;

UPDATE schema_version
SET version = 51
WHERE version = 50;
