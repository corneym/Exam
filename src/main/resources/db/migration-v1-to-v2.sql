CREATE TABLE curriculum_mappings (
    id INTEGER PRIMARY KEY,
    source_node_id INTEGER NOT NULL,
    target_node_id INTEGER NOT NULL,
    mapping_status TEXT NOT NULL
        CHECK (mapping_status IN ('SUGGESTED', 'CONFIRMED')),
    FOREIGN KEY (source_node_id)
        REFERENCES curriculum_nodes(id),
    FOREIGN KEY (target_node_id)
        REFERENCES curriculum_nodes(id),
    CHECK (source_node_id <> target_node_id),
    UNIQUE (source_node_id, target_node_id)
);

UPDATE schema_version
SET version = 2
WHERE version = 1;
