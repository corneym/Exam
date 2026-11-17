-- Schema v34: durable, append-only contributor return history.
-- Return records are separate from immutable assignment installations.
-- Each return after the first identifies the preceding return package.

CREATE TABLE contributor_return_packages (
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

    FOREIGN KEY (assignment_id)
        REFERENCES contributor_assignment_sessions(assignment_id)
        ON DELETE RESTRICT,

    FOREIGN KEY (previous_package_id)
        REFERENCES contributor_return_packages(package_id)
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
    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (length(recorded_at) > 0)
);

CREATE INDEX idx_contributor_return_packages_assignment
ON contributor_return_packages (
    assignment_id,
    return_number
);

UPDATE schema_version
SET version = 34
WHERE version = 33;
