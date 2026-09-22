# Sprint 10 — Capture Hardening and Revision Output Refinement

> **Status:** IMPLEMENTATION IN PROGRESS  
> **Start date:** 20 September 2026  
> **Status updated:** 22 September 2026  
> **Branch:** `feature/capture-output`  
> **Starting `main`:** `5a1e5a9`

## 1. Purpose

Sprint 10 combines two closely related kinds of work exposed by real use of the bank:

1. repair capture-state/UI defects that make sustained Question capture unreliable; and
2. refine the existing revision HTML output so it reflects how the generated resource is actually intended to be used by students.

The sprint does not rebuild retrieval, persistence or the revision corpus from scratch. It extends existing architecture established in Sprints 05–09.

## 2. Starting point and schema evolution

At Sprint 10 start:

- Sprint 09 was merged to `main` (`2533586`);
- protected-main workflow had been exercised through PR #1;
- current `main` was `5a1e5a9`;
- SQLite schema version was 8;
- SourceQuestion and SharedQuestionContext were already separate persisted concepts;
- revision corpus, presentation planning and static HTML export already existed;
- QuestionResponseType was persisted on each Question;
- Question and Answer capture already shared explicit PDF-selection ownership.

The sprint began with no expected schema migration. Implementation subsequently proved that three persisted relationships or hints were required.

Schema version 9 added booklet-level Question format so MCQ-only, Written-Response-only and Mixed booklets can drive new-Question capture without guessing from individual Questions.

Schema version 10 added nullable booklet-to-AnswerFile assignment. This supports exams where several question booklets may share one answer PDF while another booklet uses a different answer PDF. Existing data is migrated conservatively; relationships are not invented where legacy evidence is ambiguous.

Schema version 11 added nullable `exam_booklets.pending_mcq_shared_context_id`. This records only the restart-safe intent that one independent MCQ shared context should be offered to the immediate successor Question in the same booklet. Existing databases migrate with no pending continuation because legacy data does not establish such intent.

The latest supported SQLite schema version is therefore **11**.

## 3. Sprint goals

Sprint 10 must:

- make pending PDF-selection state internally consistent;
- make curriculum code entry and hierarchy controls display the same path;
- allow capture work to be focused on one working Subject;
- remove repetitive response-type selection where Question metadata or booklet format makes the normal answer type clear;
- support correct answer-PDF selection across exams containing multiple question booklets and multiple answer documents;
- expose existing shared-context semantics for independent Questions/MCQs without manufacturing multipart identity;
- make revision HTML grouping, ordering, numbering and navigation match the intended student resource;
- replace internal capture-count status with useful generated-resource metadata.

## 4. Cross-cutting design rules

### Persisted identity is not presentation state

Question IDs, source Question codes, curriculum classification and mappings are not rewritten to obtain nicer HTML grouping or numbering.

### Shared context is not multipart identity

Questions may share one SharedQuestionContext without sharing a SourceQuestion. The UI must not manufacture multipart identity merely to support a shared MCQ stimulus.

### Independent MCQ continuation is explicit and one-step

`Shared context with next question` records a one-Question continuation. It is booklet-scoped, restart-safe and sequence-aware. It is not inferred merely because the preceding Question has shared context.

### A shared context may cross output buckets

Two independent Questions may use the same stimulus while belonging to different Subtopics/Descriptors. Each retains its own classification. Output repeats the shared context wherever required to make a question understandable; it may be suppressed only when the same context is already immediately applicable within the same generated page/group.

### Visual selection and logical selection are one user interaction

If the PDF no longer displays a pending rectangle because the user cancelled it, the owning capture workflow must not continue to report a pending selection.

A successful booklet change must also clear transient Question metadata, accepted regions, pending rectangles and capture-status text from the previous booklet.

### Booklet format and Question response type have different roles

Booklet format constrains/defaults genuinely new capture. Persisted QuestionResponseType remains authoritative for existing/imported/editing Questions.

### AnswerFile assignment belongs to the ExamBooklet

A Question uses the AnswerFile assigned to its ExamBooklet. Several booklets may share one AnswerFile, but one booklet does not span several answer documents.

### Export configuration is transient

Grouping depth, selected Units, question numbering and generated timestamp are properties of one export, not Question-bank persistence.

## 5. Slice 1 — capture-selection state regression

### Status

**IMPLEMENTED / VERIFIED**

### Problem

After selecting a valid PDF region, an ordinary click could make the visible selection rectangle disappear because the new rectangle was below the minimum size. The application-level CaptureSelectionState and Question/Answer capture pane could nevertheless retain the old pending selection.

### Implemented behaviour

- A user action that visually cancels the current pending PDF selection also clears its logical owner and pane-local pending selection.
- Add/Clear controls become disabled when no compatible selection exists.
- Programmatic clearing initiated by the owning workflow does not recurse through the cancellation callback.
- Anchored/double-click shared-context selection behaviour remains intact.
- Successful activation of another booklet resets ordinary Question capture state so regions or pending selections from the old booklet cannot leak into the new one.
- Booklet activation also clears the stale green pending/saved status text associated with the previous capture state.

Regression coverage reproduces valid region -> ordinary PDF click -> visible rectangle gone -> no logical pending selection, and booklet A capture state -> booklet B -> no retained Question capture state.

## 6. Slice 2 — curriculum selector synchronisation

### Status

**IMPLEMENTED / VERIFIED**

### Problem

Real use demonstrated both:

- a complete code could resolve Unit/Topic/Subtopic/Descriptor while the visible Topic ComboBox appeared blank; and
- after entering a complete code, replacing it with only the Unit could select or redisplay the previous Topic.

### Implemented behaviour

For code-driven hierarchy transitions, visible ComboBox values and the CurriculumSelectionModel now agree.

Examples:

```text
3.1.1.1 -> Unit 3 / Topic 3.1 / Subtopic 3.1.1 / Descriptor 3.1.1.1
3       -> Unit 3 / Topic empty / Subtopic empty / Descriptor empty
```

UI regression tests assert actual ComboBox values for complete-code entry, progressive editing, shortening back to Unit and restoring existing Question classification.

## 7. Slice 3 — Working Subject capture filter

### Status

**IMPLEMENTED / VERIFIED**

### Purpose

Allow sustained work on Chemistry without unrelated Engineering Questions or Answers appearing in capture queues.

### Implemented design

One workspace-level **Working Subject** selector is used in the capture workspace. It is transient UI state, not persisted metadata.

The selected Subject filters at least:

- imported/pending Question capture choices; and
- unanswered Answer capture choices.

New Question classification remains consistent with the active Working Subject. Changing the Working Subject respects pending-selection/accepted-region transition guards so data is not silently discarded.

Search Questions, Corpus Audit and other bank-wide administrative tools retain their own explicit Subject/scope controls and are not silently restricted by the Working Subject.

## 8. Slice 4 — response-type defaults and booklet format

### Status

**IMPLEMENTED / VERIFIED**

Each new ExamBooklet records one of:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
MIXED
UNSPECIFIED
```

`UNSPECIFIED` exists only for unresolved legacy data.

For genuinely new Question capture:

- an MCQ booklet fixes response type to Multiple Choice and marks to exactly 1;
- a Written Response booklet fixes response type to Written Response while marks remain editable;
- a Mixed booklet permits manual response-type selection and conservative Written Response inference;
- a recognised part-letter suffix may infer Written Response in a Mixed booklet;
- a whole-number Question worth more than one mark may infer Written Response in a Mixed booklet;
- one mark alone never implies Multiple Choice;
- imported, edited and legacy-split Questions retain their persisted response type.

Schema version 9 persists the booklet format. Existing rows migrate to `UNSPECIFIED`, not `MIXED`, because legacy data does not justify inventing a format.

The MCQ invariant is enforced below the UI: an explicitly Multiple Choice Question must have exactly one mark.

## 8A. Additional capture hardening — multiple answer PDFs

### Status

**IMPLEMENTED / VERIFIED**

Real use established the following AnswerFile cardinality:

```text
one ExamBooklet -> zero or one assigned AnswerFile
one AnswerFile  -> zero or many ExamBooklets
```

A Question therefore obtains its answer document from its ExamBooklet.

A valid real-world arrangement is:

```text
MCQ booklet ──┐
              ├── Answers A
Paper 1 ──────┘

Paper 2 ───────── Answers B
```

Answer Capture behaviour is:

- when the current booklet has an assigned AnswerFile, restore it automatically;
- when two booklets share an AnswerFile, reuse that file;
- when the next booklet has a different assigned AnswerFile, switch automatically;
- when the next booklet has no assignment, clear the previous file and show `Choose PDF...`;
- selecting a PDF persists that booklet assignment for future Questions and later application sessions.

One Answer may never span multiple answer documents.

Schema version 10 persists this relationship in `exam_booklets.answer_file_id`. Existing region data is used for migration only when it establishes one unambiguous mapping.

Persistence also rejects:

- an AnswerFile assignment from another Exam;
- regions from a different file than the booklet assignment;
- one Answer containing regions from more than one AnswerFile.

The MCQ/Paper 1 shared-answer plus Paper 2 separate-answer workflow has been verified manually in the real application, including restart persistence.

## 9. Slice 5 — shared context for independent Questions and MCQs

### Status

**IMPLEMENTED / VERIFIED**

### Requirement

The normal capture UI must allow independently classified MCQs to share one SharedQuestionContext without creating a SourceQuestion multipart identity.

Example:

```text
Shared stimulus
  -> MCQ 5 -> Subtopic A
  -> MCQ 6 -> Subtopic B
```

MCQ 5 and MCQ 6 remain independent Questions and retain independent curriculum classification.

### Implemented capture workflow

For a genuinely new independent MCQ, the normal Question capture UI exposes:

```text
Shared context with next question
```

The checkbox is clear by default.

If the current MCQ has no inherited context:

- selecting the checkbox immediately begins automatic shared-context capture;
- one captured context region is sufficient for Sprint 10;
- no separate New/Reuse selector, label prompt, confirmation dialog or context picker is required;
- saving the Question stores the context and persists a booklet-scoped continuation for the immediate successor.

If the current MCQ inherited context from the previous Question:

- the checkbox begins clear;
- the context is applied automatically only when the typed Question code is the immediate supported successor;
- selecting the checkbox extends the same context to one further Question without recapturing it.

For example:

```text
Q5 tick -> capture context -> save Q5
Q6 inherits -> tick -> save Q6
Q7 inherits -> leave unticked -> save Q7
Q8 does not inherit
```

### Sequence-aware continuation

The continuation is deliberately not a generic “next thing captured” flag.

Supported automatic succession is conservative numeric succession such as:

```text
5   -> 6
Q5  -> Q6
Q09 -> Q10
```

If Q5 has a pending continuation and Q7 is captured before Q6:

- Q7 does not inherit the Q5 context;
- Q7 does not consume the pending continuation;
- Q6 may still be captured later and receive the context.

Unusual codes whose sequence cannot be established conservatively do not receive automatic continuation by guesswork.

### Restart and booklet semantics

Schema version 11 adds nullable `exam_booklets.pending_mcq_shared_context_id`.

This provides restart-safe intent:

- save Q5 with continuation;
- close the application;
- reopen the same booklet;
- Q6 can still inherit the same context.

The continuation is booklet-scoped. Another booklet cannot consume it. EDIT and IMPORTED operations do not consume it. Transient mode/booklet changes clear unsaved checkbox/capture state but do not erase a legitimate persisted continuation belonging to the previous booklet.

### Identity and persistence rules

Independent MCQs sharing context never create a common SourceQuestion.

`SourceQuestion` remains the multipart source identity used by cases such as `21a`, `21b`. SharedQuestionContext remains independently reusable.

The service layer enforces the sequence-aware continuation rule so an out-of-sequence Question cannot accidentally inherit or consume context even if UI behaviour changes later.

### Additional capture hardening completed with Slice 5

- Entering a Question code that already exists in the active booklet now produces immediate non-modal feedback before the user repeats classification and region capture work.
- Duplicate detection disables Save while preserving already accepted regions and metadata so correcting the code does not lose work.
- Switching booklets clears stale Question metadata, accepted/pending regions and the green pending-status message.
- User-facing capture/correction wording has been standardised on **Shared Context**. Historical/internal identifiers such as `PreambleStatus`, legacy database fields and existing method names may remain where renaming would add migration/refactor risk without user benefit.

### Verification

Automated coverage verifies:

- independent MCQs share one context while remaining independent Questions;
- the context survives application restart intent through booklet persistence;
- continuation does not leak across booklets;
- failed continuation persistence rolls back atomically;
- Q5 -> Q7 does not inherit or consume a Q5 continuation, while Q5 -> Q6 does;
- Q6 may extend the same context to Q7 without recapture;
- Q7 may consume the continuation by saving unticked;
- duplicate Question codes are reported before classification/capture is repeated;
- duplicate feedback does not discard accepted regions;
- booklet activation clears prior transient Question capture and status text;
- legacy multipart shared-context capture and recapture workflows remain operational.

Manual acceptance in the real application confirmed the expected Q5/Q6/Q7 flow, restart behaviour, independent classification, out-of-sequence protection and booklet-switch reset behaviour.

## 10. Slice 6 — revision HTML grouping choice

### Status

**PLANNED**

### Requirement

At export time choose how the student resource groups Questions where Descriptor structure exists:

```text
Subtopic
Descriptor
```

### Subtopic mode

- Descriptor-classified Questions roll up to their parent Subtopic.
- Descriptor headings are not emitted as separate question buckets.
- Ordering remains deterministic and should retain curriculum order before Question order.
- For a Topic whose curriculum has direct Descriptor children and no Subtopics, Topic is the practical roll-up parent rather than inventing a fake Subtopic.

### Descriptor mode

- Descriptor-classified Questions remain beneath their Descriptor.
- A Question classified directly to a Subtopic must not disappear; present it as a general/unassigned-to-descriptor block within that Subtopic before or beside Descriptor sections using a clear deterministic rule.

### Persistence boundary

Grouping mode never changes stored classification or mapping relationships.

## 11. Slice 7 — MCQ before written-response output

### Status

**PLANNED**

Within each final output bucket, order student-facing presentations by response category:

1. Multiple Choice;
2. Written Response;
3. unresolved/Unknown, if any are still renderable.

Within a response category retain deterministic source/curriculum ordering. Multipart grouping is resolved before final card ordering so one multipart SourceQuestion presentation is not split for display purposes.

Explicit `Multiple Choice` / `Written Response` section headings are not required in Sprint 10; that remains backlog polish after the ordering is exercised.

## 12. Slice 8 — page-local Question numbering

### Status

**PLANNED**

Every generated question page starts at `Question 1` and increments only across presentations actually rendered on that page.

A multipart presentation receives one displayed Question number. Source Question codes remain provenance and are not renumbered in persistence.

Displayed numbering belongs to page presentation/rendering rather than a single global RevisionPresentationPlanner sequence. Anchors, alternative text and answer references must use the same page-local displayed number.

## 13. Slice 9 — empty navigation pruning and Unit selection

### Status

**PLANNED**

### Default export

Generate/link only curriculum branches that contain at least one student-facing revision presentation in the selected export configuration.

Therefore:

- an empty Unit has no subject-index link and no unnecessary Unit page;
- empty Topics/Subtopics are similarly omitted from navigation/pages;
- Descriptor sections with no presentations are omitted.

### Unit scope

Revision HTML export supports:

- **All non-empty Units** — default; or
- **Selected Units** — explicit subset.

Selecting Units is an output scope choice only. It does not alter corpus classification/applicability.

## 14. Slice 10 — generated-site status information

### Status

**PLANNED**

The subject index should show useful resource metadata, including:

- Subject;
- syllabus/version;
- generation date and time;
- number of student-facing revision presentations/cards generated for this export.

Internal concepts such as stored captured parts are not relevant to the student site and must not be presented as the primary output count.

The displayed revision-question count must use the same presentation semantics as the generated pages so homepage/Unit counts do not appear contradictory merely because multipart member Questions were grouped into one card.

Capture one generation timestamp for the export and reuse it consistently. Test code should use a fixed/injected clock or explicit generated-at value so tests do not become time-dependent.

Operational diagnostics such as missing source regions, unresolved shared context or missing Answers may remain available in the application/export result rather than being presented as student resource statistics.

## 15. Explicitly outside Sprint 10

The following remain backlog/future work:

- multi-page automatic shared-context capture;
- optional MCQ explanation regions;
- multipart provenance-decoration simplification;
- deciding whether explicit response-type section headings are useful;
- Search Questions dialog screen-position persistence across edit hide/show;
- pruning empty managed source directories after successful Exam relocation;
- direct multiple original classifications;
- Question-level applicability exceptions;
- explicit out-of-scope source disposition;
- broad import/reconciliation/reporting hardening;
- clipboard/image-attachment Question capture;
- Exam Builder;
- printable assessment/solution generation;
- deployment packaging;
- any SCORM-specific option UI that would duplicate HTML presentation semantics.

## 16. Testing strategy and current evidence

Implementation proceeds slice by slice with focused tests, followed by meaningful broader checkpoints.

Completed capture work now has regression coverage for:

- visual PDF cancellation clearing logical selection ownership;
- booklet switching clearing transient Question capture state and status text;
- full-code visible curriculum hierarchy population;
- shortening a full code to Unit without stale Topic;
- Working Subject queue filtering and safe subject transitions;
- booklet-format and response-type defaults;
- MCQ one-mark persistence invariants;
- booklet-to-AnswerFile persistence;
- shared AnswerFile use across several booklets;
- distinct AnswerFiles across booklets in the same Exam;
- unresolved booklet behaviour;
- rejection of cross-Exam or cross-file Answer relationships;
- independent MCQ SharedQuestionContext creation, reuse, extension and consumption;
- restart-safe pending MCQ continuation;
- sequence-aware continuation and out-of-order capture;
- early duplicate Question-code feedback without losing accepted regions;
- legacy multipart/shared-context correction workflows after terminology cleanup.

The schema-version test expectation has been corrected for schema version 11 and the relevant database/schema tests are green.

The focused workflow command covering `WorkflowCaptureTests` and `WorkflowStateEditingTests` is green after the Slice 5 implementation, follow-up fixes and Shared Context terminology sweep.

Remaining Sprint 10 work requires regression coverage for:

- context rendering across different output buckets/pages;
- Subtopic and Descriptor export grouping;
- response-type ordering;
- per-page numbering reset;
- empty-branch pruning and selected-Unit scope;
- stable generated status with a fixed timestamp.

Broader non-UI/headless UI suites should be run at meaningful checkpoints and at sprint closeout, not after every small edit.

## 17. Documentation and closeout

During implementation:

- `docs/current-status.md` must distinguish planned from implemented slices;
- completed Sprint 10 work should be removed from `docs/design/backlog.md` if any deferred item is promoted during the sprint;
- architectural changes should update `docs/design/architecture-evolution.md` where a durable cross-sprint architectural rule is introduced;
- `docs/issues.txt` should contain only still-active working defects, not resolved chat transcripts.

At sprint closeout, this document becomes the canonical final-state record and must be updated with completed slices, verification evidence and merge state.
