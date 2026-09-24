-- A Question is included at every current curriculum node derived by the
-- mapping system unless an explicit exclusion row exists here. Storing only
-- exceptions preserves all existing output behaviour when version 11 data is
-- migrated.
CREATE TABLE question_output_exclusions (
    question_id INTEGER NOT NULL,
    current_curriculum_node_id INTEGER NOT NULL,
    PRIMARY KEY (
        question_id,
        current_curriculum_node_id
    ),
    FOREIGN KEY (question_id)
        REFERENCES questions(id)
        ON DELETE CASCADE,
    FOREIGN KEY (current_curriculum_node_id)
        REFERENCES curriculum_nodes(id)
        ON DELETE CASCADE
);

-- Existing Questions have no explicit exclusions, so migration requires no
-- data backfill. Their derived current applicability remains unchanged.
UPDATE schema_version
SET version = 12;
