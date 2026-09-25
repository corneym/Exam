# Current Status

> **Authoritative status date:** 25 September 2026.  
> Sprints 01 through 09 are complete and merged.  
> Sprint 10 implementation is complete and verified on `feature/capture-output`.  
> Current `main`: `5a1e5a9`.  
> Sprint 10 merge to `main` is still pending.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by
SQLite. It manages authoritative exam and marking PDFs, versioned curriculum
data, legacy metadata import, Question and Answer capture from PDF regions,
historical-to-current curriculum mapping, current-curriculum retrieval, revision
HTML generation, SCORM 1.2 packaging, backup/restore, curriculum authoring,
corpus audit and correction of persisted assessment data.

Sprint 07 established persisted SourceQuestion identity, reusable
SharedQuestionContext, shared-context-aware capture, multipart presentation
semantics, syllabus-sensitive classification and Question/Answer correction.

Sprint 08 added subject-neutral curriculum authoring, mapping coverage,
legacy-metadata correction, persisted Question response type,
response-type-aware Answer completeness and corpus-audit tooling.

Sprint 09 completed a sustained-use capture/correction pass and was merged to
`main` in commit `2533586`. Protected-main workflow was then exercised through
pull request #1.

Sprint 10 completes the next sustained-use/output pass. It hardens capture and
Search workflows, adds booklet-level response/Answer-file semantics, exposes
independent MCQ shared-context continuation, adds Question-specific revision
output exclusions, aligns live persistence terminology with Shared Context, and
turns the revision HTML/SCORM pipeline into a configurable student-facing export.

The latest supported SQLite schema version is **13**.

## Implemented and current

### Application foundation

- Maven-based Java application using Java 25.
- Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox for PDF loading, rendering and region extraction.
- SQLite persistence with foreign-key enforcement and sequential migrations.
- SQLite connections use a bounded busy timeout so short asynchronous writes do
  not cause concurrent JavaFX reads to fail immediately with `SQLITE_BUSY`.
- JUnit and TestFX regression coverage.
- Repository/service/importer/PDF/output/UI package separation.
- GitHub Actions with separate non-UI, remaining-UI and workflow-UI jobs.

### Configuration and managed data

One configured `data.root` derives managed locations including `pdf/`,
`curriculum/` and `questionbank.db`.

Persisted document paths are portable paths beneath the managed data root rather
than machine-specific absolute paths. Managed source PDFs remain authoritative;
rendered images are derived assets.

Exam/provider/year correction relocates managed exam/answer PDFs when the
managed directory changes and updates persisted source paths with rollback
protection. Assessment-name-only edits do not move files because assessment name
is not part of the managed path.

### Database and assessment model

Schema version 13 supports Subjects, SyllabusVersions, curriculum hierarchy,
providers, Exams, Booklets, managed source documents, booklet-level Question
format, booklet-to-AnswerFile assignment, restart-safe pending independent-MCQ
shared-context continuation, Questions, ordered Question regions, persisted
Question response type, Answers, ordered Answer regions, historical-to-current
mappings, SourceQuestion identity, SharedQuestionContext, ordered shared-context
regions and Question-specific revision-output exclusions.

Schema evolution introduced during Sprint 10:

- **v9** — persisted `ExamBookletQuestionFormat`;
- **v10** — nullable `exam_booklets.answer_file_id`;
- **v11** — nullable `exam_booklets.pending_mcq_shared_context_id`;
- **v12** — `question_output_exclusions(question_id,
  current_curriculum_node_id)`;
- **v13** — live physical columns renamed from legacy `preamble_*` terminology
  to `shared_context_capture_required` and `shared_context_status`.

Historical migrations retain their historical column names so old database
versions remain reproducible and testable.

Conceptually:

```text
Subject
  -> Exam
       -> AnswerFile
       -> ExamBooklet
            -> ExamBookletQuestionFormat
            -> optional assigned AnswerFile
            -> optional pending independent-MCQ SharedQuestionContext
            -> SourceQuestion (optional multipart identity)
            -> Question
                 -> QuestionResponseType
                 -> ordered QuestionRegion(s)
                 -> optional SharedQuestionContext
                 -> zero or more output-applicability exclusions
                 -> optional Answer
                      -> text and/or ordered AnswerRegion(s)
```

`SourceQuestion` and `SharedQuestionContext` are independent. Independent MCQs
may share a SharedQuestionContext without acquiring multipart SourceQuestion
identity.

### Curriculum and mapping

Current behaviour supports Unit, Topic, optional Subtopic and Descriptor
hierarchies; historical/current syllabus versions; confirmed directional
historical-to-current mappings; one-to-many mapping; and provenance-preserving
retrieval.

Application SQLite mapping-review state is authoritative. The standalone
Chemistry mapping workbook remains reference material only.

A Question-specific output exclusion is an exception to derived placement, not a
new mapping. Absence of an exclusion means normal curriculum-derived
applicability applies. Exclusions are valid only for current Subtopic or
Descriptor nodes in the same Subject and do not rewrite historical
classification or curriculum mappings.

### Curriculum authoring

Curriculum authoring can create and edit Subjects/syllabuses, author hierarchy
nodes, preserve managed syllabus-PDF provenance and use `IN_PROGRESS` / `FINAL`
lifecycle state.

Sprint 10 corrected code-driven hierarchy synchronisation so visible
Unit/Topic/Subtopic/Descriptor controls and the `CurriculumSelectionModel` remain
consistent when a complete code is entered or shortened.

### Question capture and correction

Current Question capture supports rendered PDF selection, multiple ordered
ordinary regions, question code/marks, Subtopic/Descriptor classification,
imported metadata-only Questions, editing/correction, persisted SourceQuestion
identity, multipart shared-context capture/reuse, independent-MCQ shared-context
capture/reuse, shared-context recapture and asynchronous save work.

Sprint 10 corrected the pending-selection invariant so a visually cancelled PDF
selection no longer remains logically owned by Question or Answer capture.

Successful booklet activation clears transient Question code/marks, accepted
regions, pending rectangles, transient shared-context state and stale status text
from the previous booklet.

A workspace-level Working Subject filters Question and Answer capture queues
without rewriting persisted Subject, classification or Exam ownership.

Each ExamBooklet records a Question format:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
MIXED
UNSPECIFIED
```

For genuinely new Question capture:

- MCQ-only booklets fix response type to Multiple Choice and marks to 1;
- Written-Response-only booklets fix response type to Written Response while
  marks remain editable;
- Mixed booklets permit manual response-type choice and conservative Written
  Response inference;
- recognised part-letter suffixes and greater-than-one-mark whole Questions may
  support Written Response inference in Mixed booklets;
- one mark alone never implies Multiple Choice;
- imported, edited and legacy-split Questions retain their persisted response
  type.

For genuinely new independent MCQs, `Shared context with next question` records
a booklet-scoped, restart-safe, one-step continuation. Supported numeric
successors include `5 -> 6`, `Q5 -> Q6` and `Q09 -> Q10`.

Continuation is sequence-aware rather than capture-order-aware. An out-of-order
Question does not inherit or consume pending context. An inherited Question may
extend the same context one Question further without recapture.

Question-code duplication is reported immediately and non-modally before the
user repeats classification/capture work. Correcting the code preserves accepted
regions and metadata.

User-facing and live runtime persistence terminology is now **Shared Context**.
Historical migration SQL and legacy workbook vocabulary retain `preamble` only
where required to represent historical/external data accurately.

### Answer capture and correction

Answer capture supports deterministic source-order unanswered queues, Question
marks, multiple ordered written-response regions, MCQ answer letters, persisted
Answer correction and asynchronous persistence.

Answer PDFs are resolved at ExamBooklet level. Each booklet may have zero or one
assigned AnswerFile, while one AnswerFile may serve several booklets.

When Answer capture moves to a booklet whose AnswerFile is known, the correct PDF
is restored automatically. If the booklet has no assignment, the previous
booklet's PDF is cleared and `Choose PDF...` is shown. Selecting a PDF persists
that mapping.

Persistence rejects cross-Exam assignments, regions that conflict with the
booklet assignment and one Answer spanning multiple AnswerFiles.

Completeness remains response-type aware:

```text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> ordinary Answer capture blocked until resolved
```

### Legacy import and correction

Legacy import supports workbook Question metadata, exam/provider/year/booklet
reconstruction, historical classification, MCQ letters, metadata-only
zero-region Questions, managed source documents, idempotent/conflict-aware
persistence, conservative legacy shared-context evidence and marking-guide
registration.

The legacy split workflow can transactionally convert one imported Question into
multipart parts with explicit part metadata, regions, Answer ownership and
shared-context options.

Legacy workbook labels may still use `Preamble`; runtime/domain and live-schema
terminology use Shared Context.

### Corpus audit and Search

Corpus Audit identifies missing Question source, missing Answer, unresolved
shared context and unknown response type.

Search Questions supports `Current syllabus` and `All Questions`, deterministic
source ordering and stored Question preview.

Sprint 10 Search refinements include:

- export-time grouping by Subtopic or Descriptor;
- Topic roll-up for curricula whose Topics contain Descriptors directly;
- Descriptor grouping only when every renderable placement has Descriptor-level
  coverage; a direct Subtopic placement makes Descriptor grouping unavailable
  and automatic planning falls back to Subtopic grouping;
- a functional minimum dialog width and reliable width/height/X/Y restoration
  across edit hide/show cycles, including full-height snapped windows;
- a separate selected-Question classification display rather than conflating
  stored classification with search filters;
- export-time grouping by Subtopic or Descriptor;
- Topic roll-up for curricula whose Topics contain Descriptors directly;
- broader classification changes remaining in `Edit Question`;
- classification removed from `Edit Metadata`;
- Edit Question reopening the stored PDF at the saved region and showing a
  light-grey overlay for the persisted region;
- a Revision Output Applicability panel with immediate Include/Exclude
  persistence.

Revision Output Applicability is deliberately independent from the active Search
filter. Even when Search is narrowed to one Descriptor, the output panel resolves
the selected Question's complete Subject-wide current applicability and shows all
current placements.

### Revision HTML and SCORM

The application builds a deterministic current-curriculum revision corpus and
exports a static hierarchical student website with rendered assets, marks,
provenance, answer disclosure, SourceQuestion grouping and shared-context
rendering.

Sprint 10 adds:


- Descriptor grouping only when every renderable placement has Descriptor-level
  coverage; if any renderable placement exists directly at Subtopic level,
  Descriptor grouping is unavailable and automatic planning falls back to
  Subtopic grouping rather than dropping the broader placement;
- Multiple Choice before Written Response, with `Other questions` for unresolved
  renderable material;
- response-type section headings and navigation where useful;
- a sticky curriculum breadcrumb on Unit, Topic and Subtopic pages so the current
  location remains visible while long Question pages scroll;
- page-local Question numbering beginning at 1 on every generated question page;
- multipart presentations counted and numbered as one student-facing card;
- omission of empty Unit/Topic/Subtopic/Descriptor branches and unnecessary
  files;
- explicit Unit selection, with all non-empty Units selected by default;
- identical grouping and Unit-selection options for Revision HTML and SCORM;
- Question-specific output exclusions applied before placement/statistics/rendering;
- subject-index status based on student-facing presentation/card count rather
  than stored Question rows;
- one export timestamp captured by the export service and rendered consistently,
  with fixed/injected time used in tests.

SCORM 1.2 packages the same configured static revision site as a single-SCO ZIP.
A real Chemistry package has previously been imported into QLearn and launched
successfully.

### Backup and restore

Current data-safety support includes versioned archives, SQLite-consistent
snapshots, manual and automatic backup, bounded retention, validated restore,
pre-restore protection, rollback after failed destructive restore,
migration-compatibility validation and a restart boundary.

Schema v13 snapshot/migration tests verify that current terminology and prior
data survive upgrade.

## Repository governance

Sprint 09 merged to `main` on 20 September 2026 (`2533586`). Protected-main
workflow was then tested using pull request #1, producing current `main`
`5a1e5a9`.

Sprint 10 remains on `feature/capture-output`. Git/GitHub is authoritative for
the current branch head and CI result.

Sprint 10 implementation is complete, but the sprint is not yet recorded as
merged to `main`.

## Sprint 10 implementation status

All planned Sprint 10 slices are implemented and verified:

1. pending PDF-selection state correction and booklet-transition reset;
2. curriculum selector synchronisation;
3. Working Subject filtering;
4. persisted booklet Question format and response-type capture rules;
5. booklet-specific AnswerFile persistence and automatic Answer-PDF switching;
6. independent-MCQ Shared Context capture and sequence-aware continuation;
7. safe Subtopic/Descriptor revision-output grouping with automatic Subtopic
   fallback when direct Subtopic placements prevent complete Descriptor coverage;
8. MCQ-before-written ordering and response-type sections;
9. page-local numbering;
10. empty-branch pruning and selected-Unit HTML/SCORM export;
11. generated-site timestamp and student-facing presentation counts;
12. persistent sticky breadcrumb navigation on generated Unit, Topic and Subtopic
    pages.

Additional Sprint 10 work completed during sustained use includes Search
classification refinement, stored-region edit navigation, Question-specific
revision-output exclusions, Search dialog geometry hardening, native-window
dirty-close protection, live database Shared Context terminology migration and
public API Javadoc completion across the affected production boundaries.

## Verification evidence

Sprint 10 verification includes:

- focused capture, persistence, repository, revision-output, SCORM and Search
  regression sets;
- schema v9-v13 migration and malformed-schema coverage;
- direct populated v12 -> v13 migration verification;
- TestFX/Xvfb hardening for modal-dialog lifecycle and reusable hidden Dialog
  nodes;
- regression coverage proving native Search-window closing cannot silently
  discard an unsaved Descriptor refinement;
- renderer regression coverage for persistent breadcrumb styling, current-page
  breadcrumb marking and sticky-header anchor clearance;- manual real-application verification of shared/separate answer PDFs across
  multiple booklets;
- manual verification of Q5/Q6/Q7 Shared Context continuation, restart
  persistence, different classifications, out-of-sequence protection and
  booklet-switch reset behaviour;
-- student at the latest completed closeout checkpoint;
- strict Javadoc generation completed without warnings;
- `git diff --check` passed at the latest completed closeout checkpoint;
- protected-main merge remains conditional on green GitHub Actions checks for
  the final feature-branch state.

## Active limitations and backlog themes

The authoritative deferred-work inventory is `docs/design/backlog.md`.

Major remaining themes include multi-page automatic shared-context capture,
optional MCQ explanation regions, empty managed-directory cleanup after Exam
relocation, direct multiple-original-classification decisions, explicit
out-of-scope source disposition, import/reconciliation hardening, future
clipboard/image content, broader SQLite-backed export integration, Exam Builder,
printable assessment output and deployment packaging.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- multiple direct original classifications on one Question;
- automatic multi-page shared-context capture;
- explicit reviewed out-of-scope source disposition;
- clipboard/image-attachment Question capture;
- complete current-generation Exam Builder/assessment assembly;
- current-generation printable assessment/solution assembly;
- self-contained installer/deployment packaging;
- safe simultaneous multi-user editing of one shared SQLite database.
