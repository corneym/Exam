-- Schema v52: recoverable contributor-corpus disposal planning and complete
-- portable revision history. This migration records no disposal operation and
-- deletes no contributor corpus row or managed file.

CREATE TABLE corpus_disposal_durable_states (
    state TEXT NOT NULL PRIMARY KEY
        CHECK (state IN ('PLANNED', 'DATABASE_RELEASED', 'VERIFIED'))
);

INSERT INTO corpus_disposal_durable_states (state)
VALUES ('PLANNED'), ('DATABASE_RELEASED'), ('VERIFIED');

CREATE TRIGGER trg_corpus_disposal_durable_states_no_insert
BEFORE INSERT ON corpus_disposal_durable_states
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal durable states are fixed');
END;

CREATE TRIGGER trg_corpus_disposal_durable_states_no_update
BEFORE UPDATE ON corpus_disposal_durable_states
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal durable states are fixed');
END;

CREATE TRIGGER trg_corpus_disposal_durable_states_no_delete
BEFORE DELETE ON corpus_disposal_durable_states
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal durable states are fixed');
END;

CREATE TABLE corpus_disposal_operations (
    operation_id TEXT NOT NULL PRIMARY KEY,
    policy TEXT NOT NULL,
    policy_version INTEGER NOT NULL,
    scope_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    terminal_return_package_id TEXT UNIQUE,
    assignment_package_id TEXT NOT NULL,
    assignment_archive_sha256 TEXT NOT NULL,
    plan_sha256 TEXT NOT NULL,
    state TEXT NOT NULL,
    planned_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    database_released_at TEXT,
    verified_at TEXT,

    FOREIGN KEY (terminal_return_package_id)
        REFERENCES contributor_return_packages(package_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (state)
        REFERENCES corpus_disposal_durable_states(state)
        DEFERRABLE INITIALLY DEFERRED,

    UNIQUE (policy, scope_id),

    CHECK (length(operation_id) = 36),
    CHECK (policy = 'CONTRIBUTOR_ASSIGNMENT'),
    CHECK (policy_version = 1),
    CHECK (length(scope_id) = 36),
    CHECK (reason IN ('SUBMITTED', 'REMOVED_UNCHANGED')),
    CHECK (
        (reason = 'SUBMITTED' AND length(terminal_return_package_id) = 36)
        OR (reason = 'REMOVED_UNCHANGED' AND terminal_return_package_id IS NULL)
    ),
    CHECK (length(assignment_package_id) = 36),
    CHECK (
        length(assignment_archive_sha256) = 64
        AND assignment_archive_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        length(plan_sha256) = 64
        AND plan_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (
        state IN (
            'PLANNED',
            'DATABASE_RELEASING',
            'DATABASE_RELEASED',
            'VERIFIED'
        )
    ),
    CHECK (length(planned_at) > 0),
    CHECK (length(updated_at) > 0),
    CHECK (
        (state IN ('PLANNED', 'DATABASE_RELEASING')
            AND database_released_at IS NULL
            AND verified_at IS NULL)
        OR (state = 'DATABASE_RELEASED'
            AND database_released_at IS NOT NULL
            AND verified_at IS NULL)
        OR (state = 'VERIFIED'
            AND database_released_at IS NOT NULL
            AND verified_at IS NOT NULL)
    )
);

CREATE TABLE portable_revision_history (
    revision_id TEXT NOT NULL PRIMARY KEY,
    portable_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    first_observed_at TEXT NOT NULL,

    CHECK (length(revision_id) = 36),
    CHECK (length(portable_id) = 36),
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
    CHECK (length(first_observed_at) > 0)
);

CREATE INDEX idx_portable_revision_history_entity
ON portable_revision_history (portable_id, entity_type);

INSERT INTO portable_revision_history (
    revision_id,
    portable_id,
    entity_type,
    first_observed_at
)
SELECT revision_id,
       portable_id,
       entity_type,
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM portable_entity_identities;

INSERT INTO portable_revision_history (
    revision_id,
    portable_id,
    entity_type,
    first_observed_at
)
SELECT revision_id,
       portable_id,
       entity_type,
       retired_at
FROM portable_entity_retirements;

CREATE TABLE corpus_disposal_entities (
    operation_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    local_id INTEGER NOT NULL,
    portable_id TEXT NOT NULL,
    revision_id TEXT NOT NULL,
    authority_installation_id TEXT NOT NULL,
    reattachable INTEGER NOT NULL,

    PRIMARY KEY (operation_id, entity_type, local_id),

    FOREIGN KEY (operation_id)
        REFERENCES corpus_disposal_operations(operation_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (revision_id)
        REFERENCES portable_revision_history(revision_id)
        ON DELETE RESTRICT,

    UNIQUE (operation_id, portable_id),

    CHECK (local_id > 0),
    CHECK (length(portable_id) = 36),
    CHECK (length(revision_id) = 36),
    CHECK (length(authority_installation_id) = 36),
    CHECK (reattachable IN (0, 1)),
    CHECK (
        entity_type IN (
            'SYLLABUS_VERSION',
            'CURRICULUM_NODE',
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
    )
);

CREATE TABLE corpus_disposal_retired_entities (
    operation_id TEXT NOT NULL,
    portable_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    former_local_id INTEGER NOT NULL,
    latest_revision_id TEXT NOT NULL,
    authority_installation_id TEXT NOT NULL,

    PRIMARY KEY (operation_id, portable_id),

    FOREIGN KEY (operation_id)
        REFERENCES corpus_disposal_operations(operation_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (latest_revision_id)
        REFERENCES portable_revision_history(revision_id)
        ON DELETE RESTRICT,

    CHECK (former_local_id > 0),
    CHECK (length(portable_id) = 36),
    CHECK (length(latest_revision_id) = 36),
    CHECK (length(authority_installation_id) = 36),
    CHECK (
        entity_type IN (
            'SYLLABUS_VERSION',
            'CURRICULUM_NODE',
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
    )
);

CREATE TABLE corpus_disposal_assets (
    operation_id TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    content_sha256 TEXT NOT NULL,
    asset_kind TEXT NOT NULL,

    PRIMARY KEY (operation_id, relative_path),

    FOREIGN KEY (operation_id)
        REFERENCES corpus_disposal_operations(operation_id)
        ON DELETE RESTRICT,

    CHECK (length(trim(relative_path)) > 0),
    CHECK (
        length(content_sha256) = 64
        AND content_sha256 NOT GLOB '*[^0-9a-f]*'
    ),
    CHECK (asset_kind IN ('SOURCE_DOCUMENT', 'CURRICULUM_SOURCE'))
);

CREATE TABLE portable_entity_cache_evictions (
    operation_id TEXT NOT NULL,
    portable_id TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    former_local_id INTEGER NOT NULL,
    latest_revision_id TEXT NOT NULL,
    authority_installation_id TEXT NOT NULL,
    reattachable INTEGER NOT NULL,
    evicted_at TEXT NOT NULL,

    PRIMARY KEY (operation_id, portable_id),

    FOREIGN KEY (operation_id)
        REFERENCES corpus_disposal_operations(operation_id)
        ON DELETE RESTRICT,
    FOREIGN KEY (latest_revision_id)
        REFERENCES portable_revision_history(revision_id)
        ON DELETE RESTRICT,

    CHECK (former_local_id > 0),
    CHECK (length(portable_id) = 36),
    CHECK (length(latest_revision_id) = 36),
    CHECK (length(authority_installation_id) = 36),
    CHECK (reattachable IN (0, 1)),
    CHECK (length(evicted_at) > 0)
);

CREATE INDEX idx_portable_entity_cache_evictions_identity
ON portable_entity_cache_evictions (portable_id, entity_type, evicted_at);

CREATE TRIGGER app_portable_revision_history_insert
AFTER INSERT ON portable_entity_identities
BEGIN
    SELECT RAISE(ABORT, 'Portable revision belongs to another entity')
    WHERE EXISTS (
        SELECT 1
        FROM portable_revision_history history
        WHERE history.revision_id = NEW.revision_id
          AND (
              history.portable_id <> NEW.portable_id
              OR history.entity_type <> NEW.entity_type
          )
    );

    SELECT RAISE(ABORT, 'Portable identity belongs to another entity type')
    WHERE EXISTS (
        SELECT 1
        FROM portable_revision_history history
        WHERE history.portable_id = NEW.portable_id
          AND history.entity_type <> NEW.entity_type
    );

    INSERT INTO portable_revision_history (
        revision_id,
        portable_id,
        entity_type,
        first_observed_at
    )
    SELECT NEW.revision_id,
           NEW.portable_id,
           NEW.entity_type,
           strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (
        SELECT 1
        FROM portable_revision_history history
        WHERE history.revision_id = NEW.revision_id
    );
END;

CREATE TRIGGER app_portable_revision_history_update
BEFORE UPDATE OF portable_id, revision_id
ON portable_entity_identities
WHEN NEW.portable_id IS NOT OLD.portable_id
    OR NEW.revision_id IS NOT OLD.revision_id
BEGIN
    SELECT RAISE(ABORT, 'Portable revision belongs to another entity')
    WHERE EXISTS (
        SELECT 1
        FROM portable_revision_history history
        WHERE history.revision_id = NEW.revision_id
          AND (
              history.portable_id <> NEW.portable_id
              OR history.entity_type <> NEW.entity_type
          )
    );

    SELECT RAISE(ABORT, 'Portable identity belongs to another entity type')
    WHERE EXISTS (
        SELECT 1
        FROM portable_revision_history history
        WHERE history.portable_id = NEW.portable_id
          AND history.entity_type <> NEW.entity_type
    );

    INSERT INTO portable_revision_history (
        revision_id,
        portable_id,
        entity_type,
        first_observed_at
    )
    SELECT NEW.revision_id,
           NEW.portable_id,
           NEW.entity_type,
           strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
    WHERE NOT EXISTS (
        SELECT 1
        FROM portable_revision_history history
        WHERE history.revision_id = NEW.revision_id
    );
END;

CREATE TRIGGER trg_corpus_disposal_operation_insert
BEFORE INSERT ON corpus_disposal_operations
WHEN NEW.state <> 'PLANNED'
    OR NEW.database_released_at IS NOT NULL
    OR NEW.verified_at IS NOT NULL
    OR NOT (
        (NEW.reason = 'SUBMITTED' AND NEW.terminal_return_package_id IS NOT NULL)
        OR (
            NEW.reason = 'REMOVED_UNCHANGED'
            AND NEW.terminal_return_package_id IS NULL
        )
    )
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal operations must start planned');
END;

CREATE TRIGGER trg_corpus_disposal_operation_update
BEFORE UPDATE ON corpus_disposal_operations
WHEN NEW.operation_id IS NOT OLD.operation_id
    OR NEW.policy IS NOT OLD.policy
    OR NEW.policy_version IS NOT OLD.policy_version
    OR NEW.scope_id IS NOT OLD.scope_id
    OR NEW.reason IS NOT OLD.reason
    OR NEW.terminal_return_package_id IS NOT OLD.terminal_return_package_id
    OR NEW.assignment_package_id IS NOT OLD.assignment_package_id
    OR NEW.assignment_archive_sha256 IS NOT OLD.assignment_archive_sha256
    OR NEW.plan_sha256 IS NOT OLD.plan_sha256
    OR NEW.planned_at IS NOT OLD.planned_at
    OR (
        NEW.state = 'DATABASE_RELEASING'
        AND EXISTS (
            SELECT 1
            FROM corpus_disposal_operations other
            WHERE other.state = 'DATABASE_RELEASING'
              AND other.operation_id <> NEW.operation_id
        )
    )
    OR NOT (
        (OLD.state = 'PLANNED' AND NEW.state = 'DATABASE_RELEASING')
        OR (
            OLD.state = 'DATABASE_RELEASING'
            AND NEW.state = 'DATABASE_RELEASED'
        )
        OR (OLD.state = 'DATABASE_RELEASED' AND NEW.state = 'VERIFIED')
    )
BEGIN
    SELECT RAISE(ABORT, 'Invalid corpus disposal operation update');
END;

CREATE TRIGGER trg_corpus_disposal_operation_no_delete
BEFORE DELETE ON corpus_disposal_operations
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal evidence cannot be deleted');
END;

CREATE TRIGGER trg_corpus_disposal_entity_insert
BEFORE INSERT ON corpus_disposal_entities
WHEN NOT EXISTS (
    SELECT 1
    FROM corpus_disposal_operations operation
    WHERE operation.operation_id = NEW.operation_id
      AND operation.state = 'PLANNED'
)
OR NOT EXISTS (
    SELECT 1
    FROM portable_entity_identities identity_row
    WHERE identity_row.entity_type = NEW.entity_type
      AND identity_row.local_id = NEW.local_id
      AND identity_row.portable_id = NEW.portable_id
      AND identity_row.revision_id = NEW.revision_id
      AND identity_row.retired = 0
)
OR NOT EXISTS (
    SELECT 1
    FROM portable_revision_history history
    WHERE history.revision_id = NEW.revision_id
      AND history.portable_id = NEW.portable_id
      AND history.entity_type = NEW.entity_type
)
BEGIN
    SELECT RAISE(ABORT, 'Invalid corpus disposal entity evidence');
END;

CREATE TRIGGER trg_corpus_disposal_entity_no_update
BEFORE UPDATE ON corpus_disposal_entities
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal entity evidence is immutable');
END;

CREATE TRIGGER trg_corpus_disposal_entity_no_delete
BEFORE DELETE ON corpus_disposal_entities
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal entity evidence cannot be deleted');
END;

CREATE TRIGGER trg_corpus_disposal_retired_entity_insert
BEFORE INSERT ON corpus_disposal_retired_entities
WHEN NOT EXISTS (
    SELECT 1
    FROM corpus_disposal_operations operation
    WHERE operation.operation_id = NEW.operation_id
      AND operation.state = 'PLANNED'
)
OR NOT EXISTS (
    SELECT 1
    FROM portable_entity_retirements retired
    WHERE retired.entity_type = NEW.entity_type
      AND retired.local_id = NEW.former_local_id
      AND retired.portable_id = NEW.portable_id
      AND retired.revision_id = NEW.latest_revision_id
)
OR NOT EXISTS (
    SELECT 1
    FROM portable_revision_history history
    WHERE history.revision_id = NEW.latest_revision_id
      AND history.portable_id = NEW.portable_id
      AND history.entity_type = NEW.entity_type
)
BEGIN
    SELECT RAISE(ABORT, 'Invalid corpus disposal retired-entity evidence');
END;

CREATE TRIGGER trg_corpus_disposal_retired_entity_no_update
BEFORE UPDATE ON corpus_disposal_retired_entities
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal retired-entity evidence is immutable');
END;

CREATE TRIGGER trg_corpus_disposal_retired_entity_no_delete
BEFORE DELETE ON corpus_disposal_retired_entities
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal retired-entity evidence cannot be deleted');
END;

CREATE TRIGGER trg_corpus_disposal_asset_insert
BEFORE INSERT ON corpus_disposal_assets
WHEN NOT EXISTS (
    SELECT 1
    FROM corpus_disposal_operations operation
    WHERE operation.operation_id = NEW.operation_id
      AND operation.state = 'PLANNED'
)
BEGIN
    SELECT RAISE(ABORT, 'Invalid corpus disposal asset evidence');
END;

CREATE TRIGGER trg_corpus_disposal_asset_no_update
BEFORE UPDATE ON corpus_disposal_assets
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal asset evidence is immutable');
END;

CREATE TRIGGER trg_corpus_disposal_asset_no_delete
BEFORE DELETE ON corpus_disposal_assets
BEGIN
    SELECT RAISE(ABORT, 'Corpus disposal asset evidence cannot be deleted');
END;

CREATE TRIGGER trg_portable_cache_eviction_insert
BEFORE INSERT ON portable_entity_cache_evictions
WHEN NOT (
    EXISTS (
    SELECT 1
    FROM corpus_disposal_operations operation
    JOIN corpus_disposal_entities entity
      ON entity.operation_id = operation.operation_id
    WHERE operation.operation_id = NEW.operation_id
      AND operation.state = 'DATABASE_RELEASING'
      AND entity.portable_id = NEW.portable_id
      AND entity.entity_type = NEW.entity_type
      AND entity.local_id = NEW.former_local_id
      AND entity.authority_installation_id = NEW.authority_installation_id
      AND entity.reattachable = NEW.reattachable
      AND EXISTS (
          SELECT 1
          FROM portable_revision_history history
          WHERE history.revision_id = NEW.latest_revision_id
            AND history.portable_id = NEW.portable_id
            AND history.entity_type = NEW.entity_type
      )
    )
    OR EXISTS (
        SELECT 1
        FROM corpus_disposal_operations operation
        JOIN corpus_disposal_retired_entities retired
          ON retired.operation_id = operation.operation_id
        WHERE operation.operation_id = NEW.operation_id
          AND operation.state = 'DATABASE_RELEASING'
          AND retired.portable_id = NEW.portable_id
          AND retired.entity_type = NEW.entity_type
          AND retired.former_local_id = NEW.former_local_id
          AND retired.latest_revision_id = NEW.latest_revision_id
          AND retired.authority_installation_id = NEW.authority_installation_id
          AND NEW.reattachable = 1
    )
)
BEGIN
    SELECT RAISE(ABORT, 'Invalid portable cache-eviction evidence');
END;

CREATE TRIGGER trg_portable_cache_eviction_no_update
BEFORE UPDATE ON portable_entity_cache_evictions
BEGIN
    SELECT RAISE(ABORT, 'Portable cache-eviction evidence is immutable');
END;

CREATE TRIGGER trg_portable_cache_eviction_no_delete
BEFORE DELETE ON portable_entity_cache_evictions
BEGIN
    SELECT RAISE(ABORT, 'Portable cache-eviction evidence cannot be deleted');
END;

DROP TRIGGER trg_contributor_assignment_session_no_delete;

CREATE TRIGGER trg_contributor_assignment_session_no_delete
BEFORE DELETE ON contributor_assignment_sessions
WHEN NOT EXISTS (
    SELECT 1
    FROM corpus_disposal_operations operation
    WHERE operation.policy = 'CONTRIBUTOR_ASSIGNMENT'
      AND operation.scope_id = OLD.assignment_id
      AND operation.state = 'DATABASE_RELEASING'
)
BEGIN
    SELECT RAISE(ABORT, 'Installed assignment evidence cannot be deleted');
END;

UPDATE schema_version
SET version = 52
WHERE version = 51;
