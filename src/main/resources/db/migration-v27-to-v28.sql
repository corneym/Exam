-- Schema v28: coordinator-owned offline Exam assignment ledger.
-- The issuance baseline is immutable. Assignment lifecycle is stored
-- separately so completing/cancelling work cannot rewrite its baseline.

CREATE TABLE exam_work_assignments (
    assignment_id TEXT NOT NULL PRIMARY KEY,
    package_id TEXT NOT NULL UNIQUE,
    baseline_snapshot_id TEXT NOT NULL UNIQUE,
    master_installation_id TEXT NOT NULL,
    subject_portable_id TEXT NOT NULL,
    exam_portable_id TEXT NOT NULL,
    contributor_id TEXT NOT NULL,
    contributor_name TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    archive_sha256 TEXT NOT NULL,
    records_sha256 TEXT NOT NULL,
    assets_sha256 TEXT NOT NULL,
    baseline_records BLOB NOT NULL,
    baseline_assets BLOB NOT NULL,

    CHECK (length(assignment_id) = 36),
    CHECK (length(package_id) = 36),
    CHECK (length(baseline_snapshot_id) = 36),
    CHECK (length(master_installation_id) = 36),
    CHECK (length(subject_portable_id) = 36),
    CHECK (length(exam_portable_id) = 36),
    CHECK (length(contributor_id) = 36),
    CHECK (
        length(trim(contributor_name)) BETWEEN 1 AND 256
    ),
    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(records_sha256) = 64
        AND records_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(assets_sha256) = 64
        AND assets_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(baseline_records) > 0
        AND length(baseline_records) <= 67108864
    ),
    CHECK (length(baseline_assets) <= 67108864)
);

CREATE TABLE exam_work_assignment_states (
    assignment_id TEXT NOT NULL PRIMARY KEY,
    state TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (state IN ('ACTIVE', 'COMPLETE', 'CANCELLED')),
    updated_at TEXT NOT NULL,

    FOREIGN KEY (assignment_id)
        REFERENCES exam_work_assignments(assignment_id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_exam_work_assignments_exam
ON exam_work_assignments (
    subject_portable_id,
    exam_portable_id,
    issued_at
);

-- Original version 28 immutable issuance and lifecycle guards.
-- Definitions are frozen here so their installation is transactional.

CREATE TRIGGER trg_exam_work_assignments_no_delete
BEFORE DELETE ON exam_work_assignments
BEGIN
    SELECT RAISE(
        ABORT,
        'Issued assignment evidence cannot be deleted'
    );
END;

CREATE TRIGGER trg_exam_work_assignments_no_update
BEFORE UPDATE ON exam_work_assignments
BEGIN
    SELECT RAISE(
        ABORT,
        'Issued assignment evidence cannot be modified'
    );
END;

CREATE TRIGGER trg_exam_work_assignment_initial_state
BEFORE INSERT ON exam_work_assignment_states
WHEN NEW.state <> 'ACTIVE'
BEGIN
    SELECT RAISE(
        ABORT,
        'New assignments must begin ACTIVE'
    );
END;

CREATE TRIGGER trg_exam_work_assignment_state_no_delete
BEFORE DELETE ON exam_work_assignment_states
BEGIN
    SELECT RAISE(
        ABORT,
        'Assignment lifecycle evidence cannot be deleted'
    );
END;

CREATE TRIGGER trg_exam_work_assignment_state_transition
BEFORE UPDATE ON exam_work_assignment_states
WHEN OLD.state <> 'ACTIVE'
     OR NEW.state NOT IN ('COMPLETE', 'CANCELLED')
BEGIN
    SELECT RAISE(
        ABORT,
        'Assignment lifecycle transition is not permitted'
    );
END;

UPDATE schema_version
SET version = 28
WHERE version = 27;
