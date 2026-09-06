# Sprint Design: Hierarchical Revision Corpus and Static HTML Export (version 1)

## Branch

`feature/html-output`

## Sprint Status

**Planned — Sprint 05.**

## Repository Baseline

Sprint 05 is designed against `main` after the Sprint 04 merge.

The baseline includes:

- Sprint 01 legacy metadata import;
- Sprint 02 directional curriculum applicability;
- Sprint 03 hierarchical question retrieval;
- Sprint 04 backup, restore and data safety;
- managed source PDFs;
- ordered question and answer regions;
- answer text/region persistence;
- current/historical curriculum mapping;
- subject-wide current-curriculum retrieval;
- stored-question reconstruction and preview;
- the existing proof-of-concept `HtmlQuestionRenderer`.

Before implementation begins, the high-level status documents should be aligned with the repository state. In particular, `docs/current-status.md`, `docs/roadmap.md` and the Sprint 04 status section of `docs/DEVELOPMENT_ROADMAP.md` still contain text that describes Sprint 04 as planned/next even though Sprint 04 is complete and merged.

The completed Sprint 04 design document remains a historical record and should not be rewritten beyond factual correction if necessary.

---

# 1. Purpose

Sprint 05 is the first production-oriented output sprint for the captured question bank.

The goal is to turn the current-curriculum retrieval capability into a deterministic, student-facing revision corpus and export it as ordinary static HTML plus derived image assets.

The completed sprint should allow a user to choose a Subject and an export destination in the desktop application and produce a browsable revision resource such as:

```text
Chemistry Revision
    -> Unit
        -> Topic
            -> Subtopic
                -> Questions
                -> Descriptor
                    -> Questions

or

Chemistry Revision
    -> Unit
        -> Topic
            -> Descriptor
                -> Questions
```

The output must work independently as static web content.

Sprint 05 deliberately stops before SCORM packaging.

---

# 2. Sprint Goal

At the end of Sprint 05 the application can:

1. select a Subject;
2. locate its single current syllabus;
3. build a deterministic in-memory revision corpus from the current curriculum and stored questions;
4. distinguish known questions from questions that can actually be rendered;
5. render captured question regions to deterministic web image assets;
6. render stored answer text and answer regions where available;
7. generate hierarchical static HTML with ordinary relative links;
8. preserve source attribution and original classification provenance;
9. validate the generated output before reporting success;
10. expose useful export/completeness statistics to the user.

The final Sprint 05 deliverable is a directory of static HTML/assets, not a ZIP package.

---

# 3. Explicit Non-Goals

Sprint 05 does **not** include:

- SCORM manifest generation;
- SCORM ZIP creation;
- QLearn import testing;
- SCORM API/runtime JavaScript;
- choosing or validating a QLearn SCORM profile;
- Exam Builder or assessment assembly;
- printable assessment/PDF generation;
- browser-side PDF.js rendering;
- packaging full authoritative source PDFs into the output;
- database schema changes solely for export;
- changing historical classifications to current classifications;
- solving the multiple-original-classification design gap;
- solving generalised shared-preamble/multipart persistence;
- implementing the capture-required work queue;
- importing/reconciling the 5 September mapping workbook;
- clipboard/image-snip question capture;
- OCR;
- broad retrieval performance optimisation without measurement;
- unrelated JavaFX/TestFX backlog work.

SCORM remains Sprint 06.

Parallel data work may continue while Sprint 05 is implemented, but it is not part of this sprint's acceptance boundary.

---

# 4. Existing Architecture Relevant to Sprint 05

## 4.1 Curriculum hierarchy

The current hierarchy is persisted as:

```text
Subject
    -> SyllabusVersion
        -> Unit
            -> Topic
                -> Subtopic
                    -> Descriptor

or

Subject
    -> SyllabusVersion
        -> Unit
            -> Topic
                -> Descriptor
```

`CurriculumRepository` already exposes:

- Subject lookup;
- syllabus versions;
- current hierarchy roots;
- child traversal;
- curriculum node lookup.

`CurriculumNode.displayOrder` supplies deterministic sibling ordering.

`CurriculumSearchNodeExpansionService` already enforces the important retrieval hierarchy rule that a Topic contains either Subtopic children or Descriptor children, not a mixture.

Sprint 05 must mirror this existing hierarchy rather than invent a separate export taxonomy.

## 4.2 Current applicability

`QuestionRetrievalService.findQuestionsApplicableTo(Subject)` already provides the correct semantic boundary.

It returns unique stored Questions together with the current Subtopic/Descriptor nodes that make each question applicable.

This already handles:

- directly current questions;
- confirmed historical -> current mappings;
- one historical node mapping to several current nodes;
- original classification preservation;
- duplicate prevention within retrieval results.

Sprint 05 should consume this service rather than repeat curriculum-mapping logic.

## 4.3 Complete Question reconstruction

`SqliteQuestionRepository` reconstructs:

- the Question;
- its Exam/ExamBooklet/source document;
- ordered question regions;
- original curriculum classification;
- optional Answer;
- answer text;
- ordered AnswerRegion records and their AnswerFiles.

Therefore the exporter does not need a new persistence query merely to obtain output content.

## 4.4 PDF rendering

`QuestionExtractor` already:

- renders at the existing web-suitable raster resolution;
- crops stored normalised regions;
- combines ordered question regions vertically;
- extracts answer regions.

Sprint 05 should reuse this source-PDF rendering boundary.

Rendered images remain derived artefacts.

## 4.5 Existing HTML proof

`HtmlQuestionRenderer` is a simple proof-of-concept that writes one HTML document from already-rendered question images.

It should remain a small historical/foundation class.

Sprint 05 should add a dedicated revision-export layer rather than expanding this class until it contains curriculum, navigation, answer, provenance and export orchestration logic.

---

# 5. Core Design Principles

## 5.1 The current curriculum drives placement

The generated resource is organised by the Subject's **single current syllabus**.

Historical questions may appear under current nodes only through the applicability already derived by Sprint 02/03 rules.

The exporter must not rewrite the stored original classification.

## 5.2 Export is a projection, not new persistent state

The revision corpus is transient.

It is rebuilt from:

```text
current curriculum
+ retrieval results
+ stored question/answer regions
```

No corpus tables, generated-number tables or export-state tables are required in SQLite.

## 5.3 One subject per export

A Sprint 05 export represents one Subject and its single current syllabus.

This keeps navigation, provenance, completeness statistics and later SCORM packaging clear.

A later feature may batch several Subjects, but that is not required now.

## 5.4 Current retrieval semantics remain authoritative

Sprint 05 must not create its own mapping or search rules.

The corpus builder uses one Subject-wide retrieval operation and places each result under the returned current applicability nodes.

## 5.5 Same question may legitimately appear under more than one current node

A historical classification may map to more than one confirmed current node.

Therefore:

- one stored Question may have multiple export placements;
- it appears at most once within a given current node;
- duplicate placement across different valid current nodes is allowed;
- a single rendered question asset should be reused by those placements where possible.

This is different from the unresolved question of multiple **original** classifications.

## 5.6 Subtopic precision must be preserved

A question applicable only at Subtopic level is placed at that Subtopic level.

It must not be copied into every Descriptor beneath the Subtopic.

## 5.7 Missing question regions make the question non-renderable

A metadata-only Question with zero question regions is known to the corpus but cannot produce student question content.

Such a Question:

- contributes to known/applicable counts;
- contributes to an incomplete/missing-question-region count;
- is omitted from student question cards;
- must not generate a placeholder image pretending that question content exists.

## 5.8 Missing answers do not block question export

A captured Question with no stored Answer is still useful revision content.

It should be exported with a clear static indication such as:

`Answer not yet available.`

The absence of an Answer is counted separately.

## 5.9 Stored answer material is authoritative when present

An Answer may contain:

- text only;
- one or more answer regions;
- both.

HTML should render both when both exist.

Answer regions must retain stored order.

## 5.10 `preambleCaptureRequired` remains a warning, not a new exporter rule

The current field is a legacy capture hint. Sprint 05 does not have enough persisted semantics to determine automatically whether every shared stem/preamble has been fully resolved.

Therefore Sprint 05 should:

- count questions for which `preambleCaptureRequired` is true;
- report that count to the exporting user;
- not invent a new persisted "resolved" state;
- not silently create shared content;
- not reinterpret the flag as proof that an otherwise captured question must be excluded.

This limitation must remain explicit in the export result and sprint documentation.

## 5.11 Static output must not depend on application/runtime paths

Generated HTML must use ordinary portable relative links.

It must not contain:

- `file:///` source PDF references;
- absolute Windows paths;
- the configured `data.root`;
- database paths.

## 5.12 No browser-side PDF dependency

All required question and answer regions are rendered during export.

Opening the completed `index.html` must not require PDFBox, Java, the desktop application, PDF.js or access to the managed source-PDF tree.

---

# 6. Proposed Revision Corpus Model

Use a transient service-level model under a focused package such as:

`au.edu.eq.questionbank.service.revision`

Likely concepts:

```text
RevisionCorpus
RevisionCorpusNode
RevisionQuestionPlacement
RevisionCorpusStatistics
RevisionCorpusBuilder
```

Names may be adjusted during implementation if the resulting API is clearer.

## 6.1 `RevisionCorpus`

Represents:

- Subject;
- current SyllabusVersion;
- ordered curriculum roots;
- all question placements;
- completeness/export statistics.

## 6.2 `RevisionCorpusNode`

Represents one current curriculum node in the export tree.

It should hold:

- the underlying `CurriculumNode`;
- ordered child corpus nodes;
- ordered question placements applicable directly to that node.

The model should remain generic rather than creating Chemistry-specific Unit/Topic classes.

## 6.3 `RevisionQuestionPlacement`

Represents one occurrence of a stored Question under one current classification node.

It should expose:

- the Question;
- the current placement node;
- original classification;
- exportability state;
- generated revision number once ordering is finalised.

The same Question may have several placements.

## 6.4 `RevisionCorpusStatistics`

At minimum:

```text
applicable placements
unique applicable questions
renderable unique questions
missing-question-region questions
questions with answers
questions without answers
preamble-review flags
```

Statistics should distinguish unique Questions from placements so one-to-many mappings do not inflate "unique question" counts ambiguously.

---

# 7. Deterministic Corpus Ordering

Ordering is part of the export contract.

Traverse:

1. Unit by `displayOrder`;
2. Topic by `displayOrder`;
3. Topic child mode:
   - Descriptor children by `displayOrder`; or
   - Subtopic children by `displayOrder`;
4. for a Subtopic:
   - questions placed directly at the Subtopic;
   - Descriptor children by `displayOrder`;
5. questions within one placement node by persistent `Question.id`.

`QuestionRetrievalService` already returns Questions deterministically by persistent identifier. The corpus builder should preserve that deterministic order when assigning node placements.

## Generated numbering

Generated revision numbering is assigned to **placements**, not to persistent Questions.

Use one deterministic sequence across the complete exported corpus traversal:

```text
Revision Question 1
Revision Question 2
Revision Question 3
...
```

If one stored Question is legitimately placed under two current nodes, those are two resource placements and receive two generated revision numbers.

The original source identity remains visible so the duplication is transparent.

Rendered assets remain keyed by persistent Question identity and may be reused by several placements.

---

# 8. Student-Facing HTML Structure

The static output should use ordinary HTML5 and CSS.

No JavaScript framework is required.

Answers should use native HTML where possible, for example `<details>` / `<summary>`, so "show answer" behaviour works without custom JavaScript.

## Proposed directory contract

```text
<export-root>/
├── index.html
├── assets/
│   ├── revision.css
│   ├── questions/
│   │   ├── question-123.png
│   │   └── question-456.png
│   └── answers/
│       ├── question-123-answer-01.png
│       └── question-123-answer-02.png
└── units/
    ├── unit-10/
    │   ├── index.html
    │   ├── topic-20.html
    │   └── topic-21.html
    └── unit-11/
        ├── index.html
        └── topic-30.html
```

Persistent numeric IDs are used in paths because they are deterministic, filesystem-safe and do not require fragile filename sanitisation.

Curriculum codes and names remain human-facing HTML content.

## 8.1 Subject `index.html`

Should contain:

- Subject name;
- current syllabus identity;
- revision-resource heading;
- navigation grouped by Unit;
- links to Unit pages;
- available rendered-question count.

Administrative warnings such as missing-region counts do not need to be presented as student syllabus content.

## 8.2 Unit page

Should contain:

- Unit code/name;
- link back to Subject index;
- ordered Topic list;
- links to Topic pages;
- available-question counts per Topic.

## 8.3 Topic page

This is the main question-content page.

It should contain:

- Subject / Unit / Topic breadcrumb navigation;
- Topic code/name;
- either Descriptor sections directly, or Subtopic sections with Descriptor children;
- question cards in deterministic order;
- empty current curriculum sections where useful for orientation, without inventing question content.

## 8.4 Question card

Each rendered placement should show:

- generated revision question number;
- marks;
- rendered question image;
- source attribution;
- answer section.

Source attribution should include enough information to trace the material:

```text
Provider
year
exam name
booklet
original source question code
```

Original historical/current curriculum classification should remain available in the corpus model and may be shown as subdued provenance text, especially for mapped historical questions.

The current placement is already evident from the curriculum section containing the card.

## 8.5 Answer section

For a stored Answer:

```text
<details>
    <summary>Show answer</summary>
    answer text, if any
    answer region image(s), in order
</details>
```

For no stored Answer:

```text
Answer not yet available.
```

No client/server interaction is required.

---

# 9. Derived Asset Rules

## 9.1 Question image

One combined PNG per unique renderable Question:

```text
assets/questions/question-<questionId>.png
```

Use `QuestionExtractor.extractQuestion(...)` so multiple stored question regions remain in their defined order.

The same asset can be referenced by more than one curriculum placement.

## 9.2 Answer-region images

Answer text remains HTML text.

Each stored answer region becomes a PNG:

```text
assets/answers/question-<questionId>-answer-01.png
assets/answers/question-<questionId>-answer-02.png
```

Answer-region order is the persisted order.

Do not combine or reorder answer regions unless a later visual requirement proves that necessary.

## 9.3 Source PDFs

Do not copy source PDFs into the static export.

They remain authoritative managed application data.

## 9.4 Render quality

The first Sprint 05 implementation should reuse the existing PDFBox/`QuestionExtractor` rendering behaviour.

Visual quality must be checked with real Chemistry material.

If 150-DPI-derived images prove visibly inadequate in browser use, adjust the rendering boundary deliberately and test image size/quality rather than changing stored region coordinates.

Print-quality/vector output remains later work.

---

# 10. Export Orchestration

Introduce a focused output package such as:

`au.edu.eq.questionbank.output.revision`

Likely responsibilities:

```text
RevisionAssetRenderer
RevisionHtmlRenderer
RevisionExportRequest
RevisionExportResult
RevisionExportService
RevisionExportValidator
```

Exact class boundaries may be simplified during implementation if responsibilities remain clear.

## 10.1 `RevisionExportService`

Coordinates:

```text
validate request
    ↓
build RevisionCorpus
    ↓
create staging directory
    ↓
render unique question/answer assets
    ↓
write static CSS
    ↓
write subject/unit/topic HTML
    ↓
validate generated files and relative references
    ↓
promote staging directory to completed export
    ↓
return RevisionExportResult
```

## 10.2 Staged generation

A failed export must not leave a directory that appears successfully complete.

Generate into a temporary/staging directory first.

Only after all required output validates should the staging directory be moved/renamed to its completed destination.

If generation fails:

- clean up staging where practical;
- preserve existing source data;
- return/report a clear failure;
- do not report success.

This follows the same "complete artefact only after validation" principle used successfully in Sprint 04 backup generation.

## 10.3 Destination behaviour

The UI should let the user choose an export parent/destination.

The exporter may create a clearly named child directory such as:

```text
chemistry-revision-2026-09-06-120530/
```

Timestamping the top-level output directory is acceptable.

Determinism is required for:

- curriculum ordering;
- generated numbering for a fixed corpus;
- internal file paths;
- asset naming;
- page/link structure.

It is not necessary for every separate export run to have the same top-level folder name.

---

# 11. Export Validation

Before success is reported, validate at least:

- Subject has exactly one current syllabus;
- every generated HTML file exists and is non-empty;
- every generated required image exists and is non-empty;
- every generated internal HTML/image reference resolves inside the export root;
- no generated reference is absolute;
- no generated reference escapes the export root;
- no two different artefacts claim the same output path;
- every renderable Question expected by the corpus has its question asset;
- answer-region references exist when an Answer has regions;
- the Subject index exists.

A missing/corrupt source PDF for a Question that otherwise has stored regions is a data-integrity/export error.

Do not silently omit such a Question and report a clean export.

Likewise, if an Answer is persisted with answer regions but its source file cannot be rendered, fail clearly rather than silently changing a stored answer into "no answer".

---

# 12. Persistence Implications

No schema migration is planned for Sprint 05.

The exporter reads existing:

- curriculum hierarchy;
- question metadata;
- current applicability;
- question regions;
- answers;
- answer regions;
- managed relative source paths.

Generated revision numbers, rendered images, HTML paths and export timestamps are output artefacts and are not persisted back into SQLite.

If implementation reveals that required output semantics cannot be represented without new persistent state, stop and make that a deliberate design decision rather than adding columns opportunistically.

---

# 13. UI / Workflow

Add an application command such as:

`File -> Export Revision HTML...`

The workflow should:

1. choose a Subject;
2. choose an export destination/parent directory;
3. start export;
4. perform PDF rendering and file generation off the JavaFX application thread;
5. prevent duplicate simultaneous export actions from the same UI flow;
6. show an indeterminate progress state at minimum;
7. show success with output location and summary statistics;
8. show clear failure details when generation fails.

The currently selected Subject may be used as a default, but export should not silently depend on transient capture-form state.

Cancellation/progress-per-question is useful later but not required for Sprint 05 unless implementation makes it straightforward.

---

# 14. Error Behaviour

Examples of controlled failures:

- no current syllabus for selected Subject;
- more than one current syllabus;
- invalid curriculum hierarchy;
- output destination cannot be created;
- source question PDF missing;
- source question PDF corrupt/unrenderable;
- persisted answer region source missing/corrupt;
- image cannot be written;
- HTML/CSS cannot be written;
- generated reference validation fails;
- final staging promotion fails.

Zero-region metadata-only Questions are **not** export failures; they are incomplete corpus items and are counted/omitted deliberately.

No stored Answer is **not** an export failure.

---

# 15. Testing Strategy

Tests should focus on meaningful export behaviour rather than coverage percentage.

## 15.1 Corpus builder tests

Use small in-memory curriculum/retrieval fixtures to prove:

- one current syllabus is used;
- no current syllabus is handled;
- multiple current syllabuses fail;
- Unit/Topic/Subtopic/Descriptor order is deterministic;
- direct-current Question placement;
- confirmed historical-mapped placement;
- one historical Question mapped to multiple current nodes;
- no duplicate placement within one node;
- Subtopic-level applicability stays at Subtopic level;
- Topic Descriptor-mode structure;
- Topic Subtopic-mode structure;
- zero-region Question counted as incomplete;
- missing Answer counted but Question remains renderable;
- `preambleCaptureRequired` counted as review warning;
- unique-question counts are distinct from placement counts;
- generated placement numbering is deterministic.

## 15.2 Asset rendering tests

Prove:

- one-region Question PNG generation;
- multi-region Question ordering;
- deterministic question asset path;
- answer text does not require an image;
- answer-region asset paths preserve order;
- the same Question placed more than once reuses one question asset;
- missing source file fails clearly.

Existing `QuestionExtractor` tests remain the low-level crop/render proof; Sprint 05 tests should focus on output-layer behaviour.

## 15.3 HTML renderer tests

Prove:

- correct Subject/Unit/Topic navigation;
- Descriptor-mode rendering;
- Subtopic-mode rendering;
- generated numbering;
- marks;
- source attribution;
- HTML escaping for curriculum/source text;
- question image relative links;
- answer text rendering;
- ordered answer images;
- missing-answer message;
- no absolute managed-data paths;
- portable `/` link separators.

## 15.4 Export integration tests

Use a temporary output root and representative test PDFs.

Prove:

- complete directory contract;
- all generated references resolve;
- staging is not promoted when rendering fails;
- completed export contains no full source PDFs;
- mapped historical and direct-current Questions both appear;
- zero-region Questions are omitted from content but present in statistics;
- export can be generated from a fresh SQLite/repository/service instance.

## 15.5 UI tests

Keep UI coverage focused because of known TestFX sensitivity.

Prove at minimum:

- export menu action exists;
- Subject/destination validation;
- export runs off the FX thread;
- success path presents the output result;
- failure path presents a controlled error.

Do not broaden Sprint 05 into general TestFX stabilisation.

## 15.6 Real-data acceptance exercise

Before merge, run a real Chemistry export and inspect it in a normal browser.

Check:

- Subject index;
- every Unit;
- representative Topic in Descriptor mode;
- representative Topic in Subtopic mode;
- directly current Question;
- historical mapped Question;
- multi-region Question;
- MCQ text Answer;
- answer-region material;
- missing Answer behaviour;
- source attribution;
- image clarity;
- navigation/back links;
- no broken images;
- no absolute local paths;
- sensible layout on ordinary laptop-width browser windows.

Run the complete test suite at the final checkpoint.

---

# 16. Proposed Implementation Slices

Implementation should remain in small testable slices.

## Slice 0 — Documentation baseline

- confirm Sprint 04 merged state;
- correct stale Sprint 04 status in `current-status.md`, `roadmap.md` and `DEVELOPMENT_ROADMAP.md`;
- add this Sprint 05 design document;
- create `feature/revision-corpus-html-export`.

No production code change.

## Slice 1 — Transient corpus model and builder

Implement:

- corpus tree;
- placement semantics;
- statistics;
- deterministic ordering;
- zero-region/missing-answer/preamble-review states.

No HTML yet.

Focused service tests.

## Slice 2 — Derived question assets

Implement deterministic question PNG generation from stored question regions.

Reuse assets for repeated placements.

Focused asset tests.

## Slice 3 — Answer assets

Add:

- answer text handling;
- ordered answer-region PNG generation;
- missing-answer state.

Focused tests.

## Slice 4 — Static HTML hierarchy

Generate:

- CSS;
- Subject index;
- Unit pages;
- Topic pages;
- curriculum sections;
- question cards;
- answer disclosure;
- provenance/navigation.

Focused renderer tests.

## Slice 5 — Export orchestration and validation

Add:

- staging;
- complete export service;
- reference validation;
- result/statistics;
- failure cleanup.

Integration tests.

## Slice 6 — JavaFX export workflow

Add:

- File menu command;
- Subject selection;
- destination selection;
- background execution;
- success/failure UI.

Focused UI tests.

## Slice 7 — Real-data acceptance and documentation

- export representative Chemistry corpus;
- inspect in browser;
- fix genuine layout/data-output defects;
- run full suite;
- update Sprint 05 outcome/status docs;
- perform merge-readiness review.

---

# 17. Acceptance Criteria

Sprint 05 is complete when all of the following are true.

## Corpus

- A Subject's single current syllabus is traversed deterministically.
- Current placement reuses Sprint 03 retrieval/applicability semantics.
- Historical provenance remains unchanged.
- Direct-current and confirmed historical-mapped Questions are supported.
- A Question may appear under several valid current nodes, but never twice within the same node.
- Subtopic-level applicability remains at Subtopic precision.
- Zero-region Questions are identified and omitted from student question content.
- Missing Answers do not block Question export.
- Preamble-review hints are counted and surfaced to the exporting user.

## Rendering

- Every rendered Question uses its stored ordered regions.
- Stored Answer text is shown.
- Stored Answer regions are rendered in order.
- Derived assets use deterministic internal paths.
- Full source PDFs are not copied into the export.
- Image quality is acceptable in a real browser against representative Chemistry material.

## HTML

- `index.html` opens directly.
- Subject -> Unit -> Topic navigation works using relative links.
- Topic pages represent the current hierarchy correctly.
- Question cards use deterministic generated numbering.
- Source attribution is visible.
- Answers can be revealed without external services.
- Missing-answer behaviour is clear.
- No page requires the desktop application or the managed source tree after export.

## Safety/reliability

- Export occurs off the JavaFX application thread.
- Generation is staged.
- Broken/incomplete generation is not reported as success.
- Required output references are validated.
- Missing/corrupt authoritative source files fail clearly.
- No SQLite schema change is introduced without a separate deliberate design decision.

## Quality gate

- focused unit tests green;
- export integration tests green;
- focused UI tests green;
- real Chemistry browser acceptance completed;
- complete test suite green;
- GitHub diff inspected;
- merge-readiness review completed;
- documentation updated to reflect actual Sprint 05 outcome.

---

# 18. Risks and Deliberately Deferred Work

## Multiple original classifications

The current one-best-fit `Question` classification remains a known design gap.

Sprint 05 must not hard-code assumptions that would make future many-to-many original classification unnecessarily difficult, but implementing that change is not part of this sprint.

## Shared stems / multipart questions

`preambleCaptureRequired` is only a hint.

Sprint 05 does not invent shared-content persistence.

Real-data acceptance should specifically inspect flagged questions so the limitation is understood.

## Duplicate appearance through valid mapping

One historical Question may legitimately appear under several current nodes.

This is expected corpus behaviour, not a retrieval defect.

## Output size

Raster assets may become substantial as the corpus grows.

Do not optimise prematurely.

Record real export size and generation behaviour during Chemistry acceptance. Optimise only if the result is operationally problematic.

## Web image quality

The current PDFBox rendering quality is expected to be sufficient for the first static revision export but must be visually checked.

Print/vector requirements are separate later work.

## HTML structure and Sprint 06

Sprint 05 should produce clean static relative-link content that can later be packaged, but it must not contain SCORM-specific assumptions merely to anticipate Sprint 06.

Sprint 06 is responsible for choosing the SCORM profile, manifest organisation and QLearn-specific compatibility rules.

---

# 19. Sprint Exit

The sprint ends with a working static revision export from the desktop application.

A successful demonstration is:

```text
open Exam Question Bank
    ↓
Export Revision HTML
    ↓
choose Chemistry
    ↓
choose destination
    ↓
application builds current-curriculum corpus
    ↓
application renders question/answer assets
    ↓
application writes and validates static HTML
    ↓
open generated index.html in browser
    ↓
navigate Unit -> Topic -> curriculum section
    ↓
view captured questions
    ↓
reveal available answers
```

The next sprint begins from this tested static content layer and adds SCORM packaging and real QLearn validation.
