-- Schema 25 introduces installation and portable entity identities.
-- The Java migration performs UUID generation and backfilling before
-- updating schema_version, within the existing migration transaction.

CREATE TABLE installation_identity (
    singleton_id INTEGER PRIMARY KEY
        CHECK (singleton_id = 1),
    installation_id TEXT NOT NULL UNIQUE
        CHECK (
            length(installation_id) = 36
            AND substr(installation_id, 9, 1) = '-'
            AND substr(installation_id, 14, 1) = '-'
            AND substr(installation_id, 19, 1) = '-'
            AND substr(installation_id, 24, 1) = '-'
            AND length(replace(installation_id, '-', '')) = 32
            AND replace(installation_id, '-', '')
                NOT GLOB '*[^0-9a-f]*'
        )
);

CREATE TABLE portable_entity_identities (
    entity_type TEXT NOT NULL
        CHECK (
            entity_type IN (
                'SUBJECT',
                'SYLLABUS_VERSION',
                'CURRICULUM_NODE',
                'EXAM_PROVIDER',
                'SOURCE_DOCUMENT',
                'EXAM',
                'EXAM_BOOKLET',
                'QUESTION',
                'ANSWER_FILE',
                'ANSWER',
                'SOURCE_QUESTION',
                'SHARED_QUESTION_CONTEXT',
                'CURRICULUM_MAPPING',
                'QUESTION_IMAGE'
            )
        ),

    local_id INTEGER NOT NULL,

    portable_id TEXT NOT NULL UNIQUE
        CHECK (
            length(portable_id) = 36
            AND substr(portable_id, 9, 1) = '-'
            AND substr(portable_id, 14, 1) = '-'
            AND substr(portable_id, 19, 1) = '-'
            AND substr(portable_id, 24, 1) = '-'
            AND length(replace(portable_id, '-', '')) = 32
            AND replace(portable_id, '-', '')
                NOT GLOB '*[^0-9a-f]*'
        ),

    revision_id TEXT NOT NULL UNIQUE
        CHECK (
            length(revision_id) = 36
            AND substr(revision_id, 9, 1) = '-'
            AND substr(revision_id, 14, 1) = '-'
            AND substr(revision_id, 19, 1) = '-'
            AND substr(revision_id, 24, 1) = '-'
            AND length(replace(revision_id, '-', '')) = 32
            AND replace(revision_id, '-', '')
                NOT GLOB '*[^0-9a-f]*'
        ),

    retired INTEGER NOT NULL DEFAULT 0
        CHECK (retired IN (0, 1)),

    PRIMARY KEY (entity_type, local_id)
);

-- Retired identities remain permanently distinguishable even when
-- SQLite subsequently reuses their old integer primary keys.

CREATE TABLE portable_entity_retirements (
    portable_id TEXT NOT NULL PRIMARY KEY,
    entity_type TEXT NOT NULL,
    local_id INTEGER NOT NULL,
    revision_id TEXT NOT NULL UNIQUE,
    retired_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Origin aliases will be populated only after coordinator approval.
-- The mapping is deliberately not inferred during migration.

CREATE TABLE portable_identity_aliases (
    origin_installation_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    origin_portable_id TEXT NOT NULL,
    canonical_portable_id TEXT NOT NULL,
    approval_note TEXT NOT NULL
        CHECK (length(trim(approval_note)) > 0),
    approved_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (
        origin_installation_id,
        entity_type,
        origin_portable_id
    ),

    CHECK (length(origin_installation_id) = 36),
    CHECK (length(origin_portable_id) = 36),
    CHECK (length(canonical_portable_id) = 36)
);

CREATE INDEX idx_portable_identity_aliases_canonical
ON portable_identity_aliases (
    entity_type,
    canonical_portable_id
);
