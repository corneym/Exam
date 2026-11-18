-- Schema v38: one active coordinator assignment per Exam,
-- durable assignment closure evidence, and automatic release
-- of the assignment claim when a closure is committed.

CREATE TABLE exam_work_assignment_exam_locks (
    exam_portable_id TEXT NOT NULL PRIMARY KEY,
    assignment_id TEXT NOT NULL UNIQUE,

    FOREIGN KEY (assignment_id)
        REFERENCES exam_work_assignment_states(assignment_id)
        ON DELETE RESTRICT,

    CHECK (length(exam_portable_id) = 36),
    CHECK (length(assignment_id) = 36)
);

-- Preserve existing active assignments. If historical data
-- contains multiple active assignments for one Exam, the
-- uniqueness constraint fails the migration rather than
-- silently choosing which assignment remains active.
INSERT INTO exam_work_assignment_exam_locks (
    exam_portable_id,
    assignment_id
)
SELECT
    assignments.exam_portable_id,
    states.assignment_id
FROM exam_work_assignment_states states
JOIN exam_work_assignments assignments
    ON assignments.assignment_id = states.assignment_id
WHERE states.state = 'ACTIVE';

CREATE TABLE exam_work_assignment_closures (
    assignment_id TEXT NOT NULL PRIMARY KEY,
    closed_state TEXT NOT NULL
        CHECK (closed_state IN ('COMPLETE', 'CANCELLED')),
    closure_kind TEXT NOT NULL
        CHECK (
            closure_kind IN (
                'WITHOUT_RETURN',
                'RECONCILED_RETURN',
                'LEGACY'
            )
        ),
    reason TEXT NOT NULL,
    closed_at TEXT NOT NULL,
    return_package_id TEXT,

    FOREIGN KEY (assignment_id)
        REFERENCES exam_work_assignments(assignment_id)
        ON DELETE RESTRICT,

    FOREIGN KEY (return_package_id)
        REFERENCES contribution_intake_packages(package_id)
        ON DELETE RESTRICT,

    CHECK (length(assignment_id) = 36),
    CHECK (length(trim(reason)) BETWEEN 1 AND 1024),
    CHECK (length(closed_at) > 0),

    CHECK (
        (
            closure_kind = 'WITHOUT_RETURN'
            AND closed_state = 'CANCELLED'
            AND return_package_id IS NULL
        )
        OR
        (
            closure_kind = 'RECONCILED_RETURN'
            AND closed_state = 'COMPLETE'
            AND return_package_id IS NOT NULL
        )
        OR
        (
            closure_kind = 'LEGACY'
            AND return_package_id IS NULL
        )
    )
);

-- Preserve terminal states recorded before closure auditing
-- existed. Do not invent a contributor return for them.
INSERT INTO exam_work_assignment_closures (
    assignment_id,
    closed_state,
    closure_kind,
    reason,
    closed_at
)
SELECT
    assignment_id,
    state,
    'LEGACY',
    'Closed before assignment closure auditing was introduced',
    updated_at
FROM exam_work_assignment_states
WHERE state IN ('COMPLETE', 'CANCELLED');

-- A registered assignment claims its Exam when its ACTIVE
-- lifecycle row is inserted.
CREATE TRIGGER trg_master_assignment_state_claims_exam
AFTER INSERT ON exam_work_assignment_states
BEGIN
    INSERT INTO exam_work_assignment_exam_locks (
        exam_portable_id,
        assignment_id
    )
    SELECT
        assignments.exam_portable_id,
        NEW.assignment_id
    FROM exam_work_assignments assignments
    WHERE assignments.assignment_id = NEW.assignment_id;
END;

-- Do not issue another assignment for an Exam already marked
-- COMPLETE in the authoritative master.
CREATE TRIGGER trg_master_assignment_reject_completed_exam
BEFORE INSERT ON exam_work_assignment_states
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignments assignments
    JOIN portable_entity_identities identities
        ON identities.portable_id = assignments.exam_portable_id
       AND identities.entity_type = 'EXAM'
    JOIN exams
        ON exams.id = identities.local_id
    WHERE assignments.assignment_id = NEW.assignment_id
      AND exams.capture_state = 'COMPLETE'
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Completed Exam cannot be assigned'
    );
END;

-- A state change cannot bypass the closure audit.
CREATE TRIGGER trg_master_assignment_state_requires_closure
BEFORE UPDATE ON exam_work_assignment_states
WHEN NOT EXISTS (
    SELECT 1
    FROM exam_work_assignment_closures closures
    WHERE closures.assignment_id = OLD.assignment_id
      AND closures.closed_state = NEW.state
)
BEGIN
    SELECT RAISE(
        ABORT,
        'An audited assignment closure is required'
    );
END;

-- State transitions release their Exam claim. A missing lock
-- is evidence of inconsistent lifecycle state, not success.
CREATE TRIGGER trg_master_assignment_state_releases_exam
AFTER UPDATE OF state ON exam_work_assignment_states
WHEN OLD.state = 'ACTIVE'
 AND NEW.state IN ('COMPLETE', 'CANCELLED')
BEGIN
    DELETE FROM exam_work_assignment_exam_locks
    WHERE assignment_id = NEW.assignment_id;

    -- An absent lock must abort the entire state transition.
    SELECT RAISE(
        ABORT,
        'Assignment Exam lock was missing'
    )
    WHERE changes() <> 1;
END;

CREATE TRIGGER trg_master_assignment_closure_active
BEFORE INSERT ON exam_work_assignment_closures
WHEN NOT EXISTS (
    SELECT 1
    FROM exam_work_assignment_states
    WHERE assignment_id = NEW.assignment_id
      AND state = 'ACTIVE'
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Assignment is not active'
    );
END;

-- A coordinator cannot claim that no work was returned if
-- the master already holds a contribution from the assignment.
CREATE TRIGGER trg_master_assignment_closure_no_return
BEFORE INSERT ON exam_work_assignment_closures
WHEN NEW.closure_kind = 'WITHOUT_RETURN'
 AND EXISTS (
    SELECT 1
    FROM contribution_intake_packages
    WHERE assignment_id = NEW.assignment_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Received contribution must be reconciled before closure'
    );
END;

-- Reconciled-return closure must reference a return whose
-- application has been recorded for the same assignment.
CREATE TRIGGER trg_master_assignment_closure_reconciled
BEFORE INSERT ON exam_work_assignment_closures
WHEN NEW.closure_kind = 'RECONCILED_RETURN'
 AND NOT EXISTS (
    SELECT 1
    FROM contribution_application_batches batches
    JOIN contribution_intake_packages packages
        ON packages.package_id = batches.package_id
    WHERE batches.package_id = NEW.return_package_id
      AND packages.assignment_id = NEW.assignment_id
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Cannot close assignment before return application'
    );
END;

-- Inserting closure evidence performs the state transition
-- within the same SQLite statement and transaction.
CREATE TRIGGER trg_master_assignment_closure_applies_state
AFTER INSERT ON exam_work_assignment_closures
BEGIN
    UPDATE exam_work_assignment_states
    SET state = NEW.closed_state,
        updated_at = NEW.closed_at
    WHERE assignment_id = NEW.assignment_id
      AND state = 'ACTIVE';

    -- Closure must transition exactly one active assignment.
    SELECT RAISE(
        ABORT,
        'Assignment closure did not change state'
    )
    WHERE changes() <> 1;
END;

CREATE TRIGGER trg_master_assignment_closure_no_update
BEFORE UPDATE ON exam_work_assignment_closures
BEGIN
    SELECT RAISE(
        ABORT,
        'Assignment closure evidence is immutable'
    );
END;

CREATE TRIGGER trg_master_assignment_closure_no_delete
BEFORE DELETE ON exam_work_assignment_closures
BEGIN
    SELECT RAISE(
        ABORT,
        'Assignment closure evidence is immutable'
    );
END;

CREATE TRIGGER trg_master_assignment_lock_no_active_delete
BEFORE DELETE ON exam_work_assignment_exam_locks
WHEN EXISTS (
    SELECT 1
    FROM exam_work_assignment_states states
    WHERE states.assignment_id = OLD.assignment_id
      AND states.state = 'ACTIVE'
)
BEGIN
    SELECT RAISE(
        ABORT,
        'Active assignment lock cannot be removed'
    );
END;

CREATE TRIGGER trg_master_assignment_lock_no_update
BEFORE UPDATE ON exam_work_assignment_exam_locks
BEGIN
    SELECT RAISE(
        ABORT,
        'Assignment lock cannot be modified'
    );
END;

UPDATE schema_version
SET version = 38
WHERE version = 37;