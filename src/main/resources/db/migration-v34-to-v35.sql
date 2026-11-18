-- Schema v35: immutable coordinator intake evidence for offline contributions.
-- A missing previous return must remain representable, so previous_package_id
-- intentionally has no foreign key until lineage has been independently reviewed.

CREATE TABLE contribution_intake_packages (
    package_id TEXT NOT NULL PRIMARY KEY,
    assignment_id TEXT NOT NULL,
    return_number INTEGER NOT NULL,
    previous_package_id TEXT,
    baseline_snapshot_id TEXT NOT NULL,
    master_installation_id TEXT NOT NULL,
    contributor_id TEXT NOT NULL,
    origin_installation_id TEXT NOT NULL,
    archive_sha256 TEXT NOT NULL,
    intake_status TEXT NOT NULL
        CHECK (intake_status IN ('READY_FOR_REVIEW', 'HELD_LINEAGE')),
    received_at TEXT NOT NULL,

    FOREIGN KEY (assignment_id)
        REFERENCES exam_work_assignments(assignment_id)
        ON DELETE RESTRICT,
    CHECK (length(package_id) = 36),
    CHECK (length(assignment_id) = 36),
    CHECK (return_number > 0),
    CHECK (previous_package_id IS NULL OR length(previous_package_id) = 36),
    CHECK (length(baseline_snapshot_id) = 36),
    CHECK (length(master_installation_id) = 36),
    CHECK (length(contributor_id) = 36),
    CHECK (length(origin_installation_id) = 36),
    CHECK (length(archive_sha256) = 64
           AND archive_sha256 NOT GLOB '*[^0-9a-f]*'),
    CHECK (length(received_at) > 0)
);

CREATE INDEX idx_contribution_intake_assignment_order
ON contribution_intake_packages (assignment_id, return_number, package_id);

-- Each candidate retains its canonical portable values and both revision IDs.
-- No master-domain record is changed by intake.
CREATE TABLE contribution_intake_proposals (
    package_id TEXT NOT NULL,
    record_type TEXT NOT NULL,
    record_id TEXT NOT NULL,
    proposed_revision_id TEXT NOT NULL,
    baseline_revision_id TEXT,
    record_jsonl BLOB NOT NULL,

    PRIMARY KEY (package_id, record_type, record_id),
    FOREIGN KEY (package_id)
        REFERENCES contribution_intake_packages(package_id)
        ON DELETE RESTRICT,
    CHECK (length(record_type) BETWEEN 1 AND 64),
    CHECK (length(record_id) = 36),
    CHECK (length(proposed_revision_id) = 36),
    CHECK (baseline_revision_id IS NULL OR length(baseline_revision_id) = 36),
    CHECK (length(record_jsonl) BETWEEN 1 AND 1048577)
);

CREATE TRIGGER trg_contribution_intake_packages_no_update
BEFORE UPDATE ON contribution_intake_packages
BEGIN
    SELECT RAISE(ABORT, 'Received contribution evidence cannot be changed');
END;

CREATE TRIGGER trg_contribution_intake_packages_no_delete
BEFORE DELETE ON contribution_intake_packages
BEGIN
    SELECT RAISE(ABORT, 'Received contribution evidence cannot be deleted');
END;

CREATE TRIGGER trg_contribution_intake_proposals_no_update
BEFORE UPDATE ON contribution_intake_proposals
BEGIN
    SELECT RAISE(ABORT, 'Received proposal evidence cannot be changed');
END;

CREATE TRIGGER trg_contribution_intake_proposals_no_delete
BEFORE DELETE ON contribution_intake_proposals
BEGIN
    SELECT RAISE(ABORT, 'Received proposal evidence cannot be deleted');
END;

UPDATE schema_version
SET version = 35
WHERE version = 34;
