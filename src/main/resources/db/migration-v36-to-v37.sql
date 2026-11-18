-- Schema v37: immutable terminal contribution application evidence.
-- Application results are committed in the same SQLite transaction
-- as the authoritative domain changes they describe.

CREATE TABLE contribution_application_batches (
    review_id TEXT NOT NULL PRIMARY KEY,
    package_id TEXT NOT NULL UNIQUE,
    completed_at TEXT NOT NULL,

    FOREIGN KEY (review_id, package_id)
        REFERENCES contribution_review_batches(review_id, package_id)
        ON DELETE RESTRICT,

    CHECK (length(review_id) = 36),
    CHECK (length(package_id) = 36),
    CHECK (length(completed_at) > 0)
);

CREATE TABLE contribution_application_results (
    review_id TEXT NOT NULL,
    record_type TEXT NOT NULL,
    record_id TEXT NOT NULL,
    outcome TEXT NOT NULL
        CHECK (outcome IN ('APPLIED', 'SKIPPED', 'REJECTED')),
    resulting_revision_id TEXT,

    PRIMARY KEY (review_id, record_type, record_id),

    FOREIGN KEY (review_id)
        REFERENCES contribution_application_batches(review_id)
        ON DELETE RESTRICT,

    FOREIGN KEY (review_id, record_type, record_id)
        REFERENCES contribution_review_decisions(
            review_id, record_type, record_id
        )
        ON DELETE RESTRICT,

    CHECK (length(record_type) BETWEEN 1 AND 64),
    CHECK (length(record_id) = 36),

    CHECK (
        (outcome IN ('APPLIED', 'SKIPPED')
            AND resulting_revision_id IS NOT NULL
            AND length(resulting_revision_id) = 36)
        OR
        (outcome = 'REJECTED'
            AND resulting_revision_id IS NULL)
    )
);

CREATE TRIGGER trg_contribution_application_batches_no_update
BEFORE UPDATE ON contribution_application_batches
BEGIN
    SELECT RAISE(ABORT,
        'Contribution application evidence is immutable');
END;

CREATE TRIGGER trg_contribution_application_batches_no_delete
BEFORE DELETE ON contribution_application_batches
BEGIN
    SELECT RAISE(ABORT,
        'Contribution application evidence is immutable');
END;

CREATE TRIGGER trg_contribution_application_results_no_update
BEFORE UPDATE ON contribution_application_results
BEGIN
    SELECT RAISE(ABORT,
        'Contribution application results are immutable');
END;

CREATE TRIGGER trg_contribution_application_results_no_delete
BEFORE DELETE ON contribution_application_results
BEGIN
    SELECT RAISE(ABORT,
        'Contribution application results are immutable');
END;

UPDATE schema_version
SET version = 37
WHERE version = 36;
