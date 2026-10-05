-- Version 17 records explicit Question-side source recapture requirements.
--
-- This is distinct from ordinary content completeness. A legitimate image-only
-- Question is complete, while a mixed-content Question whose PDF regions were
-- invalidated by booklet replacement must return to capture even though its
-- independent image parts remain stored.

ALTER TABLE questions
ADD COLUMN source_capture_required INTEGER NOT NULL DEFAULT 0
    CHECK (source_capture_required IN (0, 1));

-- Existing Questions have not had their source PDF invalidated by the new
-- replacement workflow, so migration must not infer recapture work.

UPDATE schema_version
SET version = 17;
