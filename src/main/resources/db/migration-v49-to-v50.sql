-- Schema v50: detached contributor terminal evidence and recoverable
-- Contribution publication attempts.
--
-- Historical return rows remain append-only and retain their established
-- (assignment_id, return_number) lineage. New runtime publications are limited
-- to one final Contribution without discarding historical multiple-return
-- evidence. No contributor corpus row or managed asset is deleted here.

DROP TRIGGER trg_operating_context_hidden_after_return;
DROP TRIGGER trg_contributor_receipt_ack_validate;
DROP TRIGGER trg_contributor_receipt_ack_no_update;
DROP TRIGGER trg_contributor_receipt_ack_no_delete;
DROP TRIGGER trg_contributor_returns_no_delete;
DROP TRIGGER trg_contributor_returns_no_update;

CREATE TABLE contributor_return_packages_v50 (
    package_id TEXT NOT NULL PRIMARY KEY,
    assignment_id TEXT NOT NULL,
    return_number INTEGER NOT NULL,
    previous_package_id TEXT,
    baseline_snapshot_id TEXT NOT NULL,
    master_installation_id TEXT NOT NULL,
    contributor_id TEXT NOT NULL,
    origin_installation_id TEXT NOT NULL,
    archive_sha256 TEXT NOT NULL,
    recorded_at TEXT NOT NULL,

    assignment_package_id TEXT NOT NULL,
    assignment_archive_sha256 TEXT NOT NULL,
    subject_portable_id TEXT NOT NULL,
    exam_portable_id TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    installed_at TEXT NOT NULL,
    contribution_created_at TEXT,

    FOREIGN KEY (previous_package_id)
        REFERENCES contributor_return_packages_v50(package_id)
        ON DELETE RESTRICT,

    UNIQUE (assignment_id, return_number),

    CHECK (length(package_id) = 36),
    CHECK (length(assignment_id) = 36),
    CHECK (return_number > 0),
    CHECK (
        previous_package_id IS NULL
        OR length(previous_package_id) = 36
    ),
    CHECK (length(baseline_snapshot_id) = 36),
    CHECK (length(master_installation_id) = 36),
    CHECK (length(contributor_id) = 36),
    CHECK (length(origin_installation_id) = 36),
    CHECK (length(assignment_package_id) = 36),
    CHECK (length(subject_portable_id) = 36),
    CHECK (length(exam_portable_id) = 36),
    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(assignment_archive_sha256) = 64
        AND assignment_archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (length(recorded_at) > 0),
    CHECK (length(issued_at) > 0),
    CHECK (length(installed_at) > 0),
    CHECK (
        contribution_created_at IS NULL
        OR length(contribution_created_at) > 0
    )
);

INSERT INTO contributor_return_packages_v50 (
    package_id,
    assignment_id,
    return_number,
    previous_package_id,
    baseline_snapshot_id,
    master_installation_id,
    contributor_id,
    origin_installation_id,
    archive_sha256,
    recorded_at,
    assignment_package_id,
    assignment_archive_sha256,
    subject_portable_id,
    exam_portable_id,
    issued_at,
    installed_at,
    contribution_created_at
)
SELECT returned.package_id,
       returned.assignment_id,
       returned.return_number,
       returned.previous_package_id,
       returned.baseline_snapshot_id,
       returned.master_installation_id,
       returned.contributor_id,
       returned.origin_installation_id,
       returned.archive_sha256,
       returned.recorded_at,
       session.package_id,
       session.archive_sha256,
       session.subject_portable_id,
       session.exam_portable_id,
       session.issued_at,
       session.installed_at,
       NULL
FROM contributor_return_packages returned
JOIN contributor_assignment_sessions session
  ON session.assignment_id = returned.assignment_id;

CREATE TABLE migration_v50_return_count_guard (
    source_count INTEGER NOT NULL,
    migrated_count INTEGER NOT NULL,
    CHECK (source_count = migrated_count)
);

INSERT INTO migration_v50_return_count_guard (
    source_count,
    migrated_count
)
SELECT (
           SELECT COUNT(*)
           FROM contributor_return_packages
       ),
       (
           SELECT COUNT(*)
           FROM contributor_return_packages_v50
       );

DROP TABLE migration_v50_return_count_guard;

CREATE TABLE contributor_receipt_acknowledgements_v50 (
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
        REFERENCES contributor_return_packages_v50(package_id)
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

INSERT INTO contributor_receipt_acknowledgements_v50 (
    receipt_package_id,
    return_package_id,
    assignment_id,
    review_id,
    master_installation_id,
    archive_sha256,
    outcomes_jsonl,
    master_records_jsonl,
    acknowledged_at
)
SELECT receipt_package_id,
       return_package_id,
       assignment_id,
       review_id,
       master_installation_id,
       archive_sha256,
       outcomes_jsonl,
       master_records_jsonl,
       acknowledged_at
FROM contributor_receipt_acknowledgements;

CREATE TABLE migration_v50_receipt_count_guard (
    source_count INTEGER NOT NULL,
    migrated_count INTEGER NOT NULL,
    CHECK (source_count = migrated_count)
);

INSERT INTO migration_v50_receipt_count_guard (
    source_count,
    migrated_count
)
SELECT (
           SELECT COUNT(*)
           FROM contributor_receipt_acknowledgements
       ),
       (
           SELECT COUNT(*)
           FROM contributor_receipt_acknowledgements_v50
       );

DROP TABLE migration_v50_receipt_count_guard;

DROP TABLE contributor_receipt_acknowledgements;

-- The replacement table already contains the verified lineage. Break only the
-- obsolete source table's self-references so SQLite can perform its implicit
-- row deletion while dropping that table with foreign keys enabled.
UPDATE contributor_return_packages
SET previous_package_id = NULL
WHERE previous_package_id IS NOT NULL;

DROP TABLE contributor_return_packages;

ALTER TABLE contributor_return_packages_v50
RENAME TO contributor_return_packages;

ALTER TABLE contributor_receipt_acknowledgements_v50
RENAME TO contributor_receipt_acknowledgements;

CREATE INDEX idx_contributor_return_packages_assignment
ON contributor_return_packages (
    assignment_id,
    return_number
);

CREATE TRIGGER trg_contributor_returns_no_delete
BEFORE DELETE ON contributor_return_packages
BEGIN
    SELECT RAISE(
        ABORT,
        'Contributor return evidence cannot be deleted'
    );
END;

CREATE TRIGGER trg_contributor_returns_no_update
BEFORE UPDATE ON contributor_return_packages
BEGIN
    SELECT RAISE(
        ABORT,
        'Contributor return evidence cannot be modified'
    );
END;

-- Existing assignments may already have multiple historical returns. The
-- migration copies those rows before installing this new-publication guard.

CREATE TRIGGER trg_contributor_returns_single_final_insert
BEFORE INSERT ON contributor_return_packages
WHEN EXISTS (
    SELECT 1
    FROM contributor_return_packages existing
    WHERE existing.assignment_id = NEW.assignment_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'A final Contribution already exists for this assignment'
    );
END;

CREATE TRIGGER trg_contributor_receipt_ack_validate
BEFORE INSERT ON contributor_receipt_acknowledgements
WHEN NOT EXISTS (
    SELECT 1
    FROM contributor_return_packages returned
    WHERE returned.package_id = NEW.return_package_id
      AND returned.assignment_id = NEW.assignment_id
      AND returned.master_installation_id =
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

CREATE TABLE contribution_publication_attempts (
    assignment_id TEXT NOT NULL PRIMARY KEY,
    package_id TEXT NOT NULL UNIQUE,
    archive_sha256 TEXT NOT NULL,
    contribution_created_at TEXT NOT NULL,
    destination_path TEXT NOT NULL,
    staging_path TEXT NOT NULL,
    state TEXT NOT NULL,
    selection_was_active INTEGER NOT NULL,
    prepared_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (assignment_id)
        REFERENCES contributor_assignment_sessions(assignment_id)
        ON DELETE RESTRICT,

    CHECK (length(assignment_id) = 36),
    CHECK (length(package_id) = 36),
    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (length(contribution_created_at) > 0),
    CHECK (length(destination_path) > 0),
    CHECK (length(staging_path) > 0),
    CHECK (destination_path <> staging_path),
    CHECK (state IN ('PREPARED', 'PUBLISHING', 'PUBLISHED')),
    CHECK (selection_was_active IN (0, 1)),
    CHECK (length(prepared_at) > 0),
    CHECK (length(updated_at) > 0)
);

CREATE TRIGGER trg_contribution_publication_attempt_insert_validate
BEFORE INSERT ON contribution_publication_attempts
WHEN NEW.state <> 'PREPARED'
    OR EXISTS (
        SELECT 1
        FROM contributor_return_packages returned
        WHERE returned.assignment_id = NEW.assignment_id
    )
    OR EXISTS (
        SELECT 1
        FROM contributor_assignment_hidden hidden
        WHERE hidden.assignment_id = NEW.assignment_id
    )
BEGIN
    SELECT RAISE(
        ABORT,
        'Contribution publication attempt is not eligible'
    );
END;

CREATE TRIGGER trg_contribution_publication_attempt_evidence_immutable
BEFORE UPDATE ON contribution_publication_attempts
WHEN NEW.assignment_id IS NOT OLD.assignment_id
    OR NEW.package_id IS NOT OLD.package_id
    OR NEW.archive_sha256 IS NOT OLD.archive_sha256
    OR NEW.contribution_created_at IS NOT OLD.contribution_created_at
    OR NEW.destination_path IS NOT OLD.destination_path
    OR NEW.staging_path IS NOT OLD.staging_path
    OR NEW.selection_was_active IS NOT OLD.selection_was_active
    OR NEW.prepared_at IS NOT OLD.prepared_at
BEGIN
    SELECT RAISE(
        ABORT,
        'Contribution publication attempt evidence is immutable'
    );
END;

CREATE TRIGGER trg_contribution_publication_attempt_state_transition
BEFORE UPDATE ON contribution_publication_attempts
WHEN NOT (
    (OLD.state = 'PREPARED' AND NEW.state = 'PUBLISHING')
    OR (OLD.state = 'PUBLISHING' AND NEW.state = 'PUBLISHED')
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Invalid Contribution publication state transition'
    );
END;

CREATE TRIGGER trg_contribution_publication_attempt_delete_guard
BEFORE DELETE ON contribution_publication_attempts
WHEN OLD.state = 'PUBLISHING'
    OR (
        OLD.state = 'PUBLISHED'
        AND NOT EXISTS (
            SELECT 1
            FROM contributor_return_packages returned
            WHERE returned.assignment_id = OLD.assignment_id
              AND returned.package_id = OLD.package_id
              AND returned.archive_sha256 = OLD.archive_sha256
        )
    )
BEGIN
    SELECT RAISE(
        ABORT,
        'Unresolved Contribution publication attempt cannot be deleted'
    );
END;

CREATE TRIGGER trg_operating_context_hidden_after_return
BEFORE INSERT ON contributor_assignment_hidden
WHEN EXISTS (
    SELECT 1
    FROM contributor_return_packages packet
    WHERE packet.assignment_id = NEW.assignment_id
)
OR EXISTS (
    SELECT 1
    FROM contribution_publication_attempts attempt
    WHERE attempt.assignment_id = NEW.assignment_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Submitted contributor assignment cannot be removed'
    );
END;

UPDATE schema_version
SET version = 50
WHERE version = 49;
