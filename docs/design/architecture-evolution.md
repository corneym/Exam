# Exam Question Bank — Architecture Evolution

> Major architectural changes and superseded designs through 6 September 2026.
>
> This file explains why the application changed shape. Use `../current-status.md` for the authoritative present-state summary.

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

Legacy image snips should not require an immediate destructive conversion. Migration can be staged. The current application, however, has been developed primarily around recovering metadata and source PDFs rather than making legacy snip files the new canonical representation.

## 4. Source paths: per-question file names -> managed portable document relationships

### Initial improvement

`PdfStore` established one configured root and safe relative path resolution.

### Domain improvement

**SUPERSEDED**

`Question` directly carrying PDF path/page responsibility.

**IMPLEMENTED / CURRENT**

Source ownership moved through document/exam/booklet relationships:

```text
managed source document
 -> Exam
 -> ExamBooklet
 -> Question
```

This avoids repeating the same path across every question and makes zero-region imported questions possible once booklet ownership is explicit.

## 5. Regions: pixel rectangles -> normalized coordinates

### Prototype

**SUPERSEDED**

Pixel-space crop rectangles tied persistent identity to a particular render size.

### Current

**IMPLEMENTED / CURRENT**

`QuestionRegion` uses normalized proportional coordinates with one-based domain page numbers.

The PDF boundary alone knows PDFBox zero-based indexing and render specifics.

This is both a persistence decision and a separation-of-concerns decision.

## 6. PDF lifecycle: direct PDFBox use -> `PdfSession`

### Problem

A JavaFX viewer navigating many pages should not reopen PDFs for every action or expose PDFBox resource management throughout the UI.

### Current boundary

**IMPLEMENTED / CURRENT**

`PdfSession` owns document opening, page count, rendering and close lifecycle. `QuestionExtractor` works through that boundary.

JavaFX receives images/region results, not raw PDFBox document lifecycle responsibilities.

## 7. Output architecture split by delivery medium

### Static web/SCORM

**DECIDED / FUTURE CURRENT-GENERATION TARGET**

```text
PDF + stored regions + database
        |
        v
PDFBox build/export rendering
        |
        +--> PNG/web assets
        +--> static HTML
        +--> SCORM ZIP
```

Browser-side PDF.js rendering was considered and rejected for the primary static resource. Full source PDFs should not normally be included in the SCORM package.

### Printable PDF

**DECIDED / FUTURE CURRENT-GENERATION TARGET**

The preferred print path is vector-preserving source clipping where practical, for example LaTeX `graphicx` page/viewport clipping against the original PDF. This avoids unnecessary rasterization of notation and diagrams.

The legacy application already generated LaTeX and externally compiled PDFs; the current application has not yet rebuilt that full print pipeline.

## 8. Repository evolution: smoke-test/in-memory -> SQLite

### First vertical slice

**IMPLEMENTED / SUPERSEDED AS RUNTIME**

- `QuestionRepository` abstraction;
- `InMemoryQuestionRepository`;
- `Main` smoke tests;
- in-memory curriculum repository.

These allowed rapid domain work without premature schema coupling.

### Current persistence

**IMPLEMENTED / CURRENT**

SQLite is the runtime database with foreign-key enforcement and transactional migrations.

Excel remains an import/exchange format.

## 9. UI architecture: monolithic composition -> specialised panes/services

### Early state

The first JavaFX slice placed increasing orchestration inside `QuestionBankApplication`.

### Refactor before persistence

**IMPLEMENTED / CURRENT DIRECTION**

On `refactor/application-structure`, responsibilities were split into specialised panes and validators such as:

- `QuestionCapturePane`;
- `AnswerCapturePane`;
- `ExamMetadataPane`;
- `PdfWorkspacePane`;
- `PdfFilePicker`;
- `SelectedPdf`;
- capture validators;
- curriculum selection factory/model support.

### Reason

SQLite persistence and application services should not depend on transient JavaFX control layout or a single oversized application class.

## 10. Configuration: PDF-only property -> one managed `data.root`

### Earlier

An early `ApplicationConfig` required `pdf.dataRoot` and loaded properties relative to the working directory.

### Current

**IMPLEMENTED / CURRENT**

One `data.root` derives the managed PDF directory, curriculum directory and SQLite file. Legacy properties remain readable.

Viewer mode can open arbitrary external PDFs without treating them as managed data; Exam Import explicitly copies external source PDFs into the managed root before persistence.

This cleanly separates “look at a file” from “make this file durable application data”.

## 11. Curriculum: one active hierarchy -> versioned histories

### Legacy/early limitation

The old generator largely consumed one loaded syllabus hierarchy and could not robustly preserve original historical context while also supporting a current curriculum.

### Current

**IMPLEMENTED / CURRENT**

Curriculum is explicit versioned data.

Historical question classification remains provenance. Current applicability is derived through mappings.

## 12. Mapping semantics: simple crosswalk -> directional reviewed graph

### Early mapping idea

Relationship labels such as equivalent, split, merged, partial, removed and new were discussed to represent real curriculum change.

### Current application semantics

**IMPLEMENTED / CURRENT**

- historical source -> current target;
- same Subject;
- different syllabus versions;
- same supported curriculum level;
- Descriptor -> Descriptor and Subtopic -> Subtopic;
- one-to-many allowed;
- only confirmed mappings authoritative.

The current retrieval engine does not require a separate persisted `SPLIT`/`MERGED` label to operate; whether richer relation-type metadata from the standalone mapping workbook should be persisted remains a future design choice.

## 13. Classification: early many-to-many requirement -> current one-best-fit model

### Early evidence/design

**DECIDED EARLY**

A real Neap question was classified to three descriptors, and the early domain discussion proposed:

```text
Question <-> SyllabusDescriptor
```

as many-to-many.

### Current implementation

**IMPLEMENTED / CURRENT**

`Question` stores one best-fit original Subtopic or Descriptor classification.

### Architectural status

**UNRESOLVED EVOLUTION / BACKLOG**

No available evidence establishes a deliberate product decision that questions can never have multiple original classifications. This is therefore not safe to describe simply as a superseded requirement.

Future work must decide whether:

1. one best-fit classification is the intended permanent model; or
2. the domain/persistence/retrieval/export layers need multiple original classifications.

Any change must preserve historical provenance and avoid conflating multiple original classifications with multiple derived current-applicability nodes.

## 14. Assessment ownership: exam-level ambiguity -> booklet ownership

### Earlier uncertainty

Pre-sprint persistence discussions considered uniqueness around `(exam_id, question_code)` and questioned where paper/booklet ownership belonged.

### Current

**IMPLEMENTED / CURRENT**

```text
Question -> ExamBooklet -> Exam
```

Natural identity is `(booklet_id, question_code)`.

Paper is represented by the booklet; it is not duplicated as a generic question type.

## 15. Imported questions: mandatory regions -> staged capture

### Earlier normal-capture assumption

A saved captured question naturally had regions because capture began from an open PDF.

### Legacy import requirement

**IMPLEMENTED / CURRENT**

Metadata may exist before regions. A persisted zero-region question is therefore valid for legacy import, while the normal manual capture workflow still requires at least one selected region.

Placeholder regions were rejected.

This creates an explicit staged lifecycle instead of pretending incomplete data is complete.

## 16. Answer architecture: file convention -> explicit answer aggregate

### Legacy

- MCQ answer = text letter in workbook;
- written answer = image snip;
- optional separate preamble image.

### Current

**IMPLEMENTED / CURRENT**

Explicit `Answer`, `AnswerFile` and `AnswerRegion` structures allow text and/or source-region answer material.

Blank legacy written-answer cells do not prove “no answer”, so fake empty answers and an unnecessary early tri-state availability model were avoided.

## 17. Preamble/shared context: several designs, only the hint is settled

### Legacy

`MultiPartQuestion` could own a shared preamble and render it once.

### Early redevelopment design

Rich concepts were discussed for shared content and dependencies, including possible STEM/SHARED_DATA/TABLE/GRAPH/DIAGRAM/PART/SUBPART region roles, part dependencies and “keep together” behaviour.

### Pre-Sprint-01 persistence proposal

A dedicated `QuestionPreamble` with ordered preamble regions was proposed.

### Implemented Sprint-01 design

**SUPERSEDED / NOT ADOPTED — required dedicated `QuestionPreamble`.**

The implemented model retained only `preambleCaptureRequired` as a legacy capture hint. Import does not infer grouping, create placeholder preamble regions or assume a preamble means exactly two regions.

### Current unresolved requirement

**PROPOSED / BACKLOG**

Generalized shared-context semantics remain open. A future design may use repeated ordinary regions, shared source sections, or another explicit relationship. UI “pin and reuse” is a workflow convenience, not established persistent state.

## 18. Capture interaction: several previews -> one consistent accepted-region pattern

### Earlier

Question and Answer workflows diverged: Question had a separate combined preview while Answer had Undo.

### Current direction

**SUPERSEDED**

- Answer Undo;
- permanent separate combined-question preview.

**IMPLEMENTED / CURRENT**

Both use current selection + Add/Clear + accepted-region list + per-region Remove. Visual details can differ without duplicating interaction semantics.

A generic shared component was deliberately deferred until repetition justified abstraction.

## 19. Package boundaries: flat growth -> persistence-oriented packages

**IMPLEMENTED / CURRENT**

Package reorganisation grouped actual transactional dependencies rather than widening visibility to achieve cosmetic structure.

Notably:

- SQLite infrastructure stayed together;
- curriculum repositories/import/mapping persistence stayed together;
- assessment persistence stayed together;
- UI was deliberately not split into `ui.curriculum` during that pass;
- Preferences-backed repository location was preserved to avoid losing existing user settings.

## 20. Retrieval architecture: stored classification -> derived applicability search

### Problem

Historical provenance and present usefulness answer different questions.

### Current

**IMPLEMENTED / CURRENT**

The stored question retains its original classification; a retrieval service derives current applicability through confirmed mappings and explicit hierarchy expansion.

Search scopes may be Subject/Unit/Topic/Subtopic/Descriptor even though persisted question classification remains Subtopic/Descriptor.

This is the architectural bridge between curriculum history and reusable current resources.

## 21. Product strategy: Exam Builder first -> revision export first

### Earlier direction

Assessment assembly and PDF output were central long-term goals inherited from the legacy system.

### Current priority

**SUPERSEDED IN PRIORITY**

Exam Builder is no longer the immediate next feature.

**DECIDED / CURRENT**

The near-term path is:

```text
data safety
 -> current-curriculum corpus
 -> static HTML/assets
 -> SCORM
 -> QLearn
```

Exam Builder and printable assessment generation remain later consumers of the same question bank.

## 22. Deployment architecture: local desktop first

**DECIDED / CURRENT DIRECTION**

SQLite keeps Version 1 realistic for a school desktop environment without requiring a hosted DB server.

**REJECTED**

Treating a SharePoint-synchronised/network SQLite file as a safe concurrently edited multi-user database.

**PROPOSED / LATER**

- controlled import/export/merge between coordinators;
- IT-supported central DB if required;
- SharePoint/Graph integration;
- `jpackage` self-contained installer;
- writable data root outside installation.

## 23. Future non-PDF content: source-region model -> attachment extension

The PDF-region architecture assumes a durable source document. Some future questions may instead originate from transient web pages or arbitrary documents captured by Windows Snipping Tool.

**PROPOSED**

Allow question content to include text plus zero or more image attachments obtained from clipboard or drag/drop.

Possible persistence:

- SQLite BLOBs; or
- application-managed image files with DB metadata.

No choice has been made.

This should extend the content model without weakening PDF provenance for normal exam questions. OCR remains optional separate future work.

## 24. Data safety becomes a first-class architecture boundary

**PROPOSED / NEXT**

Sprint 04 treats backup/restore as recoverability, not file copying:

- consistent SQLite snapshots;
- versioned backup contract;
- frequent DB backups and separate full archives;
- staging/validation;
- restore protection;
- application restart after restore unless safe complete reinitialisation is later proven.

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
| Preamble implies expected region count | **REJECTED** | independent capture hint |
| Required dedicated `QuestionPreamble` entity | **NOT ADOPTED** | hint implemented; generalized shared context unresolved |
| Tri-state answer availability for import | **DEFERRED/UNNECESSARY FOR SPRINT 01** | actual answer data or unknown state |
| Direction-neutral mapping | **SUPERSEDED** | explicit historical -> current |
| Descriptor mappings alone | **SUPERSEDED AS SUFFICIENT** | Descriptor + Subtopic mappings |
| Mapped Subtopic automatically implies all child Descriptors | **REJECTED** | preserve source precision |
| Answer Undo interaction | **SUPERSEDED** | accepted list + per-region Remove |
| Permanent combined Question preview | **SUPERSEDED** | current selection + accepted list |
| Browser-side PDF rendering for SCORM | **REJECTED TARGET** | build-time PDFBox web assets |
| Include full source PDFs in SCORM | **REJECTED DEFAULT** | derived portable web assets |
| Exam Builder as immediate critical path | **SUPERSEDED IN PRIORITY** | revision/SCORM first |
| Live concurrent SharePoint/network SQLite | **REJECTED** | local SQLite + backups/controlled sharing; server later if needed |

### Not safely classed as superseded

The early **multi-descriptor original classification requirement** is not listed as superseded because the current single-best-fit implementation does not, by itself, prove the requirement was deliberately withdrawn. It remains a design decision to revisit.
