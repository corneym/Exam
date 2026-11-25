
-- Schema v48: operating-context authority and contributor assignment visibility.
--
-- A Subject without an imported-authority record remains locally authoritative.
-- Imported authority is explicit; existing Subjects are not silently reclassified.
--
-- Contributor submission is already recorded in contributor_return_packages.
-- A local removal therefore needs only a visibility record, not another
-- independent assignment state machine.

CREATE TABLE imported_subject_authorities (
    subject_local_id INTEGER PRIMARY KEY
        REFERENCES subjects(id) ON DELETE RESTRICT,

    origin_installation_id TEXT NOT NULL,
    origin_subject_portable_id TEXT NOT NULL,
    latest_package_id TEXT NOT NULL
        REFERENCES subject_import_packages(package_id) ON DELETE RESTRICT,
    archive_sha256 TEXT NOT NULL,
    imported_at TEXT NOT NULL,

    UNIQUE (origin_installation_id, origin_subject_portable_id),

    CHECK (length(origin_installation_id) = 36),
    CHECK (length(origin_subject_portable_id) = 36),
    CHECK (length(latest_package_id) = 36),
    CHECK (
        length(archive_sha256) = 64
        AND archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (length(imported_at) > 0)
);

CREATE TABLE contributor_assignment_hidden (
    assignment_id TEXT NOT NULL PRIMARY KEY
        REFERENCES contributor_assignment_sessions(assignment_id)
        ON DELETE RESTRICT,

    removed_at TEXT NOT NULL,

    CHECK (length(removed_at) > 0)
);

-- Authority cannot be silently transferred to a different installation
-- or reassigned to another Subject.

CREATE TRIGGER trg_operating_context_authority_origin_immutable
BEFORE UPDATE ON imported_subject_authorities
WHEN NEW.subject_local_id IS NOT OLD.subject_local_id
    OR NEW.origin_installation_id IS NOT OLD.origin_installation_id
    OR NEW.origin_subject_portable_id IS NOT OLD.origin_subject_portable_id
BEGIN
    SELECT RAISE(
        ABORT,
        'Imported Subject authority cannot be reassigned'
    );
END;

CREATE TRIGGER trg_operating_context_authority_no_delete
BEFORE DELETE ON imported_subject_authorities
BEGIN
    SELECT RAISE(
        ABORT,
        'Imported Subject authority cannot be silently removed'
    );
END;

-- Submission is permanent for this assignment. A published contribution
-- cannot subsequently be treated as an abandoned, removable assignment.

CREATE TRIGGER trg_operating_context_hidden_after_return
BEFORE INSERT ON contributor_assignment_hidden
WHEN EXISTS (
    SELECT 1
    FROM contributor_return_packages packet
    WHERE packet.assignment_id = NEW.assignment_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Submitted contributor assignment cannot be removed'
    );
END;

UPDATE schema_version
SET version = 48
WHERE version = 47;
