# Sprint 07 — Preamble-aware Question Capture and UI Redesign

> **Status:** DESIGN COMPLETE — implementation not started  
> **Feature branch:** `feature/preamble-capture`  
> **Repository:** `corneym/Exam`

## 1. Sprint objective

Sprint 07 will make capture and revision output accurately represent examination structure where introductory material is shared across question parts or otherwise independent questions.

The sprint covers one coherent workflow:

- persist source-question identity for multipart questions;
- persist shared context/preamble separately from ordinary question regions;
- migrate and use legacy `preambleCaptureRequired` evidence without guessing relationships;
- capture, link and reuse shared context;
- preserve ordinary multi-region questions as a separate concept;
- allow correction/editing of captured questions and answers;
- display question marks during answer capture;
- prevent accidental loss of pending selections;
- redesign the capture workspace for sustained use;
- make the built-in question-classification controls reflect the selected syllabus hierarchy, including Descriptor-level selection where that level exists;
- enforce classification completion according to the hierarchy actually present in the selected syllabus;
- update revision HTML so shared context and multipart questions are presented correctly;
- retain the existing SCORM 1.2 packaging architecture while packaging the amended revision output.

Curriculum import, curriculum mapping, curriculum applicability and retrieval semantics are not redesigned by this sprint.

Sprint 07 does change the **question-classification capture UI and validation** so it correctly consumes the hierarchy already supplied by the selected syllabus.

## 2. Domain concepts

Sprint 07 distinguishes three concepts.

### Question

`Question` remains an independently classified and marked question or question part, for example:

- `21a` — 2 marks — Descriptor A;
- `21b` — 4 marks — Descriptor B;
- `21c` — 3 marks — Descriptor A.

Each part keeps its own:

- question code;
- marks;
- classification;
- ordered question regions;
- answer.

### SourceQuestion

`SourceQuestion` represents the common identity in the original examination paper, for example source question `21`.

A source question may have one or more `Question` members.

```text
SourceQuestion "21"
├── Question "21a"
├── Question "21b"
└── Question "21c"
```

Membership is persisted explicitly. It is not reconstructed on every read by stripping suffixes from question codes.

### SharedQuestionContext

`SharedQuestionContext` represents material that must be shown with one or more questions, such as:

- a preamble;
- common stem;
- table;
- graph;
- diagram;
- introductory text.

The shared context is captured independently from the question regions and contains one or more ordered source regions.

```text
SharedQuestionContext
├── context region 1
└── context region 2

Question 21a ──► SharedQuestionContext
Question 21c ──► SharedQuestionContext
```

A shared context may also be reused by otherwise independent questions such as several MCQs. Therefore shared-context identity and multipart source-question identity are deliberately separate concepts.

## 3. Ordinary multi-region questions remain unchanged

A question may occupy several PDF rectangles because of page layout, size or page breaks.

```text
Question 14
├── QuestionRegion 1
└── QuestionRegion 2
```

This means only that Question 14 has two ordered regions.

It does **not** imply:

- a preamble;
- shared source material;
- multipart membership;
- a dependency on another question.

This existing behaviour remains valid.

## 4. Deliberate Sprint 07 scope limit for shared context

Sprint 07 will support zero or one linked `SharedQuestionContext` per `Question`.

One shared context may:

- contain multiple ordered regions;
- be linked to many questions.

This covers the demonstrated use cases without introducing a general dependency graph.

If a future real use case proves that a single question requires several independently reusable shared-context objects, the model can be extended later.

Shared context is booklet-scoped in Sprint 07.

## 5. Schema version 5

Sprint 07 requires a v4 → v5 database migration.

### 5.1 New `source_questions` table

Proposed fields:

```text
id
booklet_id
source_question_code
```

Constraint:

```text
UNIQUE(booklet_id, source_question_code)
```

The code is text rather than integer so the domain does not unnecessarily restrict examination numbering conventions.

### 5.2 New `shared_question_contexts` table

Proposed fields:

```text
id
booklet_id
context_label
```

The label is descriptive metadata for capture/editing and is not the identity used for grouping.

### 5.3 New `shared_question_context_regions` table

Proposed fields:

```text
id
shared_context_id
region_order
page_number
x
y
width
height
```

Constraint:

```text
UNIQUE(shared_context_id, region_order)
```

Coordinates use the same normalized proportional rules as existing question regions.

### 5.4 Changes to `questions`

Add nullable foreign keys:

```text
source_question_id
shared_context_id
```

The existing `preamble_capture_required` field remains.

### 5.5 No redundant persisted multipart marks

Each `Question` continues to store its own marks.

A multipart presentation total is derived by summing the marks of the member parts actually presented together.

Do not add a persisted multipart-total-marks field.

### 5.6 No persisted UI pin state

Any capture-time convenience for reusing the most recently selected shared context is transient UI state only.

Do not add a `pinned` database flag.

## 6. Migration rules

The v4 → v5 migration must preserve all existing data.

For every existing question:

```text
source_question_id = NULL
shared_context_id = NULL
```

The migration must not infer source-question membership from codes such as:

```text
21a
21b
21c
```

The migration must not create shared contexts from `preamble_capture_required = 1`.

The legacy Boolean records only that the legacy row indicated shared introductory material was required. It does not establish which rows share the same material.

The same rule applies to future legacy workbook imports after v5.

## 7. Legacy preamble resolution

The historical flag is preserved as evidence.

Operational resolution is derived:

```text
preamble_capture_required = true
AND shared_context_id IS NULL

=> shared context required — unresolved
```

Once an explicit shared context is linked, the operational requirement is resolved.

The Boolean itself remains unchanged.

This avoids rewriting historical import evidence merely because later capture work has been completed.

## 8. Source-question identification workflow

The application may offer a suggestion based on familiar question-code patterns.

Example:

```text
Question code: 21a
Suggested source question: 21    [Use]
```

The suggestion is not persisted unless the user explicitly accepts it or explicitly selects/types the source-question relationship.

The UI may also allow selection of an already-persisted source question in the active booklet.

This uses the legacy convention as a capture convenience without silently turning a naming pattern into authoritative data.

## 9. Shared-context capture workflow

Question capture gains a distinct **Shared context / preamble** section.

For a question requiring shared context, the user can:

1. link an existing shared context from the same booklet; or
2. capture a new shared context separately from the question regions.

A new shared context may contain multiple accepted regions.

Example:

```text
Question code: 21a
Marks: 2
Source question: 21

Shared context / preamble:
    Question 21 preamble

Question regions:
    Region 1

Classification:
    ...
```

When `21c` is captured, the existing context can be reused rather than captured again.

For a legacy flagged record the current instruction to include the preamble with the question is replaced by an unresolved-status message and explicit link/capture actions.

## 10. Capture-required status

Sprint 07 should distinguish at least:

```text
missing ordinary question regions
unresolved required shared context
```

A legacy question remains capture-incomplete when either condition applies.

This sprint does not require a full general-purpose capture audit dashboard. The model and repository queries should, however, leave that future workflow straightforward.

## 11. Question editing/correction

A persisted question can be reopened for correction.

Editable fields:

- question code;
- marks;
- classification;
- ordered question regions;
- source-question link;
- shared-context link.

Not editable in this workflow:

- exam/booklet ownership.

The existing question ID is retained so an existing answer remains attached.

Existing `questionText` and `preamble_capture_required` values are preserved unless a later explicit requirement introduces editing for them.

Persistence must update the question and replace its ordered regions transactionally.

No question deletion is introduced in Sprint 07.

## 12. Answer capture and editing

Answer capture must display the selected question's marks.

Example:

```text
QCAA 2024 — Paper 2 — 21a — 2 marks
```

and:

```text
Answering 21a — 2 marks — 2 regions accepted
```

The marks are a human prompt only. The application does not attempt to judge answer completeness from the number of marks.

The answer selector should support both unanswered and answered questions.

When an existing answer is selected:

- load existing answer text;
- load existing ordered answer regions;
- allow correction;
- update the persisted answer transactionally.

Where practical the answer ID should remain stable:

```text
UPDATE answers
DELETE old answer_regions
INSERT replacement answer_regions in order
```

No answer deletion is introduced in Sprint 07.

## 13. Selection ownership

The shared PDF workspace must have explicit selection ownership.

Required owners:

```text
QUESTION
SHARED_CONTEXT
ANSWER
```

Only the owner of a transient selection may clear that selection.

This fixes the existing defect where a Question-side Clear action can interfere with an Answer-side selection.

Button wording should be made less ambiguous, for example:

```text
Add region
Discard selection
Clear all regions
Remove
```

## 14. Accidental-loss protection

An unaccepted current rectangle must not disappear silently when an action changes context.

Guard transitions including:

- page change;
- selected question change;
- capture-target change;
- PDF/document change;
- entering another edit record.

If a current selection exists but has not been accepted, block the transition and require **Add region** or **Discard selection**.

Accepted but unsaved work may require confirmation before a destructive transition.

Accepted regions do not block ordinary page navigation because multi-page question/answer capture depends on retaining them.

## 15. Capture workspace redesign

Replace the fixed narrow capture column with a horizontally resizable JavaFX `SplitPane`.

At the current default window size, the initial divider should allocate roughly 500–550 pixels to capture controls while allowing the user to resize it.

The capture side remains vertically scrollable.

Retain:

- bordered logical sections;
- bold Question and Answer headings;
- clear separation of current selection and accepted regions.

Principal structure:

```text
Classification

Question
    imported/existing question selection
    question code
    marks
    source question
    shared context / preamble
    current selection
    accepted regions
    save/update

Answer
    question selection + marks
    answer PDF
    current selection
    accepted regions
    answer text
    save/update
```

The redesign should use the additional width for useful controls and previews rather than blank spacing.


## 16. Syllabus-sensitive question classification

The built-in Question classification panel must be driven by the hierarchy of the selected syllabus version rather than assuming one fixed level structure.

For a syllabus branch using:

```text
Unit -> Topic -> Descriptor
```

the panel must show:

```text
Unit
Topic
Descriptor
```

For a syllabus branch using:

```text
Unit -> Topic -> Subtopic -> Descriptor
```

the panel must show all four levels.

A level that is not present in the selected syllabus branch must not be shown merely because another syllabus uses it.

### 16.1 Descriptor control

Add a Descriptor-level drop-down/control whenever Descriptor is a valid child level in the selected hierarchy branch.

Descriptor choices must be filtered by the selections above them. Changing a parent selection must clear any child selection that is no longer valid.

### 16.2 Valid classification stopping points

Classification validity depends on the hierarchy beneath the selected node.

Confirmed Sprint 07 rules:

- a Question may be classified at **Subtopic** level even when that Subtopic has Descriptor children;
- where a selected Topic has **direct Descriptor children**, Topic alone is not sufficiently precise and a Descriptor must be selected;
- where the syllabus uses `Unit -> Topic -> Descriptor`, Descriptor is therefore required for a Question under a Topic that has Descriptors;
- where the syllabus uses `Unit -> Topic -> Subtopic -> Descriptor`, a Question may validly stop at Subtopic or may be classified to a Descriptor;
- controls and validation must follow the actual persisted syllabus branch rather than a fixed Chemistry-specific shape.

The sprint must not invent a curriculum level that is absent from the selected syllabus.

### 16.3 Domain/persistence boundary

This is a capture and validation change over the existing curriculum hierarchy.

Sprint 07 does **not** redesign:

- curriculum import;
- curriculum-node persistence;
- historical-to-current mapping;
- mapping review;
- current applicability;
- retrieval hierarchy semantics.

If the existing `Question` domain invariant prevents a classification level required by these confirmed rules, the smallest necessary domain/repository adjustment is part of Sprint 07. Do not broaden classification semantics beyond these rules.

### 16.4 Editing

When an existing Question is reopened for correction, the classification panel must reconstruct the correct hierarchy path for that Question's syllabus and classification node.

The same syllabus-sensitive validation applies when updating an existing Question.

## 17. Revision grouping semantics

Sprint 07 includes the required revision-output changes. They are not deferred to a follow-up sprint.

Grouping is performed within the final current-curriculum output bucket after normal retrieval/applicability logic has determined which questions belong there.

Example:

```text
21a = 2 marks -> Descriptor A
21b = 4 marks -> Descriptor B
21c = 3 marks -> Descriptor A
```

Descriptor A presents one multipart revision question:

```text
Revision Question 7                         5 marks

[shared context once]

[21a]
[21c]

Reveal answer
    [21a answer]
    [21c answer]
```

Descriptor B presents:

```text
Revision Question 4                         4 marks

[shared context]

[21b]

Reveal answer
    [21b answer]
```

The combined mark value is:

```text
sum of the marks of the member parts in that output group
```

It is derived, not stored.

If only one part of a source question appears in a bucket, it behaves as a single question and displays that part's marks.

## 18. Shared context does not imply multipart grouping

Several independent questions may link to one shared context.

Example:

```text
shared context
├── MCQ 8
├── MCQ 9
└── MCQ 10
```

These remain separate revision questions with their own numbering and marks.

The presentation layer may render the shared context once for the appropriate consecutive group, but it must not convert the MCQs into one multipart source question unless they actually share a `SourceQuestion`.

Therefore:

- multipart grouping depends on `SourceQuestion`;
- context deduplication depends on `SharedQuestionContext`.

## 19. Inconsistent relationships

If questions that would otherwise form one multipart output group have inconsistent shared-context links, the exporter must not guess which context was intended.

The inconsistency should be treated as incomplete/inconsistent data and surfaced for correction.

## 20. Revision HTML changes

Sprint 07 must update the revision corpus/presentation boundary so the HTML renderer receives explicit presentation groups rather than having to rediscover relationships ad hoc.

A pure grouping/planning layer should be preferred.

Its responsibilities include:

- group placements by final curriculum bucket;
- identify same-`SourceQuestion` members;
- retain deterministic member order;
- derive combined marks;
- determine context rendering;
- keep unrelated questions separate;
- expose incomplete/inconsistent relationship state.

The existing static HTML navigation, source attribution, answer disclosure and asset-generation architecture remain otherwise intact.

## 21. SCORM changes

SCORM packaging mechanics remain unchanged:

- SCORM 1.2;
- one organisation/item/SCO;
- launch `index.html`;
- static multi-page revision website;
- root manifest/schema files;
- no SCORM runtime/API JavaScript;
- no source PDFs.

SCORM consumes the amended revision HTML/content output.

Do not create a second multipart/preamble implementation inside SCORM.

Affected SCORM integration/package tests must prove that the revised content is still completely declared and successfully packaged.

## 22. Areas deliberately not redesigned

Sprint 07 does not redesign:

- curriculum hierarchy persistence;
- curriculum Excel import;
- historical/current syllabus handling;
- curriculum mapping;
- mapping review;
- mapping suggestion logic;
- current-curriculum applicability;
- Sprint 03 retrieval semantics;
- exam/provider/booklet persistence;
- managed PDF storage/path safety;
- normalized coordinate rules;
- backup/restore architecture;
- automatic backup retention/shutdown behaviour;
- SCORM manifest/profile/ZIP architecture;
- multiple original classifications;
- curriculum mapping reconciliation;
- general import-audit reporting;
- explicit out-of-scope disposition;
- assisted question-boundary recognition;
- clipboard/snipping image questions;
- Exam Builder;
- printable assessment generation;
- general retrieval hardening.

Question search is not redesigned in this sprint. Shared-context-aware search previews may be considered later unless implementation shows a correctness problem that requires a narrow compatibility change.

## 23. Implementation sequence

### Slice 1 — schema v5 and domain foundation

Add:

- `SourceQuestion`;
- `SharedQuestionContext`;
- shared-context region domain type;
- v4 → v5 migration;
- schema verification;
- optional relationships on `Question`.

Run focused model and SQLite migration/schema tests.

Stop for test result before continuing.

### Slice 2 — persistence

Add repository/writer support for:

- source-question creation/lookup;
- shared-context persistence;
- context-region ordering;
- explicit links;
- transactional question update.

Run focused assessment repository/writer tests.

### Slice 3 — legacy unresolved-context semantics

Keep import non-inferential.

Replace the current capture hint with an explicit unresolved requirement and include unresolved flagged questions in the capture-required workflow.

Run focused legacy import and capture-status tests.

### Slice 4 — selection ownership and warnings

Introduce explicit QUESTION / SHARED_CONTEXT / ANSWER ownership.

Fix the Question Clear / Answer selection defect.

Guard destructive transitions.

Run focused workflow tests.

### Slice 5 — shared-context/source-question capture UI

Add source-question suggestion/confirmation, new-context capture, existing-context linking and reuse convenience.

Run focused capture workflow tests and manual Eclipse smoke test.

### Slice 6 — syllabus-sensitive classification panel

Add Descriptor selection where the selected syllabus branch contains Descriptors.

Make visible levels and child choices follow the actual syllabus hierarchy.

Enforce the confirmed stopping rules:

- Subtopic may remain the final classification even when Descriptors exist below it;
- Topic may not remain the final classification when that Topic has direct Descriptor children.

Run focused curriculum-selection/classification-validator tests plus Question capture workflow tests across representative hierarchy shapes.

### Slice 7 — question correction

Load and update persisted questions without changing identity/booklet ownership.

Run repository and JavaFX workflow tests.

### Slice 9 — answer correction and marks display

Replace insert-only answer workflow with save/update semantics and display marks prominently.

Run answer writer/validator/workflow tests.

### Slice 9 — resizable capture workspace

Replace fixed capture width with a resizable SplitPane and improve control layout/previews.

Run JavaFX workflow/smoke tests and manual sustained-use check.

### Slice 10 — revision presentation planner

Implement deterministic grouping/deduplication rules and derived multipart marks at a pure service/domain boundary.

Run focused grouping tests including same-bucket/different-bucket cases and independent questions sharing context.

### Slice 11 — HTML and SCORM integration

Update asset/rendering flow and HTML presentation to consume the grouping plan.

Verify:

- preamble once where appropriate;
- multipart parts grouped correctly;
- combined marks;
- separate answers retained;
- cross-bucket repetition where required;
- independent shared-context questions remain independent;
- SCORM packages the amended static content without changing its SCORM profile.

Run revision export and SCORM integration tests.

### Final checkpoint

- run complete Maven clean test suite;
- inspect GitHub branch diff against current `main`;
- run Codex merge-readiness review;
- independently verify findings;
- fix genuine issues;
- rerun tests;
- update `current-status.md`, `project-history.md`, architecture/roadmap/backlog documentation to implemented state;
- merge only when ready.

## 24. Acceptance criteria

Sprint 07 is complete when:

- an existing v4 database migrates to v5 without loss;
- no source-question or shared-context relationship is guessed during migration/import;
- `21a` and `21c` can explicitly share persisted source question `21`;
- shared context can be captured separately and reused;
- ordinary multi-region questions remain semantically unchanged;
- legacy flagged questions clearly show unresolved shared context until explicitly resolved;
- captured question metadata/regions/relationships can be corrected;
- existing answers can be corrected;
- answer capture displays marks;
- transient selections cannot be silently lost on destructive transitions;
- Question-side Clear cannot clear an Answer-owned selection;
- the capture workspace is resizable and practical for sustained use;
- the Question classification panel shows only the levels present in the selected syllabus hierarchy;
- Descriptor selection is available where Descriptor exists in that hierarchy;
- a Question may stop at Subtopic even when that Subtopic has Descriptor children;
- a Question cannot stop at Topic when that Topic has direct Descriptor children;
- editing reconstructs and validates the correct syllabus-specific classification path;
- revision HTML renders shared context once where the grouping rules require it;
- same-bucket multipart parts render as one revision question with derived combined marks;
- parts in different output buckets render separately with the needed context;
- independent questions sharing context remain independent questions;
- SCORM packages and launches the amended revision website without redesigning its SCORM profile;
- the complete test suite is green.

## 25. Branch

Use:

```text
feature/preamble-capture
```
