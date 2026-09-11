# Exam Question Bank — Current Status

> Authoritative project status at 11 September 2026.
>
> Current development branch: `feature/preamble-capture`.
>
> Final Sprint 07 merge-readiness review performed: 11 September 2026.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by
SQLite. It manages original exam and marking PDFs, versioned curriculum data,
legacy metadata import, question and answer capture from PDF regions,
historical-to-current curriculum mapping, current-curriculum retrieval,
revision HTML generation, SCORM 1.2 packaging, backup/restore, and correction of
persisted Questions and Answers.

Sprint 07 implementation and closeout are complete on
`feature/preamble-capture`. The sprint introduced persisted
source-question identity and reusable shared question context, preamble-aware
capture, multipart presentation semantics, syllabus-sensitive classification,
Question/Answer correction, capture-workflow protection, and a substantial set
of UI and responsiveness improvements.

The application is no longer an Excel-backed generation pipeline. Excel is an
import/exchange format. SQLite is the live datastore. Original PDFs are the
authoritative source material; extracted/cropped images are derived content.

The latest supported SQLite schema version is **6**.

## Implemented and current

### Application foundation

**IMPLEMENTED / CURRENT**

- Maven-based Java application using Java 25.
- Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox for PDF loading, rendering and region extraction.
- SQLite persistence with foreign-key enforcement.
- Transactional schema creation and migration.
- JUnit and TestFX coverage.
- Repository/service/importer/PDF/output/UI package separation.
- Repository guidance for Codex in `AGENTS.md`.

### Configuration and managed data root

**IMPLEMENTED / CURRENT**

One configured `data.root` derives managed application locations including:

```text
pdf/
curriculum/
questionbank.db
```

Persisted document paths are portable paths beneath the managed data root rather
than machine-specific absolute paths.

Legacy configuration remains readable. Data-root changes take effect after
restart.

### Database schema and persistence

**IMPLEMENTED / CURRENT — schema version 6**

The schema supports:

- subjects and syllabus versions;
- generic curriculum hierarchy nodes;
- examination providers, exams and booklets;
- managed source documents;
- questions and ordered question regions;
- answer files, answers and ordered answer regions;
- historical-to-current curriculum mappings;
- persisted `SourceQuestion` identity;
- persisted `SharedQuestionContext` and ordered shared-context regions;
- nullable Question links to SourceQuestion/shared context;
- persisted source-question `preamble_status` with `UNKNOWN`, `NONE` and
  `PRESENT` states.

Migration preserves existing data and does not infer multipart/shared-context
relationships merely from question-code naming patterns.

### Assessment domain

**IMPLEMENTED / CURRENT**

```text
Subject
  -> Exam
       -> ExamBooklet
            -> SourceQuestion (optional grouping identity)
            -> Question
                 -> ordered QuestionRegion(s)
                 -> optional SharedQuestionContext
                 -> optional Answer
                      -> text and/or ordered AnswerRegion(s)
```

Current invariants include:

- a Question belongs to one ExamBooklet;
- Question natural identity is `(booklet_id, question_code)`;
- question codes are text and support values such as `21a`;
- marks are positive whole numbers;
- Question classification is one original Subtopic or Descriptor;
- legacy-imported Questions may exist with zero ordinary regions while capture
  is incomplete;
- normal manual Question capture requires at least one ordinary region.

### Original classification model

**IMPLEMENTED / CURRENT — one best-fit classification**

A Question stores one original curriculum classification at Subtopic or
Descriptor level.

A historical requirement remains unresolved: some real questions may reasonably
belong to several original descriptors. Whether direct multi-classification is
required remains a design decision in the backlog.

### Versioned curriculum and mapping

**IMPLEMENTED / CURRENT**

- Subject and SyllabusVersion persistence.
- Unit, Topic, Subtopic and Descriptor hierarchy.
- Curriculum Excel import.
- Current/historical syllabus selection.
- Historical-to-current mapping.
- Descriptor -> Descriptor and Subtopic -> Subtopic mapping.
- One-to-many mappings.
- Confirmed/suggested/no-match/unreviewed semantics.
- Only confirmed mappings affect retrieval.
- Original historical classification remains provenance.
- Current applicability is derived rather than overwriting original
  classification.

Chemistry remains the most complete development dataset. Full current-version,
mapping and question-corpus parity across every science subject is not yet
established.

A separate 5 September Chemistry 2019 -> 2025 descriptor-mapping workbook exists
as a reviewed data artefact, but its complete reconciliation with application
SQLite mapping records remains separate work.

### Managed PDF workflows

**IMPLEMENTED / CURRENT**

- arbitrary external PDF viewer mode;
- managed exam-PDF import;
- managed marking-guide/answer-PDF import;
- separate exam and answer PDF sessions;
- page navigation;
- proportional region selection;
- full-width selection mode;
- large same-page anchored selection for shared-preamble capture;
- restoration/switching between exam and answer documents where appropriate.

PDF loading used during post-save Answer transitions now has asynchronous support
so answer persistence does not wait for PDF opening/rendering on the FX thread.

### Question capture

**IMPLEMENTED / CURRENT**

Question capture supports:

- rendered source-PDF page selection;
- multiple ordered ordinary Question regions;
- per-region removal;
- question code and marks;
- syllabus-sensitive classification;
- Descriptor-level controls where present in the selected syllabus branch;
- valid Subtopic stopping points where the hierarchy permits them;
- required Descriptor selection where a Topic has direct Descriptor children;
- imported legacy Questions with incomplete region state;
- editing/correction of an existing persisted Question while retaining its ID;
- preservation of an existing Answer when the Question is corrected;
- source-question assignment;
- shared-context link/capture/reuse;
- explicit unresolved shared-preamble status;
- automatic preamble workflow for the current single-region case;
- safe clearing/ownership of transient selections;
- confirmation/guarding around destructive transitions;
- resizable capture workspace;
- asynchronous Question save/validation/refresh work so repository I/O does not
  block the FX thread.

### SourceQuestion and shared context

**IMPLEMENTED / CURRENT — Sprint 07**

`SourceQuestion` provides explicit persisted multipart identity. It is separate
from `SharedQuestionContext`.

`SharedQuestionContext` represents reusable material such as a preamble, table,
graph or diagram. It contains one or more ordered PDF source regions and may be
linked to multiple Questions in the same booklet.

Important semantics:

- multipart membership is persisted explicitly;
- shared-context reuse is persisted explicitly;
- multipart grouping and shared-context identity are not the same relationship;
- ordinary multi-region Questions remain ordinary Questions;
- recognised multipart question codes may be conservatively used during
  capture/backfill to create persisted `SourceQuestion` identity;
- shared-context relationships are never inferred from question codes alone;
- legacy preamble evidence is preserved rather than rewritten away;
- shared context can be reused by later parts after initial capture.

**CURRENT WORKFLOW LIMIT:** automatic imported-question preamble capture currently
finishes after one accepted shared-context region. The persistence model supports
multiple ordered shared-context regions, but automatic multi-page capture is
deferred to the backlog.

### Answer capture and correction

**IMPLEMENTED / CURRENT**

Answer capture supports:

- an unanswered-question queue for ordinary capture;
- Question marks shown during Answer capture;
- text-only, region-only or combined Answers;
- multiple ordered Answer regions;
- current selection and accepted-region previews;
- per-region removal;
- A/B/C/D multiple-choice capture where currently inferred from booklet naming;
- persisted Answer correction/editing reached through Question Search;
- stable Question/Answer relationships while editing;
- asynchronous Answer persistence;
- automatic loading/reuse of a registered marking-guide PDF;
- asynchronous post-save transition to the next Answer PDF;
- retention of a committed Answer even when loading the next PDF fails;
- hiding the Answer PDF chooser/decorative row when the selected Question's
  answer PDF is already known;
- exposing the PDF chooser when no registered answer PDF is available.

The Answer pane is intentionally an unanswered queue for normal capture.
Already-answered Questions are edited through Question Search rather than by
adding a second selector mode to the Answer pane.

### Legacy metadata import

**IMPLEMENTED / CURRENT**

Legacy import supports:

- Excel question metadata import;
- exam/provider/year/booklet reconstruction;
- text question codes;
- preserved historical classification;
- MCQ answer letters when supplied;
- metadata-only Questions with zero captured regions;
- managed source-document handling;
- idempotent/conflict-aware persistence;
- preamble evidence without guessed grouping;
- missing-booklet discovery and import;
- optional marking-guide registration while importing missing booklets;
- marking-guide registration even when all required question booklets already
  exist.

The importer does not create fake regions, fake answers or guessed
SourceQuestion/shared-context relationships.

### Question retrieval and Search Questions

**IMPLEMENTED / CURRENT — Sprint 03 foundation retained**

Retrieval scopes include:

```text
Subject
Unit
Topic
Subtopic
Descriptor
```

Retrieval includes:

- Questions directly classified to current curriculum;
- historical Questions connected by confirmed mappings;
- hierarchy expansion;
- duplicate prevention;
- original provenance;
- SQLite repository/service retrieval;
- asynchronous JavaFX search;
- stale-result/lifecycle protection;
- Question details and reconstructed Question preview;
- linked `SharedQuestionContext` regions rendered before the selected
  Question's ordinary regions without automatically displaying sibling parts;
- existing Question and Answer correction entry points.



### Revision corpus and HTML output

**IMPLEMENTED / CURRENT — Sprint 05 plus Sprint 07 semantics**

The application exports a deterministic static revision website for a Subject's
current syllabus.

Implemented behaviour includes:

- transient current-curriculum revision-corpus generation;
- deterministic hierarchy/question ordering;
- reusable generated assets;
- ordered Question and Answer region rendering;
- Subject, Unit, Topic and Subtopic pages;
- Descriptor-mode presentation where applicable;
- native answer disclosure;
- source attribution and original-classification provenance;
- omission/reporting of zero-region metadata-only Questions;
- staged generation and validation before publication;
- background generation and progress reporting;
- collision-safe export-directory naming;
- Sprint 07 presentation grouping by persisted SourceQuestion;
- shared context rendered with applicable Question parts;
- multipart marks derived from included parts rather than persisted separately;
- independent Questions sharing context remaining independent Questions.

Real Chemistry browser acceptance was completed successfully during Sprint 05.

### SCORM 1.2 output

**IMPLEMENTED / CURRENT — Sprint 06 plus Sprint 07 presentation changes**

The application generates SCORM 1.2 revision ZIPs from the static revision
content pipeline.

Current package profile includes:

- one organisation, one item and one SCO;
- launch from root `index.html`;
- deterministic manifest identifiers/file ordering;
- complete learning-content file inventory;
- bundled schema support;
- package/reference validation before publication;
- portable relative paths;
- no authoritative source PDFs copied into the package;
- deterministic ZIP structure;
- background JavaFX export workflow.

A real generated Chemistry package was imported into QLearn and launched
successfully during Sprint 06.

Sprint 07 altered presentation content, not the underlying SCORM packaging
architecture.

### Backup, restore and data safety

**IMPLEMENTED / CURRENT — Sprint 04**

- versioned backup archives;
- SQLite-consistent snapshots;
- manual full backup;
- automatic database-only backup on normal close;
- bounded automatic-backup retention;
- validated database-only and full restore;
- pre-restore safety backup;
- rollback after failed destructive restore;
- migration-compatibility validation;
- archive path/content validation;
- filesystem-layout hardening;
- restart boundary after successful or partially destructive restore.

### UI workflow improvements completed in Sprint 07

**IMPLEMENTED / CURRENT**

- resizable PDF/capture workspace;
- explicit selection ownership between Question, shared-context and Answer
  capture;
- large same-page anchored selections;
- visible required-preamble messaging;
- Add Preamble -> Add Region workflow transition;
- shared preamble reuse for later parts;
- Question Search status cleared when the dialog closes;
- Question correction preserving existing Answer;
- Answer correction preserving existing Question;
- multiple-choice button selection;
- ordinary Answer PDF-region capture;
- stable Answer-region preview sizing;
- Question and Answer save work moved off blocking FX-thread paths;
- automatic registered Answer PDF reuse;
- Answer PDF chooser controls suppressed when unnecessary.

## Current branch and Sprint 07 closeout

`feature/preamble-capture` is the active feature branch.

Sprint 07 implementation, documentation consolidation and final
branch-versus-`main` merge-readiness review are complete.

The final review identified one edit-transition defect: changing a multipart
Question to a non-multipart question code could retain its old `SourceQuestion`
and shared-context relationships. The capture service was corrected so those
relationships are cleared, with regression coverage for the transition.

Final verification includes:

- standard Maven test suite green;
- full headless TestFX/UI suite green;
- Javadoc/doclint green;
- manual Sprint 07 acceptance complete.

No known Sprint 07 merge blocker remains.

## Active backlog themes

The authoritative deferred-work list is `docs/design/backlog.md`.

Current major deferred themes include:

- additional asynchronous Question Search regression coverage;
- broad capture/audit work queue;
- reconciliation of the 5 September Chemistry mapping workbook;
- Answer-pane layout annoyances;
- multi-page automatic shared-preamble capture;
- supported editing of persisted exam-specific metadata;
- clearing a pending selection when Full width selection changes;
- Question-level response type rather than booklet-name MCQ inference;
- optional MCQ explanation-region capture;
- explicit decision on multiple original classifications;
- question-level applicability exceptions after curriculum mapping;
- import audit/reconciliation reporting;
- broader real-world legacy workbook validation;
- measurement-driven retrieval/preview performance work;
- later image/clipboard content support;
- later desktop packaging/deployment work.

## Future output/deployment work

**PROPOSED / NOT CURRENT**

- current-generation printable exam/revision PDF assembly;
- vector-preserving clipping of original source PDFs where practical;
- self-contained `jpackage`-style desktop deployment;
- broader multi-user/faculty deployment model;
- clipboard or drag/drop image-question attachments.

A live SQLite database on SharePoint/network sync is not considered a safe
concurrently edited multi-user datastore.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- completed current-generation Exam Builder/assessment assembly workflow;
- complete current-curriculum/mapping/question corpus for every science Subject;
- multiple direct original classifications on one Question;
- automatic multi-page shared-preamble capture;
- general dependency graphs between Questions/shared contexts;
- Question-level response type persisted independently of booklet naming;
- clipboard/image-attachment Question capture;
- current-generation LaTeX/PDF assessment assembly;
- self-contained installer/deployment packaging;
- safe simultaneous multi-user editing of one shared SQLite database.

## Completed sprint position

- Sprint 01: legacy metadata import foundation — complete.
- Sprint 02: curriculum applicability/mapping foundations — complete.
- Sprint 03: current-curriculum Question retrieval/Search Questions — complete.
- Sprint 04: backup/restore/data safety — complete.
- Sprint 05: deterministic revision corpus/static HTML output — complete.
- Sprint 06: SCORM 1.2 generation and QLearn validation — complete.
- Sprint 07: preamble-aware capture, persisted SourceQuestion/shared context,
  correction workflows, multipart revision presentation and capture UI redesign
  — implementation and merge-readiness closeout complete on the feature branch.
