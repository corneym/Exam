# Exam Question Bank — Architecture Evolution

> Major architectural changes and superseded designs through 7 September 2026.
>
> This file explains why the application changed shape. Use `../current-status.md` for the authoritative present-state implementation summary. A design decision may be recorded here before implementation when its status is stated explicitly.

## 1. Legacy architecture: Excel + named snips + generators

### Implemented predecessor

```text
Excel syllabus data
        +
Excel exam/question/classification data
        +
PNG/JPG question, answer and preamble snips
        |
        v
Java hierarchy
        |
        +--> LaTeX -> external PDF generation
        +--> hierarchical static HTML
        +--> SCORM-style ZIP
```

This solved a real publishing problem but made the filesystem/workbooks the de facto database.

### Why it was replaced

**DECIDED**

The redevelopment needed:

- durable relational metadata;
- authoritative source-document provenance;
- curriculum version history;
- historical-to-current mapping;
- region editing without recreating source assets;
- reusable retrieval independent of a particular output generator.

## 2. Early batching prototype exposed hidden requirements

Before the new application existed, 25 questions from a representative 2021 Neap MCQ paper were classified manually.

### What that proved

**DECIDED / EVIDENCED**

- source formatting should be preserved visually;
- generated resources need independent sequential numbering plus original source attribution;
- a source question may legitimately fit several descriptors;
- some source questions may be out of scope for the target curriculum/resource;
- questions and solutions need durable logical linking.

This early evidence remains relevant because the current persisted `Question` later simplified original classification to one best-fit node.

## 3. Canonical content: image snips -> immutable PDFs + regions

### Earlier approach

**SUPERSEDED**

Named PNG/JPG snips were canonical content.

### Current PDF-based approach

**DECIDED / IMPLEMENTED / CURRENT**

```text
managed source PDF
+ owning booklet
+ page
+ normalized rectangle(s)
```

Advantages:

- source remains authoritative;
- no dependence on hand-maintained filenames;
- crop boundaries can change without replacing source;
- vector source content remains available for later print output;
- web/preview images can be regenerated.

Generated images are derived artefacts.

### Legacy compatibility

**DECIDED EARLY / NOT A CURRENT CORE MODEL REQUIREMENT**

Legacy image snips do not require destructive conversion merely for architectural purity. The current application has been developed primarily around recovering metadata and authoritative source PDFs.

## 4. Source paths: per-question file names -> managed portable document relationships

### Earlier

**SUPERSEDED**

`Question` directly carrying PDF path/page responsibility.

### Current

**IMPLEMENTED / CURRENT**

```text
managed source document
 -> Exam
 -> ExamBooklet
 -> Question
```

This avoids repeating paths and makes zero-region imported questions possible once booklet ownership is explicit.

## 5. Regions: pixel rectangles -> normalized coordinates

### Prototype

**SUPERSEDED**

Pixel-space rectangles tied persistence to a particular render size.

### Current

**IMPLEMENTED / CURRENT**

`QuestionRegion` uses normalized proportional coordinates and one-based domain page numbers.

The PDF boundary alone handles PDFBox zero-based indexing and rendering details.

## 6. PDF lifecycle: direct PDFBox use -> `PdfSession`

**IMPLEMENTED / CURRENT**

`PdfSession` owns document opening, page count, rendering and close lifecycle. `QuestionExtractor` works through that boundary.

JavaFX receives images/region results rather than raw PDFBox lifecycle responsibilities.

## 7. Output architecture split by delivery medium

### Static web/SCORM

**DECIDED / IMPLEMENTED / CURRENT**

```text
PDF + stored regions + database
        |
        v
PDFBox export-time rendering
        |
        +--> PNG/web assets
        +--> static HTML
        +--> SCORM 1.2 ZIP
```

Browser-side PDF.js rendering was rejected as the primary static-resource architecture.

Full authoritative source PDFs are not normally included in SCORM.

Sprint 05 implemented the static revision-content boundary.

Sprint 06 implemented SCORM 1.2 packaging over that content and achieved successful QLearn import/launch acceptance.

### Printable PDF

**DECIDED / FUTURE DIRECTION**

Prefer vector-preserving source clipping where practical, for example LaTeX `graphicx` page/viewport clipping against the original PDF.

The current application has not rebuilt the full legacy print pipeline.

## 8. Repository evolution: smoke-test/in-memory -> SQLite

### First vertical slice

**IMPLEMENTED / SUPERSEDED AS RUNTIME**

- `QuestionRepository`;
- in-memory question repository;
- in-memory curriculum repository;
- early smoke tests.

### Current persistence

**IMPLEMENTED / CURRENT**

SQLite is the runtime database with foreign-key enforcement and transactional migrations.

Excel remains import/exchange only.

## 9. UI architecture: monolithic composition -> specialised panes/services

**IMPLEMENTED / CURRENT DIRECTION**

Responsibilities were split into specialised panes and validators including:

- `QuestionCapturePane`;
- `AnswerCapturePane`;
- `ExamMetadataPane`;
- `PdfWorkspacePane`;
- `PdfFilePicker`;
- `SelectedPdf`;
- capture validators;
- curriculum selection factory/model support.

Reason:

Persistence and application services should not depend on transient JavaFX layout or a single oversized application class.

Sprint 07 retains these responsibility boundaries while redesigning the capture workspace and selection lifecycle.

## 10. Configuration: PDF-only property -> one managed `data.root`

**IMPLEMENTED / CURRENT**

One `data.root` derives the managed PDF directory, curriculum directory and SQLite file.

Viewer mode can open arbitrary external PDFs without treating them as managed data. Exam Import explicitly brings durable exam PDFs into the managed root.

## 11. Curriculum: one active hierarchy -> versioned histories

**IMPLEMENTED / CURRENT**

Curriculum is explicit versioned data.

Historical question classification remains provenance. Current applicability is derived through mappings.

## 12. Mapping semantics: simple crosswalk -> directional reviewed graph

**IMPLEMENTED / CURRENT**

- historical source -> current target;
- same Subject;
- different syllabus versions;
- same supported curriculum level;
- Descriptor -> Descriptor and Subtopic -> Subtopic;
- one-to-many allowed;
- only confirmed mappings authoritative.

The retrieval engine does not require persisted SPLIT/MERGED labels to operate.

Whether richer relation-type/confidence/notes metadata should be persisted remains future work.

## 13. Classification: early many-to-many requirement -> current one-best-fit model

### Early evidence/design

**DECIDED EARLY**

A real Neap question was classified to three descriptors, and early design proposed:

```text
Question <-> SyllabusDescriptor
```

as many-to-many.

### Current implementation

**IMPLEMENTED / CURRENT**

`Question` stores one best-fit original Subtopic or Descriptor classification.

### Architectural status

**UNRESOLVED EVOLUTION / BACKLOG**

No evidence establishes a deliberate permanent decision that questions can never have multiple original classifications.

Future work must decide whether:

1. one-best-fit remains permanent policy; or
2. domain/persistence/retrieval/export need multiple original classifications.

Sprint 07 deliberately does not alter the unresolved **multiple original classifications** question.

### Sprint 07 capture-hierarchy decision

**DECIDED / SCHEDULED — NOT YET IMPLEMENTED**

The built-in Question classification panel must no longer assume a fixed hierarchy shape.

It will consume the selected syllabus branch:

```text
Unit -> Topic -> Descriptor
```

or:

```text
Unit -> Topic -> Subtopic -> Descriptor
```

and show only the levels actually present.

A Descriptor control is shown where Descriptor exists.

Classification may stop at Subtopic even when Descriptor children exist.

Classification may not stop at Topic when that Topic has direct Descriptor children; a Descriptor must then be selected.

This changes question-capture UI/validation, not curriculum import, mapping, applicability or retrieval semantics.

## 14. Assessment ownership: exam-level ambiguity -> booklet ownership

**IMPLEMENTED / CURRENT**

```text
Question -> ExamBooklet -> Exam
```

Natural identity is:

```text
(booklet_id, question_code)
```

Paper/booklet identity is not duplicated as a generic question type.

## 15. Imported questions: mandatory regions -> staged capture

**IMPLEMENTED / CURRENT**

Persisted metadata may exist before question regions.

A zero-region question is therefore valid for legacy import while ordinary manual capture still requires at least one accepted region.

Placeholder regions were rejected.

Sprint 07 extends staged completeness to include an explicit unresolved shared-context condition without invalidating metadata-only import.

## 16. Answer architecture: file convention -> explicit answer aggregate

### Legacy

- MCQ answer = text letter in workbook;
- written answer = image snip;
- optional separate preamble image.

### Current

**IMPLEMENTED / CURRENT**

Explicit `Answer`, `AnswerFile` and `AnswerRegion` structures allow text and/or source-region answer material.

Blank legacy written-answer cells do not prove “no answer”, so fake empty answers were avoided.

Sprint 07 will add correction/update workflow without changing the core answer aggregate.

## 17. Preamble/shared context and multipart identity

### Legacy

The predecessor application had a `MultiPartQuestion` concept.

It:

- treated parts such as `21a`, `21b`, `21c` as belonging to source question `21`;
- grouped matching parts inside the classification bucket;
- rendered a multipart preamble once;
- accumulated member-part marks.

It did not provide a general persisted shared-context model for otherwise independent questions such as several MCQs.

### Early redevelopment alternatives

Possible designs included:

- repeated ordinary regions;
- a dedicated preamble object;
- generic shared source sections;
- ordered shared blocks;
- dependency relationships;
- keep-together flags;
- region-role taxonomies.

### Sprint 01 implementation

**IMPLEMENTED / CURRENT THROUGH SCHEMA V4**

Only the Boolean legacy evidence flag `preambleCaptureRequired` was persisted.

Import deliberately did not infer grouping or create placeholder preamble data.

### Sprint 07 design decision

**DECIDED / SCHEDULED — NOT YET IMPLEMENTED**

Sprint 07 resolves the prior ambiguity with two separate persisted concepts:

```text
SourceQuestion
```

for common original source-question identity, and:

```text
SharedQuestionContext
```

for reusable introductory material.

The concepts are intentionally independent.

Example:

```text
SourceQuestion "21"
├── Question "21a"
├── Question "21b"
└── Question "21c"

SharedQuestionContext "Question 21 preamble"
├── context region 1
└── context region 2

21a ──► shared context
21b ──► shared context
21c ──► shared context
```

A single shared context may also link otherwise independent questions without converting them into one multipart question.

### Persisted scope selected for Sprint 07

**DECIDED**

- source-question membership is explicit;
- question-code parsing may suggest but not silently create membership;
- one shared-context link per question;
- one shared context may contain several ordered regions;
- one shared context may be reused by many questions;
- shared context is booklet-scoped;
- ordinary multi-region questions remain a separate concept;
- no persisted UI `pinned` flag;
- no general dependency graph is introduced;
- no redundant multipart total-marks field is stored.

### Legacy migration/import rule

**DECIDED**

Neither migration nor import may infer:

```text
21a -> SourceQuestion 21
```

or a shared-context relationship merely from:

```text
preamble_capture_required = 1
```

The legacy flag remains evidence.

Operational unresolved state is derived when the flag is true and no shared context is explicitly linked.

### Output rule

**DECIDED**

Multipart grouping is performed inside the final current-curriculum output bucket.

If `21a` and `21c` occur in one bucket and `21b` in another:

- `21a` + `21c` form one displayed multipart question in the first bucket;
- `21b` is displayed separately in the second;
- shared context is repeated across buckets where needed;
- grouped marks are the sum of included member-part marks.

Shared context alone does not create multipart grouping.

Sprint 07 includes the required HTML revision-output change. SCORM continues to package that static content rather than implementing its own grouping rules.

## 18. Capture interaction: accepted-region pattern -> explicit selection ownership

### Previous direction

**IMPLEMENTED THROUGH SPRINT 06**

Question and Answer capture both use:

- current selection;
- Add/Clear;
- accepted-region list;
- per-region Remove.

### Problem exposed by real use

Both panes can invoke the shared PDF workspace selection clear operation.

This permits a Question-side clear action to disturb an Answer-side selection and allows some transitions to discard unaccepted selections too silently.

### Sprint 07 decision

**DECIDED / SCHEDULED**

Introduce explicit transient selection ownership:

```text
QUESTION
SHARED_CONTEXT
ANSWER
```

Only the owner may clear its selection.

Guard transitions that would discard an unaccepted rectangle.

Do not persist selection ownership or pin state.

## 19. Package boundaries: flat growth -> persistence-oriented packages

**IMPLEMENTED / CURRENT**

Package reorganisation follows actual transactional responsibilities rather than widening visibility for cosmetic structure.

Notably:

- SQLite infrastructure remains together;
- curriculum persistence remains grouped;
- assessment persistence remains grouped;
- UI remains separated from persistence/domain logic.

Sprint 07 should preserve these boundaries.

## 20. Retrieval architecture: stored classification -> derived applicability search

**IMPLEMENTED / CURRENT**

The stored question retains its original classification.

Retrieval derives current applicability through confirmed mappings and explicit hierarchy expansion.

Search scopes may be Subject/Unit/Topic/Subtopic/Descriptor even though persisted classification remains Subtopic/Descriptor.

### Sprint 07 boundary

**DECIDED**

Multipart/shared-context grouping happens after retrieval has established the final output bucket.

Do not push multipart grouping into curriculum retrieval or mapping logic.

## 21. Product strategy: Exam Builder first -> revision export first

### Earlier direction

Assessment assembly and PDF output were central inherited goals.

### Current priority

**SUPERSEDED IN PRIORITY**

Exam Builder is not the immediate critical path.

**IMPLEMENTED PATH THROUGH SPRINT 06**

```text
data safety
 -> current-curriculum corpus
 -> static HTML/assets
 -> SCORM
 -> QLearn
```

### Next

**DECIDED**

Sprint 07 improves the bank/capture/presentation semantics before moving to richer bank administration or Exam Builder.

## 22. Deployment architecture: local desktop first

**DECIDED / CURRENT DIRECTION**

SQLite keeps Version 1 realistic for a school desktop environment without requiring a hosted DB server.

**REJECTED**

A SharePoint-synchronised/network SQLite file is not treated as a safe concurrently edited multi-user database.

**PROPOSED / LATER**

- controlled import/export/merge;
- IT-supported central DB if required;
- SharePoint/Graph integration;
- `jpackage` self-contained installer;
- writable data root outside installation.

## 23. Future non-PDF content: source-region model -> attachment extension

The PDF-region architecture assumes a durable source document.

Some future questions may originate from transient web pages/documents captured by Windows Snipping Tool.

**PROPOSED**

Allow text plus zero or more image attachments obtained from clipboard or drag/drop.

Possible persistence:

- SQLite BLOBs; or
- managed image files with DB metadata.

No choice has been made.

This must extend rather than weaken PDF provenance.

## 24. Data safety becomes a first-class architecture boundary

### Earlier

**PROPOSED / SPRINT 04**

Backup/restore was elevated from file copying to recoverability.

### Current

**IMPLEMENTED / CURRENT**

Sprint 04 established:

- consistent SQLite snapshots;
- versioned backup contract;
- frequent DB backups and separate full archives;
- staging/validation;
- restore protection;
- application restart boundary after restore;
- retention.

Schema v5 data introduced by Sprint 07 will use the same backup/restore architecture; the backup model itself is not redesigned.

## 25. Superseded/rejected design register

| Earlier approach | Status | Current replacement / status |
|---|---|---|
| Excel as live database | **SUPERSEDED** | SQLite runtime; Excel import/exchange |
| PNG/JPG snips as canonical new content | **SUPERSEDED** | managed PDFs + normalized regions |
| Pixel crop coordinates | **SUPERSEDED** | normalized proportional coordinates |
| Question directly owning PDF path/page | **SUPERSEDED** | managed document/exam/booklet ownership |
| In-memory repository as runtime | **SUPERSEDED** | SQLite repositories |
| Non-modular JavaFX workaround | **SUPERSEDED** | proper Java module |
| Classification subject directly drives Exam metadata | **SUPERSEDED** | Exam Import owns subject; incompatible classification invalidates exam |
| `(exam_id, question_code)` as likely uniqueness | **SUPERSEDED** | `(booklet_id, question_code)` |
| Placeholder regions for imported questions | **REJECTED** | valid zero-region capture-pending questions |
| Preamble implies expected region count | **REJECTED** | explicit shared-context semantics; legacy flag is evidence only |
| Required dedicated `QuestionPreamble` as the only model | **NOT ADOPTED** | Sprint 07 separates `SourceQuestion` and `SharedQuestionContext` |
| Generic dependency graph for Sprint 07 | **REJECTED AS PREMATURE** | explicit source-question + one linked shared context per question |
| Infer multipart membership by stripping question-code suffixes | **REJECTED AS AUTHORITATIVE DATA** | parsing may suggest; user-confirmed relationship is persisted |
| Persist multipart total marks | **REJECTED** | derive sum from included member parts |
| Persist UI pin/reuse state | **REJECTED** | transient UI convenience only |
| Tri-state answer availability for import | **DEFERRED/UNNECESSARY FOR SPRINT 01** | actual answer data or unknown state |
| Direction-neutral mapping | **SUPERSEDED** | explicit historical -> current |
| Descriptor mappings alone | **SUPERSEDED AS SUFFICIENT** | Descriptor + Subtopic mappings |
| Mapped Subtopic automatically implies all child Descriptors | **REJECTED** | preserve source precision |
| Answer Undo interaction | **SUPERSEDED** | accepted list + per-region Remove |
| Permanent combined Question preview | **SUPERSEDED** | current selection + accepted list |
| Browser-side PDF rendering for SCORM | **REJECTED TARGET** | build-time PDFBox web assets |
| Include full source PDFs in SCORM | **REJECTED DEFAULT** | derived portable web assets |
| Separate multipart logic in SCORM | **REJECTED** | SCORM packages revision presentation |
| Exam Builder as immediate critical path | **SUPERSEDED IN PRIORITY** | revision/SCORM first, then bank semantics |
| Live concurrent SharePoint/network SQLite | **REJECTED** | local SQLite + backups/controlled sharing; server later if needed |

### Not safely classed as superseded

The early **multi-descriptor original classification requirement** is not listed as superseded because the current single-best-fit implementation does not prove that requirement was deliberately withdrawn.

It remains a separate design decision after Sprint 07.
