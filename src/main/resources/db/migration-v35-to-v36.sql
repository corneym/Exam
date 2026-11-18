-- Schema v36: immutable coordinator review decisions.
-- A review is evidence of a decision, never evidence that a
-- proposed record was applied to the authoritative corpus.

CREATE TABLE contribution_review_batches (
    review_id TEXT NOT NULL PRIMARY KEY,
    package_id TEXT NOT NULL,
    observed_at TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    review_note TEXT NOT NULL,

    UNIQUE (review_id, package_id),

    FOREIGN KEY (package_id)
        REFERENCES contribution_intake_packages(package_id)
        ON DELETE RESTRICT,

    CHECK (length(review_id) = 36),
    CHECK (length(package_id) = 36),
    CHECK (length(observed_at) > 0),
    CHECK (length(recorded_at) > 0),
    CHECK (length(trim(review_note)) BETWEEN 1 AND 4000)
);

CREATE INDEX idx_contribution_review_package
ON contribution_review_batches(package_id, recorded_at);

CREATE TABLE contribution_review_decisions (
    review_id TEXT NOT NULL,
    package_id TEXT NOT NULL,
    record_type TEXT NOT NULL,
    record_id TEXT NOT NULL,
    baseline_revision_id TEXT,
    proposed_revision_id TEXT NOT NULL,
    master_revision_id TEXT,
    master_sha256 TEXT,
    preliminary_status TEXT NOT NULL
        CHECK (preliminary_status IN (
            'READY_NEW',
            'READY_UPDATE',
            'SKIP_IDENTICAL',
            'HELD_STALE_MASTER',
            'HELD_MISSING_MASTER',
            'HELD_ID_COLLISION',
            'HELD_OUT_OF_SCOPE',
            'HELD_LINEAGE'
        )),
    choice TEXT NOT NULL
        CHECK (choice IN (
            'QUEUE_APPLY',
            'SKIP_IDENTICAL',
            'REJECT',
            'HOLD'
        )),
    result_revision_id TEXT,

    PRIMARY KEY (review_id, record_type, record_id),

    FOREIGN KEY (review_id, package_id)
        REFERENCES contribution_review_batches(review_id, package_id)
        ON DELETE RESTRICT,

    FOREIGN KEY (package_id, record_type, record_id)
        REFERENCES contribution_intake_proposals(
            package_id, record_type, record_id
        )
        ON DELETE RESTRICT,

    CHECK (length(record_type) BETWEEN 1 AND 64),
    CHECK (length(record_id) = 36),
    CHECK (
        baseline_revision_id IS NULL
        OR length(baseline_revision_id) = 36
    ),
    CHECK (length(proposed_revision_id) = 36),
    CHECK (
        master_revision_id IS NULL
        OR length(master_revision_id) = 36
    ),
    CHECK (
        (master_revision_id IS NULL AND master_sha256 IS NULL)
        OR (
            master_revision_id IS NOT NULL
            AND length(master_sha256) = 64
            AND master_sha256 NOT GLOB '*[^0-9a-f]*'
        )
    ),
    CHECK (
        result_revision_id IS NULL
        OR length(result_revision_id) = 36
    ),
    CHECK (
        (choice = 'QUEUE_APPLY'
            AND preliminary_status IN ('READY_NEW', 'READY_UPDATE'))
        OR choice <> 'QUEUE_APPLY'
    ),
    CHECK (
        (choice = 'SKIP_IDENTICAL'
            AND preliminary_status = 'SKIP_IDENTICAL'
            AND result_revision_id = master_revision_id
            AND result_revision_id IS NOT NULL)
        OR
        (choice <> 'SKIP_IDENTICAL'
            AND result_revision_id IS NULL)
    )
);

CREATE TRIGGER trg_contribution_review_batches_no_update
BEFORE UPDATE ON contribution_review_batches
BEGIN
    SELECT RAISE(ABORT, 'Contribution review evidence is immutable');
END;

CREATE TRIGGER trg_contribution_review_batches_no_delete
BEFORE DELETE ON contribution_review_batches
BEGIN
    SELECT RAISE(ABORT, 'Contribution review evidence is immutable');
END;

CREATE TRIGGER trg_contribution_review_decisions_no_update
BEFORE UPDATE ON contribution_review_decisions
BEGIN
    SELECT RAISE(ABORT, 'Contribution review decisions are immutable');
END;

CREATE TRIGGER trg_contribution_review_decisions_no_delete
BEFORE DELETE ON contribution_review_decisions
BEGIN
    SELECT RAISE(ABORT, 'Contribution review decisions are immutable');
END;

UPDATE schema_version
SET version = 36
WHERE version = 35;
