
-- Schema v29: contributor-side assignment installations.
-- Separate from the coordinator's immutable issuance ledger.

CREATE TABLE contributor_assignment_sessions (
    assignment_id TEXT NOT NULL PRIMARY KEY,
    package_id TEXT NOT NULL UNIQUE,
    baseline_snapshot_id TEXT NOT NULL UNIQUE,
    master_installation_id TEXT NOT NULL,

    subject_portable_id TEXT NOT NULL,
    exam_portable_id TEXT NOT NULL,
    current_syllabus_portable_id TEXT NOT NULL,
    legacy_syllabus_portable_id TEXT,

    contributor_id TEXT NOT NULL,
    contributor_name TEXT NOT NULL,

    subject_local_id INTEGER NOT NULL,
    exam_local_id INTEGER NOT NULL,
    current_syllabus_local_id INTEGER NOT NULL,
    legacy_syllabus_local_id INTEGER,

    issued_at TEXT NOT NULL,
    installed_at TEXT NOT NULL,

    archive_sha256 TEXT NOT NULL,
    records_sha256 TEXT NOT NULL,
    assets_sha256 TEXT NOT NULL,

    baseline_records BLOB NOT NULL,
    baseline_assets BLOB NOT NULL,

    FOREIGN KEY (subject_local_id)
        REFERENCES subjects(id) ON DELETE RESTRICT,
    FOREIGN KEY (exam_local_id)
        REFERENCES exams(id) ON DELETE RESTRICT,
    FOREIGN KEY (current_syllabus_local_id)
        REFERENCES syllabus_versions(id) ON DELETE RESTRICT,
    FOREIGN KEY (legacy_syllabus_local_id)
        REFERENCES syllabus_versions(id) ON DELETE RESTRICT,

    CHECK (length(assignment_id) = 36),
    CHECK (length(package_id) = 36),
    CHECK (length(baseline_snapshot_id) = 36),
    CHECK (length(master_installation_id) = 36),
    CHECK (length(subject_portable_id) = 36),
    CHECK (length(exam_portable_id) = 36),
    CHECK (length(current_syllabus_portable_id) = 36),
    CHECK (
        legacy_syllabus_portable_id IS NULL
        OR length(legacy_syllabus_portable_id) = 36
    ),
    CHECK (length(contributor_id) = 36),
    CHECK (
        length(trim(contributor_name)) BETWEEN 1 AND 256
    ),
    CHECK (
        (legacy_syllabus_local_id IS NULL)
        = (legacy_syllabus_portable_id IS NULL)
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
    CHECK (
        length(baseline_assets) <= 67108864
    )
);

-- Exactly one durable active-selection slot per installation.
-- A NULL assignment means normal, unrestricted application mode.

CREATE TABLE contributor_assignment_selection (
    singleton_id INTEGER NOT NULL PRIMARY KEY
        CHECK (singleton_id = 1),
    assignment_id TEXT,
    updated_at TEXT NOT NULL,

    FOREIGN KEY (assignment_id)
        REFERENCES contributor_assignment_sessions(assignment_id)
        ON DELETE RESTRICT
);

INSERT INTO contributor_assignment_selection (
    singleton_id,
    assignment_id,
    updated_at
)
VALUES (
    1,
    NULL,
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
);

CREATE INDEX idx_contributor_assignment_sessions_exam
ON contributor_assignment_sessions (
    subject_portable_id,
    exam_portable_id,
    installed_at
);

UPDATE schema_version
SET version = 29
WHERE version = 28;
