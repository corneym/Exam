-- Align the live database schema with the Shared Context terminology already
-- used by the domain and UI. These are physical column renames only; existing
-- values and constraints are retained unchanged.
ALTER TABLE questions
RENAME COLUMN preamble_capture_required
TO shared_context_capture_required;

ALTER TABLE source_questions
RENAME COLUMN preamble_status
TO shared_context_status;

UPDATE schema_version
SET version = 13;
