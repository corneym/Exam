> Authoritative project status at 17 September 2026.
>
> Current development branch: `feature/data-completion`.
>
> Sprint 07 is complete and merged. Sprint 08 — Curriculum and Corpus Completion
> has completed implementation and branch-level validation. Final independent
> review and merge remain.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by
SQLite. It manages original exam and marking PDFs, versioned curriculum data,
legacy metadata import, question and answer capture from PDF regions,
historical-to-current curriculum mapping, current-curriculum retrieval,
revision HTML generation, SCORM 1.2 packaging, backup/restore, and correction of
persisted Questions and Answers.

Sprint 07 implementation and closeout are complete and merged. The sprint
introduced persisted source-question identity and reusable shared question
context, preamble-aware capture, multipart presentation semantics,
syllabus-sensitive classification, Question/Answer correction,
capture-workflow protection, and a substantial set of UI and responsiveness
improvements.

Sprint 08 implementation is complete on `feature/data-completion`. It delivered
subject-neutral curriculum authoring, measurable curriculum-mapping coverage,
legacy metadata correction, persisted Question response type, corpus
audit/completeness tooling, and the agreed capture/search hardening. The branch
has completed non-UI, headless UI, Javadoc and whitespace validation and is
under final independent review before merge.

The latest supported SQLite schema version is **8**.

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

**IMPLEMENTED / CURRENT — schema version 8**

The schema supports:

- subjects and syllabus versions;
- curriculum authoring status, finalisation timestamp and managed source-PDF path;
- generic curriculum hierarchy nodes;
- optional one-based syllabus source-page provenance on curriculum nodes;
- examination providers, exams and booklets;
- managed source documents;
- questions and ordered question regions;
- persisted Question response type with `MULTIPLE_CHOICE`, `WRITTEN_RESPONSE`
  and `UNKNOWN`;
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
                 -> persisted QuestionResponseType
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
- every Question has persisted response type `MULTIPLE_CHOICE`,
  `WRITTEN_RESPONSE` or `UNKNOWN`;
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

Application SQLite state is authoritative for curriculum mapping. Mapping
decisions are established through the application's human-reviewed mapping
workflow rather than by reconciling against an external workbook.

The separate Chemistry 2019 -> 2025 mapping workbook remains a useful reference
artefact only. Sprint 08 will add coverage reporting so application state can
distinguish matched, explicit no-match and unreviewed historical nodes, together
with current nodes having no confirmed predecessor from the selected historical
version.

### Resumable curriculum authoring — Sprint 08

**IMPLEMENTED / CURRENT**

The Curriculum > Author / Edit action creates a new syllabus for a new or
existing Subject, or opens an existing persisted syllabus, including an
Excel-imported curriculum. Creation immediately stores an empty `IN_PROGRESS`
syllabus. Choosing it as current immediately makes the previous current version
historical; authoring completion is a separate decision.

The author explicitly selects node levels and parents. Both
`Unit -> Topic -> Descriptor` and `Unit -> Topic -> Subtopic -> Descriptor` are supported.
Authoring validation rejects mixing these shapes anywhere within one syllabus.
An empty syllabus can be created and reopened, but saving/finalising a draft
requires at least one structurally valid node.

Curriculum codes are generated automatically using hierarchical numeric numbering such as `1`, `1.1`, `1.1.1` and `1.1.1.1`. Node type and parent relationships remain explicit rather than being inferred from code depth. Existing codes remain stable when nodes are moved or deleted. A deletion may leave a numbering gap; the next node created beneath that parent uses the lowest available child number, allowing an accidentally deleted branch to be recreated with its original numbering.

Save persists the complete draft transactionally. Existing curriculum-node IDs
are updated in place; new nodes acquire database IDs after successful commit.
Draft IDs are session-local and are bound separately to persistent IDs. Removal
of nodes referenced by Questions, mappings or mapping-review records is rejected.
The persisted hierarchy and optional source-page numbers can be loaded into a
fresh authoring session. JDBC storage preserves supplied text without interpreting
Markdown or LaTeX.

Curriculum lifecycle is independent of the current/historical syllabus flag:

- `IN_PROGRESS` permits authoring; existing imported curricula migrate to this state.
- Mark Final validates and saves the draft, then records `FINAL` and a timestamp.
- A `FINAL` curriculum must be explicitly reopened before editing or changing its
  source PDF; reopening clears the timestamp and returns it to `IN_PROGRESS`.
- Saving the draft and marking it final are separate transactions. If the latter
  fails, the saved draft can remain `IN_PROGRESS` for retry.

Attaching a syllabus PDF copies it into managed curriculum storage and persists
the attachment immediately, independently of saving draft nodes. The stored path
is relative to `curriculumDataRoot`, uses portable separators, and is resolved
within that root. The original external file is no longer needed after a
successful attachment. Node source-page references are one-based.

Pending node-editor text counts as unsaved work. Save and Mark Final apply it to
the draft before persistence, and tree selection changes apply valid wording in
memory. Invalid blank wording remains in the editor and blocks the transition.
Edited text retains indentation, trailing line breaks and Markdown/LaTeX
characters. Closing an authoring window or exiting the application offers
Save/Discard/Cancel for unsaved authoring work; cancelling retains the session.

Only one authoring window per syllabus version can be open in the application,
including FINAL views that can later reopen for editing. A duplicate open explains
that the existing window must be used or closed. Closing releases access;
cancelling a close retains it. Different syllabus versions can be open together.
At the persistence boundary, Save compares all stored node fields against the
session's loaded or last successfully saved snapshot in the same transaction as
the write. A changed snapshot rejects the save before mutation and requires the
session to be closed and reopened. Finalisation uses this same save safeguard.
No schema migration or persistent edit-lock record is required.

Replacing an attached syllabus PDF is failure-safe. A replacement is copied to a new managed file before its database path is published. If the metadata update fails, the new unpublished copy is removed and the existing managed PDF, persisted path and authoring-session state remain unchanged.

Closing a Curriculum Authoring window refreshes the application's curriculum selectors. Newly authored Subjects and saved hierarchy changes therefore become available to the main classification workflow without restarting the application.

Curriculum numbering is automatic and correction-safe: existing codes are preserved during move/delete operations, while newly created nodes use the lowest available hierarchical child number beneath their explicit parent.

The separate Exam Import dialog refreshes its subject choices when opened.

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
- explicit per-Question multiple-choice/written-response selection for normal
  capture;
- persisted response-type correction while editing existing Questions;
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
- broad persisted Answer storage capable of retaining text and/or ordered
  Answer regions;
- response-type-aware Answer capture and validation;
- `MULTIPLE_CHOICE` Questions use persisted A/B/C/D Answer choice and do not
  require an Answer region;
- `WRITTEN_RESPONSE` Questions require at least one Answer region; text alone
  does not make the Answer corpus-complete;
- `UNKNOWN` Questions cannot enter Answer capture until response type is
  resolved;
- Answer UI behaviour is driven by persisted Question response type rather than
  booklet naming;
- multiple ordered Answer regions;
- current selection and accepted-region previews;
- per-region removal;
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

### Corpus audit and completeness — Sprint 08

**IMPLEMENTED / CURRENT**

Question-bank completeness is now assessed independently for Question source
capture, response-type resolution, Answer completeness and unresolved shared
context.

The actionable problem states are:

- `MISSING_QUESTION_SOURCE`;
- `MISSING_ANSWER`;
- `UNRESOLVED_SHARED_CONTEXT`;
- `UNKNOWN_RESPONSE_TYPE`.

Answer completeness is response-type aware:

- MCQ requires a valid A/B/C/D answer and does not require an Answer region;
- written response requires at least one Answer region;
- an `UNKNOWN` response type is reported as `UNKNOWN_RESPONSE_TYPE` without also
  reporting `MISSING_ANSWER`.

The Questions > Corpus Audit workflow provides Subject, provider, year, booklet,
completion-state and problem filters together with scope-wide summary totals.

Selected incomplete work is routed through the existing workflows:

- unknown response type -> Edit Metadata;
- missing Question source or unresolved shared context -> Question/imported
  capture;
- missing Answer -> Answer capture or Answer edit.

The corpus audit does not create a parallel persistence/capture system.

### Legacy metadata import

**IMPLEMENTED / CURRENT**

Legacy import supports:

- Excel question metadata import;
- exam/provider/year/booklet reconstruction;
- text question codes;
- preserved historical classification;
- MCQ answer letters when supplied;
- persisted response type where reliable legacy evidence exists: legacy `MCQ`
  imports become `MULTIPLE_CHOICE`, while Paper 1/Paper 2 remain `UNKNOWN`
  unless explicitly corrected;
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
- regression coverage for stale search and preview completion, hierarchy
  failures, disposal while work is in flight, repeated disposal, no-current and
  multiple-current syllabus states, zero-region Questions and unavailable source
  PDFs;
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

## Current branch and historical Sprint 07 closeout

`feature/data-completion` is the active feature branch for Sprint 08.
Sprint 07 on `feature/preamble-capture` is complete and merged.

Sprint 07 implementation, documentation consolidation and final
branch-versus-`main` merge-readiness review are complete.

The final review identified one edit-transition defect: changing a multipart
Question to a non-multipart question code could retain its old `SourceQuestion`
and shared-context relationships. The capture service was corrected so those
relationships are cleared, with regression coverage for the transition.

Historical Sprint 07 final verification included:

- standard Maven test suite green;
- full headless TestFX/UI suite green;
- Javadoc/doclint green;
- manual Sprint 07 acceptance complete.

No known Sprint 07 merge blocker remains.

### Sprint 08 regression and capture hardening

**IMPLEMENTED / CURRENT**

- changing Full width selection clears any pending Question,
  SharedQuestionContext or Answer selection so visible PDF state cannot disagree
  with logical capture state;
- asynchronous Question Search has explicit stale-result and lifecycle regression
  coverage across search, preview, hierarchy failure and disposal paths;
- TestFX dialog interactions used by closeout regressions are scoped to visible
  dialogs rather than stale hidden dialog nodes;
- the normal Curriculum Authoring UI no longer exposes the diagnostic Export
  Draft action; programmatic draft export remains available for diagnostics and
  regression tests.

## Active backlog themes

The authoritative deferred-work list is `docs/design/backlog.md`.

Current major deferred themes include:

- Answer-pane layout annoyances;
- multi-page automatic shared-preamble capture;
- supported editing of persisted exam-specific metadata;
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
- Sprint 08: implementation and branch-level validation complete on
  `feature/data-completion`; curriculum authoring, mapping coverage, legacy
  metadata correction, persisted Question response type, corpus
  audit/completeness, regression/capture hardening and documentation closeout are
  complete. Final independent review and merge remain.

  Final Sprint 08 branch validation on 17 September 2026:

- non-UI suite: 814 tests passed, 0 failures/errors, 3 existing disabled tests;
- headless UI suite: 144 tests passed, 0 failures/errors/skips;
- Javadoc: build successful with zero warnings;
- `git diff --check`: clean.
