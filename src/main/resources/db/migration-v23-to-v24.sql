-- Version 24 introduces ordered mixed Shared Context content.
-- Existing PDF regions and their coordinates remain authoritative
-- PDF source records. The new content table defines their order
-- relative to independently stored PNG images.

CREATE TABLE shared_question_context_images (
    shared_context_id INTEGER NOT NULL,
    image_order INTEGER NOT NULL
        CHECK (image_order >= 0),
    image_png BLOB NOT NULL
        CHECK (length(image_png) > 0),

    PRIMARY KEY (shared_context_id, image_order),

    FOREIGN KEY (shared_context_id)
        REFERENCES shared_question_contexts(id)
);

CREATE TABLE shared_question_context_content_parts (
    shared_context_id INTEGER NOT NULL,
    content_order INTEGER NOT NULL
        CHECK (content_order >= 0),
    content_type TEXT NOT NULL
        CHECK (content_type IN ('PDF_REGION', 'IMAGE')),
    region_order INTEGER,
    image_order INTEGER,

    PRIMARY KEY (shared_context_id, content_order),

    FOREIGN KEY (shared_context_id)
        REFERENCES shared_question_contexts(id),

    FOREIGN KEY (shared_context_id, region_order)
        REFERENCES shared_question_context_regions(
            shared_context_id, region_order
        ),

    FOREIGN KEY (shared_context_id, image_order)
        REFERENCES shared_question_context_images(
            shared_context_id, image_order
        ),

    CHECK (
        (
            content_type = 'PDF_REGION'
            AND region_order IS NOT NULL
            AND image_order IS NULL
        )
        OR
        (
            content_type = 'IMAGE'
            AND region_order IS NULL
            AND image_order IS NOT NULL
        )
    )
);

-- Each existing PDF region becomes one ordered content part.
-- The original region records are neither removed nor rewritten.

INSERT INTO shared_question_context_content_parts (
    shared_context_id,
    content_order,
    content_type,
    region_order,
    image_order
)
SELECT
    shared_context_id,
    region_order,
    'PDF_REGION',
    region_order,
    NULL
FROM shared_question_context_regions
ORDER BY shared_context_id, region_order;

UPDATE schema_version
SET version = 24
WHERE version = 23;
