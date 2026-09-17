> Authoritative project status at 17 September 2026.
>
> Current repository position: `main` after Sprint 08 merge.
>
> Sprint 08 — Curriculum and Corpus Completion is complete, independently
> reviewed, revalidated and merged. Sprint 09 — Capture Workflow and Corpus
> Correction is planned but implementation has not started.

# Current Status

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by
SQLite. It manages original exam and marking PDFs, versioned curriculum data,
legacy metadata import, Question and Answer capture from PDF regions,
historical-to-current curriculum mapping, current-curriculum retrieval,
revision HTML generation, SCORM 1.2 packaging, backup/restore, curriculum
authoring, corpus audit and correction of persisted Questions and Answers.

Sprint 07 established persisted source-question identity, reusable shared
context, preamble-aware capture, multipart presentation semantics,
syllabus-sensitive classification and Question/Answer correction.

Sprint 08 added subject-neutral curriculum authoring, mapping coverage,
legacy-metadata correction, persisted Question response type, response-type-aware
Answer completeness, corpus audit/completeness tooling, asynchronous Question
Search hardening and capture-state regressions. Final review findings were
addressed before merge.

The latest supported SQLite schema version is **8**.

## Implemented and current

### Application foundation

**IMPLEMENTED / CURRENT**

- Maven-based Java application using Java 25.
- Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox for PDF loading, rendering and region extraction.
- SQLite persistence with foreign-key enforcement and sequential migrations.
- JUnit and TestFX regression coverage.
- Repository/service/importer/PDF/output/UI package separation.
- Repository guidance for Codex in `AGENTS.md`.

### Configuration and managed data

**IMPLEMENTED / CURRENT**

One configured `data.root` derives managed application locations including:

```text
pdf/
curriculum/
questionbank.db
```

Persisted document paths are portable paths beneath the managed data root rather
than machine-specific absolute paths. Managed source PDFs remain authoritative;
rendered images are derived assets.

### Database schema and assessment model

**IMPLEMENTED / CURRENT — schema version 8**

The schema supports:

- subjects and syllabus versions;
- curriculum authoring status, finalisation timestamp and managed source-PDF
  path;
- generic curriculum hierarchy nodes and source-page provenance;
- examination providers, exams and booklets;
- managed source documents;
- Questions and ordered Question regions;
- Question response type: `MULTIPLE_CHOICE`, `WRITTEN_RESPONSE`, `UNKNOWN`;
- answer files, Answers and ordered Answer regions;
- historical-to-current curriculum mappings and review state;
- persisted `SourceQuestion` identity;
- persisted `SharedQuestionContext` with ordered regions;
- nullable Question links to SourceQuestion/shared context;
- source-question `preamble_status`: `UNKNOWN`, `NONE`, `PRESENT`.

The current assessment relationship is conceptually:

```text
Subject
  -> Exam
       -> ExamBooklet
            -> SourceQuestion (optional multipart identity)
            -> Question
                 -> persisted QuestionResponseType
                 -> ordered QuestionRegion(s)
                 -> optional SharedQuestionContext
                 -> optional Answer
                      -> text and/or ordered AnswerRegion(s)
```

A Question belongs to one booklet, uses a text code such as `21a`, has positive
marks, and stores one original Subtopic or Descriptor classification. Imported
legacy Questions may legitimately exist with zero ordinary Question regions
until capture is completed.

### Curriculum and mapping

**IMPLEMENTED / CURRENT**

- Subject and SyllabusVersion persistence.
- Unit, Topic, Subtopic and Descriptor hierarchy.
- Both `Unit -> Topic -> Descriptor` and
  `Unit -> Topic -> Subtopic -> Descriptor` structures.
- Excel curriculum import.
- Current/historical syllabus selection.
- Historical-to-current Descriptor and Subtopic mapping.
- One-to-many mappings.
- Confirmed, suggested, explicit no-match and unreviewed semantics.
- Only confirmed mappings affect retrieval.
- Original historical classification remains provenance.
- Application SQLite state is authoritative for mapping review.

The separate Chemistry 2019 -> 2025 mapping workbook is reference material only;
there is no workbook-reconciliation step that overrides application review
state.

### Curriculum authoring — Sprint 08

**IMPLEMENTED / CURRENT**

Curriculum > Author / Edit can create a new Subject/syllabus or open an existing
persisted syllabus, including one originally imported from Excel.

Key rules:

- hierarchy is explicitly authored by node type and parent;
- hierarchy is never inferred from dotted code depth;
- codes are generated automatically using hierarchical numeric notation;
- existing surviving codes remain stable during ordinary move/delete editing;
- a newly created sibling uses the lowest unused positive child number;
- persistent curriculum-node IDs are preserved for edited existing nodes;
- draft IDs remain separate from persistent IDs until save;
- removal of referenced nodes is guarded;
- source-page provenance can be persisted;
- syllabus PDFs are copied into managed curriculum storage;
- `IN_PROGRESS` and `FINAL` lifecycle states are explicit;
- a FINAL syllabus must be reopened before editing;
- finalisation saves the draft first, then records lifecycle state separately;
- a failure after draft save leaves a retryable IN_PROGRESS curriculum;
- attached syllabus-PDF replacement is failure-safe;
- closing the authoring window refreshes application curriculum selectors.

Curriculum structure remains expert-authored. PDF extraction may assist entry but
must not infer authoritative Unit, Topic, Subtopic or Descriptor relationships.

### Question capture and correction

**IMPLEMENTED / CURRENT**

Question capture supports:

- rendered source-PDF selection;
- multiple ordered ordinary Question regions;
- question code and marks;
- explicit per-Question response type for normal capture;
- Subtopic or Descriptor classification as the selected syllabus permits;
- imported metadata-only Questions;
- editing/correction of persisted Questions while retaining persistent identity;
- preservation of an existing Answer during Question correction;
- explicit SourceQuestion identity;
- shared-context capture, linking and reuse;
- unresolved shared-preamble status;
- same-page automatic preamble workflow;
- guarded selection ownership and destructive transitions;
- asynchronous save/refresh work.

Automatic imported-question preamble capture currently completes after one
accepted shared-context region. Persistence supports several ordered
shared-context regions, but the multi-page automatic workflow remains backlog
work.

### SourceQuestion and SharedQuestionContext

**IMPLEMENTED / CURRENT**

`SourceQuestion` represents original multipart identity.
`SharedQuestionContext` represents reusable source material such as a preamble,
graph, table or diagram. They are separate relationships.

Recognised multipart question codes may conservatively derive/create
SourceQuestion identity during capture/backfill. Shared context is never inferred
from code pattern alone.

Sprint 08 metadata correction protects multipart consistency. A Question cannot
be moved into an existing source group if its shared-context relationship would
conflict with the destination group, including present-versus-missing context.
Compatible moves survive reload and remain valid for revision presentation.

### Answer capture and correction

**IMPLEMENTED / CURRENT**

Answer capture supports:

- an unanswered-question queue;
- Question marks during capture;
- multiple ordered Answer regions;
- persisted Answer correction through Question Search;
- asynchronous persistence;
- automatic reuse/loading of registered marking-guide PDFs;
- response-type-aware controls and validation.

Answer completeness is authoritative at Question level:

```text
MULTIPLE_CHOICE
    valid A/B/C/D answer required
    Answer regions optional

WRITTEN_RESPONSE
    one or more Answer regions required
    text alone is insufficient for corpus completeness

UNKNOWN
    response type unresolved
    ordinary Answer capture blocked until corrected
```

Booklet naming is not the runtime authority for Answer behaviour. Mixed-response
booklets are supported. Changing response type does not silently delete existing
Answer text, Answer regions, Question regions, classification, SourceQuestion or
SharedQuestionContext relationships.

### Corpus audit and completeness — Sprint 08

**IMPLEMENTED / CURRENT**

Question-bank completeness is assessed independently for Question source
capture, response-type resolution, Answer completeness and unresolved shared
context.

Actionable problems are:

```text
MISSING_QUESTION_SOURCE
MISSING_ANSWER
UNRESOLVED_SHARED_CONTEXT
UNKNOWN_RESPONSE_TYPE
```

An UNKNOWN response type is reported as its own problem rather than also being
reported as `MISSING_ANSWER`.

Questions > Corpus Audit provides Subject, provider, year, booklet,
completion-state and problem filters plus scope-wide summary totals. Selected
work is routed into the existing metadata, Question/imported-capture or Answer
workflow rather than a parallel persistence system.

### Legacy metadata import and correction

**IMPLEMENTED / CURRENT**

Legacy import supports:

- Excel Question metadata import;
- exam/provider/year/booklet reconstruction;
- text Question codes and marks;
- historical classification preservation;
- MCQ answer letters when supplied;
- persisted response type where reliable legacy evidence exists;
- metadata-only zero-region Questions;
- managed source documents;
- idempotent/conflict-aware persistence;
- preamble evidence without guessed grouping;
- missing-booklet discovery/import;
- optional marking-guide registration.

Edit Metadata can correct question code, marks, classification, response type and
legacy preamble-required evidence while preserving safe persisted relationships.
Single-part captured preamble material can be converted to ordinary Question
regions when the legacy hint is removed. Multipart shared context is protected
from inconsistent per-part correction.

### Question retrieval and Search Questions

**IMPLEMENTED / CURRENT**

Retrieval scopes are Subject, Unit, Topic, Subtopic and Descriptor. Results may
come from direct current classification or confirmed historical mapping while
preserving original provenance.

Question Search is asynchronous and has stale-result/lifecycle protection,
including regressions for search, preview, hierarchy failure and disposal paths.
Stored Question preview reconstructs linked shared context before the selected
Question's own ordinary regions without automatically displaying sibling parts.

### Revision HTML and SCORM

**IMPLEMENTED / CURRENT**

The application builds a deterministic current-curriculum revision corpus and
exports a static hierarchical website. It supports ordered Question/Answer
assets, generated numbering, marks, source attribution, original-classification
provenance, omission/reporting of zero-region Questions, persisted
SourceQuestion grouping and shared-context rendering.

SCORM 1.2 packages the static revision site as a deterministic single-SCO ZIP.
A real Chemistry package has been imported into QLearn and launched
successfully.

### Backup and restore

**IMPLEMENTED / CURRENT**

- versioned backup archives;
- SQLite-consistent snapshots;
- manual full backup;
- automatic database-only backup on normal close;
- bounded automatic-backup retention;
- validated database-only and full restore;
- pre-restore safety backup;
- rollback after failed destructive restore;
- migration-compatibility validation;
- restart boundary after successful/destructive restore.

### Sprint 08 closeout hardening

**IMPLEMENTED / CURRENT**

Final Sprint 08 review and closeout addressed the remaining identified gaps:

- multipart metadata correction rejects incompatible destination shared context;
- regressions cover different-context and missing-versus-present destination
  conflicts and confirm persisted relationships remain unchanged after rejection;
- a positive compatible multipart move survives reload and remains valid for
  revision presentation;
- bulk response-type resolution rolls back if a later loaded Question has become
  known/stale before update;
- curriculum finalisation failure after a successful draft save leaves a valid
  IN_PROGRESS draft and supports retry;
- changing Full width selection clears pending Question,
  SharedQuestionContext or Answer selection state;
- the shared-context Full width regression uses the real user-facing preamble
  capture path;
- the final standard Maven suite and full headless UI suite were green;
- Javadoc completed without warnings;
- `git diff --check` was clean.

Sprint 08 was merged to `main` on 17 September 2026.

## Current development position

Sprint 01 through Sprint 08 are complete.

Sprint 09 — Capture Workflow and Corpus Correction is the next planned sprint.
Its design is recorded in:

`docs/design/sprint-09-capture-workflow-and-corpus-correction.md`

Sprint 09 is based on sustained use of the real application and focuses on
capture-pane usability, ordering, metadata correction, shared-preamble
replacement, legacy Question splitting and bank-management workflow friction.

The revision HTML presentation changes identified during the same usage review
remain in the backlog rather than being silently folded into Sprint 09.

## Active limitations and backlog themes

The authoritative inventory is `docs/design/backlog.md`.

Major current themes include:

- Answer and Question pane responsive-layout/control-state issues;
- natural source ordering for Answer capture and Corpus Audit;
- shared-preamble replacement/recapture;
- performance when entering imported-question capture;
- supported exam-level metadata correction and known-PDF metadata reuse;
- dedicated conversion of a legacy single Question into multipart parts;
- Search Questions all-bank versus syllabus filtering;
- revision HTML grouping/provenance/response-type presentation refinements;
- multi-page automatic shared-preamble capture;
- optional MCQ explanation regions;
- multiple original-classification decision;
- question-level applicability exceptions;
- import audit/reconciliation and broader workbook validation;
- retrieval and performance hardening;
- future image/clipboard content support;
- later Exam Builder, print output and deployment work.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- a completed Sprint 09 workflow;
- dedicated one-step split of legacy `3` into `3a`, `3b`, etc.;
- supported recapture/replacement of an already persisted shared preamble as a
  first-class workflow;
- complete current-generation Exam Builder/assessment assembly;
- complete current-curriculum/mapping/question corpus for every science Subject;
- multiple direct original classifications on one Question;
- automatic multi-page shared-preamble capture;
- question-level mapped-applicability exclusions;
- clipboard/image-attachment Question capture;
- current-generation LaTeX/PDF assessment assembly;
- self-contained installer/deployment packaging;
- safe simultaneous multi-user editing of one shared SQLite database.
