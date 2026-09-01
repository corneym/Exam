CREATE TABLE curriculum_mapping_reviews (
    source_node_id INTEGER NOT NULL,
    target_syllabus_version_id INTEGER NOT NULL,
    review_outcome TEXT NOT NULL
        CHECK (review_outcome IN ('MATCHED', 'NO_MATCH')),
    PRIMARY KEY (source_node_id, target_syllabus_version_id),
    FOREIGN KEY (source_node_id)
        REFERENCES curriculum_nodes(id),
    FOREIGN KEY (target_syllabus_version_id)
        REFERENCES syllabus_versions(id)
);

INSERT INTO curriculum_mapping_reviews (
    source_node_id,
    target_syllabus_version_id,
    review_outcome
)
SELECT DISTINCT
    mapping.source_node_id,
    target.syllabus_version_id,
    'MATCHED'
FROM curriculum_mappings mapping
JOIN curriculum_nodes target
    ON target.id = mapping.target_node_id
WHERE mapping.mapping_status = 'CONFIRMED';

UPDATE schema_version
SET version = 3
WHERE version = 2;
