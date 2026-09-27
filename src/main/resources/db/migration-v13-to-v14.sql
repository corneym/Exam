-- Version 14 introduces ordered mixed Question content.
--
-- Existing PDF regions remain in question_regions because they retain useful
-- PDF-specific identity and constraints. question_content_parts supplies the
-- authoritative assembly order across PDF regions and pasted images.

CREATE TABLE question_images (
    id INTEGER PRIMARY KEY,
    question_id INTEGER NOT NULL,
    image_png BLOB NOT NULL
        CHECK (length(image_png) > 0),
    FOREIGN KEY (question_id)
        REFERENCES questions(id)
        ON DELETE CASCADE,
    UNIQUE (question_id, id)
);

CREATE TABLE question_content_parts (
    question_id INTEGER NOT NULL,
    content_order INTEGER NOT NULL
        CHECK (content_order >= 0),
    content_type TEXT NOT NULL
        CHECK (content_type IN ('PDF_REGION', 'IMAGE')),
    region_order INTEGER,
    image_id INTEGER,

    PRIMARY KEY (question_id, content_order),

    FOREIGN KEY (question_id)
        REFERENCES questions(id)
        ON DELETE CASCADE,

    FOREIGN KEY (question_id, region_order)
        REFERENCES question_regions(question_id, region_order)
        ON DELETE CASCADE,

    FOREIGN KEY (question_id, image_id)
        REFERENCES question_images(question_id, id)
        ON DELETE CASCADE,

    CHECK (
        (
            content_type = 'PDF_REGION'
            AND region_order IS NOT NULL
            AND image_id IS NULL
        )
        OR
        (
            content_type = 'IMAGE'
            AND region_order IS NULL
            AND image_id IS NOT NULL
        )
    )
);

-- Every existing Question region becomes one PDF content part. Existing
-- region_order already represents the complete assembly order for PDF-only
-- Questions, so it can be copied directly without changing semantics.
INSERT INTO question_content_parts (
    question_id,
    content_order,
    content_type,
    region_order,
    image_id
)
SELECT
    question_id,
    region_order,
    'PDF_REGION',
    region_order,
    NULL
FROM question_regions
ORDER BY question_id, region_order;

UPDATE schema_version
SET version = 14;
