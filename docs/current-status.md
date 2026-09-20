# Current Status

> Authoritative project status at 20 September 2026.
>
> Sprint 08 — Curriculum and Corpus Completion is complete and merged.
>
> Sprint 09 — Capture Workflow and Corpus Correction has completed implementation
> on `feature/capture-workflow`. Commit `11acb24` is the current feature-branch
> head and is ready for final repository closeout and merge.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by
SQLite. It manages authoritative exam and marking PDFs, versioned curriculum
data, legacy metadata import, Question and Answer capture from PDF regions,
historical-to-current curriculum mapping, current-curriculum retrieval,
revision HTML generation, SCORM 1.2 packaging, backup/restore, curriculum
authoring, corpus audit and correction of persisted assessment data.

Sprint 07 established persisted source-question identity, reusable shared
context, preamble-aware capture, multipart presentation semantics,
syllabus-sensitive classification and Question/Answer correction.

Sprint 08 added subject-neutral curriculum authoring, mapping coverage,
legacy-metadata correction, persisted Question response type,
response-type-aware Answer completeness, corpus audit/completeness tooling and
search/capture hardening.

Sprint 09 has now completed the sustained-use capture/correction pass: responsive
capture controls, source ordering, shared-preamble recapture, Exam correction,
legacy Question splitting, Search scope, Corpus Audit ordering and several
workflow corrections discovered during real corpus work.

The latest supported SQLite schema version is **8**.

## Implemented and current

### Application foundation

- Maven-based Java application using Java 25.
- Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox for PDF loading, rendering and region extraction.
- SQLite persistence with foreign-key enforcement and sequential migrations.
- JUnit and TestFX regression coverage.
- Repository/service/importer/PDF/output/UI package separation.
- Repository guidance for Codex in `AGENTS.md`.

Sprint 09 also reorganised the JavaFX UI into focused subpackages for capture,
search, audit, curriculum, exam metadata, PDF workspace, correction and export
workflows without changing persisted domain semantics.

### Configuration and managed data

One configured `data.root` derives managed application locations including:

```text
pdf/
curriculum/
questionbank.db
```

Persisted document paths are portable paths beneath the managed data root rather
than machine-specific absolute paths. Managed source PDFs remain authoritative;
rendered images are derived assets.

Exam PDFs and marking PDFs use managed subject/provider/year storage. Sprint 09
Exam metadata correction now relocates managed PDFs when corrected provider or
year changes the authoritative managed directory and updates persisted
`SourceDocument` paths transactionally. Completed filesystem moves are reversed
if later persistence fails. Changing only an assessment name does not move files
because assessment name is not part of the managed path.

### Database schema and assessment model

Schema version 8 supports:

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

Current behaviour includes:

- Subject and SyllabusVersion persistence;
- Unit, Topic, Subtopic and Descriptor hierarchy;
- both `Unit -> Topic -> Descriptor` and
  `Unit -> Topic -> Subtopic -> Descriptor` structures;
- Excel curriculum import;
- current/historical syllabus selection;
- historical-to-current Descriptor and Subtopic mapping;
- one-to-many mappings;
- confirmed, suggested, explicit no-match and unreviewed semantics;
- only confirmed mappings affecting retrieval;
- original historical classification preserved as provenance;
- application SQLite state authoritative for mapping review.

The separate Chemistry 2019 -> 2025 mapping workbook remains reference material
only. There is no workbook-reconciliation pass that overrides application review
state.

### Curriculum authoring

Curriculum > Author / Edit can create a new Subject/syllabus or open an existing
persisted syllabus, including one originally imported from Excel.

Key rules include explicit hierarchy authoring, stable persistent node identity,
automatic correction-safe numbering, managed syllabus-PDF provenance,
`IN_PROGRESS` / `FINAL` lifecycle, safe finalisation/retry and guarded removal
of referenced nodes.

Curriculum structure remains expert-authored. PDF extraction may assist entry but
must not infer authoritative Unit, Topic, Subtopic or Descriptor relationships.

One UI defect remains open: entering a valid curriculum code can update the
underlying hierarchy correctly while a hierarchy ComboBox displays blank or
out-of-sync. This remains backlog work and requires UI-level displayed-value
regressions.

### Question capture and correction

Question capture supports:

- rendered source-PDF selection;
- multiple ordered ordinary Question regions;
- compact question-code/marks controls;
- explicit radio buttons for Multiple Choice versus Written Response;
- Subtopic or Descriptor classification as the selected syllabus permits;
- imported metadata-only Questions;
- editing/correction while retaining persistent identity;
- preservation of an existing Answer during Question correction;
- explicit SourceQuestion identity;
- shared-context capture, linking and multipart reuse;
- unresolved shared-preamble status;
- same-page automatic preamble workflow;
- guarded Question/Answer selection ownership;
- Add Region/Clear enablement based on compatible pending selections;
- asynchronous save/refresh work.

Important field labels and action controls remain readable at supported narrow
workspace widths. The redundant `Questions -> Capture New Questions` menu action
has been removed; visible capture modes are the authoritative entry point.

Automatic imported-question preamble capture currently completes after one
accepted shared-context region. Persistence supports several ordered
shared-context regions, but the multi-page automatic workflow remains backlog
work.

The underlying domain permits reusable shared context independently of response
type. However the normal capture UI still couples convenient new/reuse context
selection mainly to multipart/preamble workflows. A general capture workflow for
otherwise independent Questions that share one stimulus — including successive
MCQs — remains backlog work.

### Shared-context correction

An existing persisted `SharedQuestionContext` can be deliberately recaptured.

The correction workflow:

- opens the relevant source PDF/page;
- stages replacement region(s) before persistence;
- retains one shared context identity for linked Questions;
- preserves old persisted regions until replacement succeeds;
- leaves the previous context intact on cancellation/failure;
- survives reload;
- is used by Search/revision presentation after replacement.

### Answer capture and correction

Answer capture supports:

- an unanswered-question queue in deterministic source/natural Question order;
- Question marks during capture;
- multiple ordered Answer regions for written response;
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

MCQ Answer capture remains text/letter based, but selecting an MCQ can now
open/reuse the registered answer PDF so the teacher can read the answer key
without opening the marking booklet externally. If no answer PDF is registered,
the user may choose one for MCQ work; region controls remain unavailable where
the response type does not require answer regions.

### Legacy metadata import and correction

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

Question metadata correction preserves safe persisted relationships.

Exam-level correction now changes provider/year/name at the owning Exam entity
rather than rewriting linked Questions. When provider/year changes, associated
managed booklet/answer PDFs are relocated and stored relative paths are updated.
Natural-identity conflicts, shared-source conflicts and destination file
collisions are rejected instead of silently merging or overwriting data.

Selecting a PDF already known to persistence reuses its stored metadata rather
than requiring duplicate Exam/Booklet creation.

### Legacy Question split workflow

A legacy Question stored as one row can be deliberately converted into multipart
parts.

The workflow supports:

- explicit destination part codes;
- marks, classification and response type per part;
- staged Question-region capture;
- no shared preamble, a newly captured shared preamble, or reuse of a compatible
  existing shared context;
- reuse of the original Question row as one resulting part where safe;
- explicit ownership of an existing Answer;
- compatible existing SourceQuestion reuse;
- rejection of duplicate/incompatible identities;
- atomic persistence and cancellation/rollback protection;
- reload reconstruction;
- Corpus Audit and RevisionPresentationPlanner regressions after reload.

### Corpus audit and completeness

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

Questions > Corpus Audit provides Subject, provider, year, booklet,
completion-state and problem filters plus scope-wide summary totals.

Sprint 09 now presents audit work in deterministic source order by provider,
year, booklet and natural Question code. Year filter choices are numerically
sorted rather than insertion ordered.

### Question retrieval and Search Questions

Current-syllabus retrieval scopes remain Subject, Unit, Topic, Subtopic and
Descriptor. Results may come from direct current classification or confirmed
historical mapping while preserving original provenance.

Search Questions now has an explicit scope:

```text
Current syllabus
All Questions
```

Current-syllabus scope retains established applicability semantics. All Questions
deliberately shows the stored bank without fabricating current-curriculum
applicability. Both scopes use deterministic source ordering.

Search remains asynchronous with stale-result/lifecycle protection. Stored
Question preview reconstructs linked shared context before the selected
Question's own ordinary regions without automatically displaying sibling parts.

The Search dialog now remembers the user's resized dimensions when an action
temporarily hides and redisplays the same dialog.

### Revision HTML and SCORM

The application builds a deterministic current-curriculum revision corpus and
exports a static hierarchical website. It supports ordered Question/Answer
assets, generated numbering, marks, source attribution, original-classification
provenance, omission/reporting of zero-region Questions, persisted
SourceQuestion grouping and shared-context rendering.

SCORM 1.2 packages the static revision site as a deterministic single-SCO ZIP.
A real Chemistry package has been imported into QLearn and launched
successfully.

Revision presentation refinements discovered during real use remain a separate
future design pass rather than Sprint 09 capture work.

### Backup and restore

Current data-safety support includes:

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

## Sprint 09 verification state

The current feature-branch head is:

```text
11acb24 complete sprint 09 capture workflow
```

At that exact commit, GitHub Actions run `35491556692` completed successfully
with all configured jobs green:

- Non-UI tests;
- Workflow UI — capture;
- Workflow UI — state-editing;
- Workflow UI — application;
- remaining UI tests.

The headless UI tests have also been reported green under JUnit in Eclipse.

The sprint design retains the formal local closeout commands:

```bash
./mvnw test
./mvnw -Pheadless-ui-tests test
./mvnw javadoc:javadoc
git diff --check
```

The first two behaviours now have both local/CI evidence. Javadoc and whitespace
validation remain part of the final merge checklist unless rerun after this
documentation update.

## Current development position

Sprints 01 through 08 are complete and merged.

Sprint 09 implementation is complete on `feature/capture-workflow` and is ready
for final documentation/verification closeout and merge to `main`.

After Sprint 09 is merged, the next repository task is to finish main-branch
protection/CI governance so `main` requires the intended checks rather than
relying only on convention.

Real corpus completion and application-authoritative curriculum mapping review
remain ongoing data work.

## Active limitations and backlog themes

The authoritative inventory is `docs/design/backlog.md`.

Major current themes include:

- general shared-context capture/reuse for independent Questions, including
  successive MCQs sharing one stimulus;
- curriculum-code entry leaving hierarchy ComboBox display out of sync;
- revision HTML grouping/provenance/response-type presentation refinements;
- multi-page automatic shared-preamble capture;
- optional MCQ explanation regions;
- multiple original-classification decision;
- Question-level applicability exceptions;
- explicit out-of-scope source-Question disposition;
- import audit/reconciliation and broader workbook validation;
- retrieval/performance hardening;
- future clipboard/image content support;
- later Exam Builder, printable assessment output and deployment work.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- complete current-generation Exam Builder/assessment assembly;
- a complete current-curriculum/mapping/question corpus for every science Subject;
- general independent-Question shared-context capture through the normal capture
  UI;
- multiple direct original classifications on one Question;
- automatic multi-page shared-preamble capture;
- Question-level mapped-applicability exclusions;
- clipboard/image-attachment Question capture;
- current-generation LaTeX/PDF assessment assembly;
- self-contained installer/deployment packaging;
- safe simultaneous multi-user editing of one shared SQLite database.
