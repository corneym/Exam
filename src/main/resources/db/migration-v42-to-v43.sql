-- Schema v43: authenticated, append-only contributor receipt evidence.
-- Acknowledgement records independently verified master results.
-- No original baseline, working Question/Answer or portable identity
-- is modified by this migration.

CREATE TABLE contributor_receipt_acknowledgements (
    receipt_package_id TEXT NOT NULL PRIMARY KEY,
    return_package_id TEXT NOT NULL UNIQUE,
    assignment_id TEXT NOT NULL,
    review_id TEXT NOT NULL,
    master_installation_id TEXT NOT NULL,
    archive_sha256 TEXT NOT NULL,
    outcomes_jsonl BLOB NOT NULL,
    master_records_jsonl BLOB NOT NULL,
    acknowledged_at TEXT NOT NULL,

    FOREIGN KEY (return_package_id)
        REFERENCES contributor_return_packages(package_id)
        ON DELETE RESTRICT,

    FOREIGN KEY (assignment_id)
        REFERENCES contributor_assignment_sessions(assignment_id)
        ON DELETE RESTRICT,

    CHECK (length(receipt_package_id) = 36),
    CHECK (length(return_package_id) = 36),
    CHECK (length(assignment_id) = 36),
    CHECK (length(review_id) = 36),
    CHECK (length(master_installation_id) = 36),

    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),

    CHECK (length(outcomes_jsonl) > 0),
    CHECK (length(acknowledged_at) > 0)
);

-- The receipt must belong to the contributor's durable
-- publication lineage and its original master installation.

CREATE TRIGGER trg_contributor_receipt_ack_validate
BEFORE INSERT ON contributor_receipt_acknowledgements
WHEN NOT EXISTS (
    SELECT 1
    FROM contributor_return_packages returned
    JOIN contributor_assignment_sessions session
      ON session.assignment_id = returned.assignment_id
    WHERE returned.package_id = NEW.return_package_id
      AND returned.assignment_id = NEW.assignment_id
      AND returned.master_installation_id =
          NEW.master_installation_id
      AND session.master_installation_id =
          NEW.master_installation_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Receipt acknowledgement has invalid return lineage'
    );
END;

CREATE TRIGGER trg_contributor_receipt_ack_no_update
BEFORE UPDATE ON contributor_receipt_acknowledgements
BEGIN
    SELECT RAISE(
        ABORT,
        'Contributor receipt acknowledgement is immutable'
    );
END;

CREATE TRIGGER trg_contributor_receipt_ack_no_delete
BEFORE DELETE ON contributor_receipt_acknowledgements
BEGIN
    SELECT RAISE(
        ABORT,
        'Contributor receipt acknowledgement cannot be deleted'
    );
END;

UPDATE schema_version
SET version = 43
WHERE version = 42;
