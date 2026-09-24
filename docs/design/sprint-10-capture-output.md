# Sprint 10 — Capture Hardening and Revision Output Refinement

> **Status:** IMPLEMENTATION COMPLETE / VERIFIED ON FEATURE BRANCH  
> **Start date:** 20 September 2026  
> **Implementation closeout:** 24 September 2026  
> **Branch:** `feature/capture-output`  
> **Starting `main`:** `5a1e5a9`  
> **Verified branch head:** `07b7b337` (`question applicability updated`)  
> **CI:** GitHub Actions run 59 successful  
> **Merge state:** pending protected-main merge

## 1. Purpose

Sprint 10 combined two kinds of work exposed by sustained real use of the bank:

1. harden capture, correction and Search state so ordinary teacher workflows are
   reliable; and
2. refine revision HTML/SCORM presentation so generated resources match the
   intended student experience.

The sprint extended architecture established in Sprints 05–09 rather than
rebuilding retrieval, persistence or corpus generation.

## 2. Starting point and schema evolution

At Sprint 10 start:

- Sprint 09 was merged to `main` (`2533586`);
- protected-main workflow had been exercised through PR #1;
- current `main` was `5a1e5a9`;
- SQLite schema version was 8;
- SourceQuestion and SharedQuestionContext were separate persisted concepts;
- revision corpus, presentation planning, HTML export and SCORM packaging existed;
- QuestionResponseType was persisted on each Question;
- Question and Answer capture used explicit PDF-selection ownership.

Sprint 10 introduced five schema versions:

### Version 9 — booklet Question format

Persisted `ExamBookletQuestionFormat`:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
MIXED
UNSPECIFIED
```

Existing rows migrate to `UNSPECIFIED` rather than inventing format.

### Version 10 — booklet-specific AnswerFile

Added nullable `exam_booklets.answer_file_id`.

```text
one ExamBooklet -> zero or one AnswerFile
one AnswerFile  -> zero or many ExamBooklets
```

Legacy relationships are backfilled only when existing region evidence is
unambiguous.

### Version 11 — restart-safe independent-MCQ continuation

Added nullable `exam_booklets.pending_mcq_shared_context_id`.

It stores explicit one-step continuation intent only. Existing databases migrate
with no pending continuation.

### Version 12 — Question-specific output exclusions

Added:

```text
question_output_exclusions (
    question_id,
    current_curriculum_node_id
)
```

No exclusion row means normal curriculum-derived applicability. A row suppresses
one Question/current-node placement without modifying classification or mapping.

### Version 13 — Shared Context physical terminology

Renamed live columns:

```text
questions.preamble_capture_required
    -> questions.shared_context_capture_required

source_questions.preamble_status
    -> source_questions.shared_context_status
```

Historical migrations and historical-schema tests retain old names because they
must still represent genuine earlier versions.

The latest supported SQLite schema version is **13**.

## 3. Sprint goals and final result

Sprint 10 goals were to:

- make pending PDF-selection state internally consistent;
- synchronise curriculum code entry with visible hierarchy controls;
- allow capture work to focus on one Working Subject;
- reduce repetitive response-type selection through explicit booklet semantics;
- support correct Answer PDF selection across multi-booklet exams;
- expose shared-context semantics for independent Questions/MCQs;
- make revision grouping, ordering, numbering and navigation student-facing;
- replace internal corpus/capture counts with useful generated-resource metadata.

All of those goals are implemented and verified.

Sustained use also promoted additional work into Sprint 10:

- Search classification display and Descriptor refinement;
- Search dialog geometry persistence/minimum width;
- stored-region visual navigation when editing a Question;
- Question-specific revision-output Include/Exclude controls;
- live persistence terminology alignment to Shared Context.

## 4. Cross-cutting design rules

### Persisted identity is not presentation state

Question IDs, source Question codes, curriculum classification and mappings are
not rewritten to obtain nicer HTML grouping or numbering.

### Shared context is not multipart identity

Questions may share one SharedQuestionContext without sharing a SourceQuestion.
The UI does not manufacture multipart identity merely to support a shared MCQ
stimulus.

### Independent MCQ continuation is explicit and one-step

`Shared context with next question` records a one-Question continuation. It is
booklet-scoped, restart-safe and sequence-aware.

### A shared context may cross output buckets

Independent Questions using one context may belong to different
Subtopics/Descriptors. Each retains independent classification. Output repeats
context where needed for the Question to remain understandable.

### Visual selection and logical selection are one interaction

If the visible pending PDF rectangle is cancelled, the owning workflow's logical
pending selection is cancelled too.

Booklet changes also clear transient metadata, accepted/pending regions and stale
capture status from the previous booklet.

### Booklet format and Question response type have different roles

Booklet format constrains/defaults genuinely new capture. Persisted
QuestionResponseType remains authoritative for existing/imported/editing
Questions.

### AnswerFile assignment belongs to the ExamBooklet

A Question uses the AnswerFile assigned to its ExamBooklet. Several booklets may
share one AnswerFile, but one booklet does not span several answer documents.

### Export configuration is transient

Grouping depth, selected Units, question numbering and generated timestamp are
properties of one export, not Question-bank persistence.

### Search-match applicability is not output applicability

A Search result's current applicability explains why it matched the active
Search scope. Revision-output applicability must instead show the selected
Question's complete Subject-wide current placements.

## 5. Slice 1 — capture-selection state regression

### Status

**IMPLEMENTED / VERIFIED**

A user action that visually cancels the current pending PDF selection also clears
its logical owner and pane-local pending selection.

Add/Clear controls become disabled when no compatible selection exists.
Programmatic cleanup avoids callback recursion.

Successful activation of another booklet resets Question code/marks, accepted
regions, pending rectangles, transient shared-context state and stale status
text.

Regression coverage reproduces valid region -> ordinary PDF click -> no visible
rectangle -> no logical pending selection.

## 6. Slice 2 — curriculum selector synchronisation

### Status

**IMPLEMENTED / VERIFIED**

Code-driven hierarchy transitions keep visible ComboBox state and the
`CurriculumSelectionModel` aligned.

Examples:

```text
3.1.1.1 -> Unit 3 / Topic 3.1 / Subtopic 3.1.1 / Descriptor 3.1.1.1
3       -> Unit 3 / Topic empty / Subtopic empty / Descriptor empty
```

Regression tests cover complete-code entry, progressive editing, shortening back
to Unit and restoring existing Question classification.

## 7. Slice 3 — Working Subject capture filter

### Status

**IMPLEMENTED / VERIFIED**

One workspace-level **Working Subject** selector filters imported/pending
Question choices and unanswered Answer choices.

The value is transient UI state. It does not rewrite persisted Subject,
classification or Exam ownership.

Changing Working Subject respects pending-selection and accepted-region guards.

Search Questions, Corpus Audit and other bank-wide tools retain their own scope.

## 8. Slice 4 — response-type defaults and booklet format

### Status

**IMPLEMENTED / VERIFIED**

For genuinely new capture:

- MCQ booklets fix response type to Multiple Choice and marks to 1;
- Written Response booklets fix response type to Written Response while marks
  remain editable;
- Mixed booklets allow manual response type and conservative Written Response
  inference;
- recognised part-letter suffixes and greater-than-one-mark whole Questions may
  support Written Response inference in Mixed booklets;
- one mark alone never implies Multiple Choice;
- imported, edited and legacy-split Questions retain persisted response type.

The MCQ one-mark invariant is enforced below the UI.

## 8A. Additional capture hardening — multiple answer PDFs

### Status

**IMPLEMENTED / VERIFIED**

Answer Capture resolves AnswerFile from the Question's ExamBooklet.

Behaviour:

- mapped booklet -> restore assigned AnswerFile automatically;
- several booklets may share one AnswerFile;
- different booklet assignment -> switch automatically;
- unmapped booklet -> clear previous file and show `Choose PDF...`;
- choosing a PDF persists the booklet assignment.

Persistence rejects cross-Exam assignment, conflicting regions and one Answer
spanning multiple files.

The MCQ/Paper 1 shared-answer plus Paper 2 separate-answer workflow was manually
verified, including restart persistence.

## 9. Slice 5 — shared context for independent Questions and MCQs

### Status

**IMPLEMENTED / VERIFIED**

For a genuinely new independent MCQ, the capture UI exposes:

```text
Shared context with next question
```

If no context is inherited, selecting it begins automatic shared-context
capture. One context region is sufficient in Sprint 10.

Saving records the context and a booklet-scoped continuation for the supported
immediate numeric successor.

Examples:

```text
5   -> 6
Q5  -> Q6
Q09 -> Q10
```

If Q5 leaves continuation pending and Q7 is captured first, Q7 neither inherits
nor consumes the Q5 context. Q6 may still inherit later.

An inherited Question starts with continuation clear. Selecting it extends the
same context one more Question without recapture; leaving it clear consumes the
continuation when the intended successor saves successfully.

Independent MCQs sharing context never acquire a common SourceQuestion.

Early duplicate Question-code feedback was also added. Duplicate detection
disables Save without discarding accepted regions/metadata.

## 9A. Search and Question-editing hardening

### Status

**IMPLEMENTED / VERIFIED**

Search Questions gained a clearer separation between search filters and selected
Question state.

Implemented behaviour includes:

- functional minimum width of 900;
- resized width/height persistence across hide/show;
- X/Y restoration across hide/show, including full-height snapped placement;
- non-shrinking selector labels;
- a selected-Question classification section independent of Search filters;
- read-only stored Unit/Topic/Subtopic and existing Descriptor display;
- optional child-Descriptor selection when stored classification is a Subtopic;
- dirty-state tracking and a separate inline `Save`;
- Save / Discard Changes / Cancel protection when navigating away dirty;
- broader classification correction delegated to `Edit Question`;
- Classification removed from `Edit Metadata`.

Search -> Edit Question transfers to the non-modal editor. The editor restores
the saved PDF position and displays the stored region with a light-grey overlay.
PDF lifecycle handling closes editor-owned PDFs after Save or Cancel without
breaking sustained capture workflows.

## 9B. Question-specific revision-output applicability

### Status

**IMPLEMENTED / VERIFIED**

Schema v12 and `QuestionOutputApplicabilityRepository` persist explicit
Question/current-node exclusions.

Rules:

- default/absence = included under normal derived applicability;
- exclusion suppresses one current Subtopic/Descriptor placement;
- current node must be in the same Subject;
- mapping and historical classification remain unchanged;
- repository reads store node IDs rather than reconstructing curriculum.

`RevisionCorpusBuilder` applies exclusions before placement, statistics, HTML and
SCORM rendering.

Search exposes a Revision Output Applicability list with Include/Exclude actions.
Updates persist immediately.

The panel resolves complete Subject-wide current applicability independently of
the Search filter. Regression coverage proves that a Question mapping to two
current Descriptors still displays both output placements when Search itself is
narrowed to one Descriptor.

## 9C. Shared Context terminology migration

### Status

**IMPLEMENTED / VERIFIED**

Java/domain/user-facing terminology had already moved to Shared Context. Schema
v13 completes that alignment for live database columns.

Runtime SQL now uses:

```text
shared_context_capture_required
shared_context_status
```

Historical v4-v12 migrations/tests preserve `preamble_*` where required. The
legacy workbook's external `Preamble` label is not silently rewritten.

Populated v12 -> v13 migration tests verify data, constraints, output exclusions,
Question reload and database reopen.

## 10. Slice 6 — revision HTML grouping choice

### Status

**IMPLEMENTED / VERIFIED**

Revision HTML and SCORM export offer:

```text
Subtopic
Descriptor
```

### Subtopic mode

Descriptor-classified Questions roll up to their parent Subtopic. Descriptor
headings are suppressed.

For curricula with direct Descriptor children under Topic, Topic is the practical
roll-up page.

### Descriptor mode

Descriptor-classified Questions remain beneath Descriptor headings.

A Question classified directly to a Subtopic remains visible rather than
disappearing when Descriptor grouping is chosen.

Grouping never rewrites persisted classification/mapping.

## 11. Slice 7 — response-type ordering

### Status

**IMPLEMENTED / VERIFIED**

Within each output bucket:

1. Multiple Choice;
2. Written Response;
3. Other/Unknown renderable Questions.

Presentation grouping is resolved before ordering so one multipart SourceQuestion
card is not split.

The renderer also emits response-type section headings. When multiple response
types are present, page navigation links target those sections.

## 12. Slice 8 — page-local Question numbering

### Status

**IMPLEMENTED / VERIFIED**

Every generated question page begins at `Question 1` and increments across only
the presentations rendered on that page.

A multipart presentation receives one displayed Question number.

Anchors, image alternative text and answer references use the same page-local
display number.

Persisted Question IDs/codes are unchanged.

## 13. Slice 9 — empty navigation pruning and Unit selection

### Status

**IMPLEMENTED / VERIFIED**

Only branches containing student-facing presentations are generated/linked.

Therefore empty Units, Topics, Subtopics and Descriptor sections are omitted.

Export dialogs list non-empty Units, all selected by default. A user may choose
an explicit subset.

Unit scope is transient output configuration and is applied consistently to:

- corpus scope;
- rendered assets;
- validation/statistics;
- Revision HTML;
- SCORM.

Grouping availability is recalculated for the selected Unit set.

## 14. Slice 10 — generated-site status information

### Status

**IMPLEMENTED / VERIFIED**

The subject index uses student-facing presentation/card counts rather than stored
Question-row counts. A multipart SourceQuestion card therefore counts once.

The site displays Subject, syllabus/version and one generated-at timestamp.

`RevisionExportService` captures the timestamp once. Renderer tests use a fixed
time and service tests inject a fixed `Clock`, avoiding wall-clock-dependent
tests.

Operational diagnostics remain application/export-result concerns rather than
student site statistics.

## 15. TestFX/Xvfb and lifecycle hardening

### Status

**IMPLEMENTED / VERIFIED**

Sprint 10 exposed CI-only lifecycle races around modal JavaFX Dialog reuse.

The shared TestFX harness now distinguishes:

- synchronous `fire()` for non-modal transfers;
- deferred actions for controls that open `showAndWait()` dialogs;
- actual showing JavaFX windows/dialog panes rather than retained hidden nodes.

This removed brittle text-based `robot.clickOn("Close")` assumptions and improved
headless Xvfb execution speed/reliability.

## 16. Explicitly outside Sprint 10

The following remain backlog/future work:

- multi-page automatic shared-context capture;
- optional MCQ explanation regions;
- pruning empty managed source directories after successful Exam relocation;
- direct multiple original classifications;
- explicit out-of-scope source disposition;
- broad import/reconciliation/reporting hardening;
- clipboard/image-attachment Question capture;
- SQLite-backed full persistence-to-export integration hardening;
- Exam Builder;
- printable/vector-preserving assessment/solution generation;
- deployment packaging.

The following items were originally deferred or expected as later polish but were
completed during Sprint 10 and therefore are not backlog:

- Search dialog position persistence;
- multipart source-provenance simplification;
- response-type section headings;
- SCORM grouping/Unit-selection parity;
- Question-level output applicability exceptions.

## 17. Verification evidence

Automated coverage includes:

- visual PDF cancellation clearing logical selection ownership;
- booklet-switch capture-state reset;
- complete-code hierarchy synchronisation and shortening;
- Working Subject queue filtering;
- booklet-format/response-type defaults and MCQ one-mark invariants;
- booklet-to-AnswerFile migration/persistence and cross-file rejection;
- independent MCQ Shared Context creation, reuse, extension and consumption;
- restart-safe and out-of-order continuation;
- duplicate-code feedback preserving accepted regions;
- response grouping and response-type section navigation;
- page-local numbering reset;
- empty-branch pruning;
- selected-Unit HTML/SCORM export;
- multipart student-facing count semantics;
- fixed/injected generated timestamps;
- Question-specific exclusion persistence/corpus filtering;
- Subject-wide output applicability independent of narrowed Search scope;
- Search classification dirty/save/navigation behaviour;
- stored-region edit overlay/navigation;
- Search geometry restoration;
- v12 -> v13 populated migration;
- snapshot/import/repository/runtime SQL after Shared Context rename.

Manual verification includes:

- shared/separate Answer PDFs across multiple booklets;
- Q5/Q6/Q7 Shared Context continuation;
- restart persistence;
- different classifications on shared-context MCQs;
- out-of-sequence protection;
- booklet-switch reset behaviour.

The full Maven suite was green at final implementation checkpoint.

GitHub Actions CI run 59 completed successfully for final branch head
`07b7b337`.

## 18. Closeout state

Sprint 10 implementation is complete. This document is the canonical final-state
record for the feature branch.

Repository merge state is deliberately recorded separately:

```text
feature/capture-output: 07b7b337  (verified, CI green)
main:                   5a1e5a9   (Sprint 10 not yet merged)
```

After protected-main merge, only merge-state references need updating; no
additional Sprint 10 implementation is currently planned.
