# Sprint 10 — Capture Hardening and Revision Output Refinement

> **Status:** DESIGN AGREED — IMPLEMENTATION NOT YET COMPLETE  
> **Start date:** 20 September 2026  
> **Branch:** `feature/capture-output`  
> **Starting `main`:** `5a1e5a9`

## 1. Purpose

Sprint 10 combines two closely related kinds of work exposed by real use of the
bank:

1. repair capture-state/UI defects that make sustained Question capture
   unreliable; and
2. refine the existing revision HTML output so it reflects how the generated
   resource is actually intended to be used by students.

The sprint does not rebuild retrieval, persistence or the revision corpus from
scratch. It extends existing architecture established in Sprints 05–09.

## 2. Starting point

At Sprint 10 start:

- Sprint 09 is merged to `main` (`2533586`);
- protected-main workflow has been exercised through PR #1;
- current `main` is `5a1e5a9`;
- SQLite schema version is 8;
- SourceQuestion and SharedQuestionContext are already separate persisted
  concepts;
- revision corpus, presentation planning and static HTML export already exist;
- QuestionResponseType is persisted on each Question;
- Question and Answer capture already share explicit PDF-selection ownership.

No schema migration is expected for the agreed Sprint 10 scope. A migration must
not be introduced unless implementation proves an existing persisted relation is
actually insufficient.

## 3. Sprint goals

Sprint 10 must:

- make pending PDF-selection state internally consistent;
- make curriculum code entry and hierarchy controls display the same path;
- allow capture work to be focused on one working Subject;
- remove repetitive response-type selection where Question metadata makes the
  normal answer type clear;
- expose existing shared-context semantics for independent Questions/MCQs;
- make revision HTML grouping, ordering, numbering and navigation match the
  intended student resource;
- replace internal capture-count status with useful generated-resource metadata.

## 4. Cross-cutting design rules

### Persisted identity is not presentation state

Question IDs, source Question codes, curriculum classification and mappings are
not rewritten to obtain nicer HTML grouping or numbering.

### Shared context is not multipart identity

Questions may share one SharedQuestionContext without sharing a SourceQuestion.
The UI must not manufacture multipart identity merely to support a shared MCQ
stimulus.

### A shared context may cross output buckets

Two independent Questions may use the same stimulus while belonging to different
Subtopics/Descriptors. Each retains its own classification. Output repeats the
shared context wherever required to make a question understandable; it may be
suppressed only when the same context is already immediately applicable within
the same generated page/group.

### Visual selection and logical selection are one user interaction

If the PDF no longer displays a pending rectangle because the user cancelled it,
the owning capture workflow must not continue to report a pending selection.

### Automatic response type is a default, not a persisted law

Strong code/marks evidence may select Written Response for a new capture. The
user may still override the default. Existing/imported/editing Question response
type must be restored from persistence rather than silently re-inferred.

### Export configuration is transient

Grouping depth, selected Units, question numbering and generated timestamp are
properties of one export, not Question-bank persistence.

## 5. Slice 1 — capture-selection state regression

### Problem

After selecting a valid PDF region, an ordinary click can make the visible
selection rectangle disappear because the new rectangle is below the minimum
size. The application-level CaptureSelectionState and Question/Answer capture
pane can nevertheless retain the old pending selection.

### Required behaviour

- A user action that visually cancels the current pending PDF selection also
  clears its logical owner and pane-local pending selection.
- Add/Clear controls become disabled when no compatible selection exists.
- Programmatic clearing initiated by the owning workflow must not recurse through
  the cancellation callback.
- Anchored/double-click shared-context selection behaviour must remain intact.

### Acceptance

Regression coverage must reproduce: valid region -> ordinary PDF click -> visible
rectangle gone -> no logical pending selection.

## 6. Slice 2 — curriculum selector synchronisation

### Problems

Real use has demonstrated both:

- a complete code can resolve Unit/Topic/Subtopic/Descriptor while the visible
  Topic ComboBox appears blank; and
- after entering a complete code, replacing it with only the Unit can select or
  redisplay the previous Topic.

These occur without requiring PDF-region interaction and are therefore a
CurriculumSelectorPane synchronisation defect, not merely a capture-selection
side effect.

### Required behaviour

For every code-driven hierarchy transition, visible ComboBox values and the
CurriculumSelectionModel must agree.

Examples:

```text
3.1.1.1 -> Unit 3 / Topic 3.1 / Subtopic 3.1.1 / Descriptor 3.1.1.1
3       -> Unit 3 / Topic empty / Subtopic empty / Descriptor empty
```

Programmatic clearing must clear both JavaFX selection state and displayed/value
state where required.

### Acceptance

UI tests must assert actual ComboBox values, not only model fields, for full-code
paste/typing, progressive editing, shortening back to Unit and restoring an
existing Question classification.

## 7. Slice 3 — Working Subject capture filter

### Purpose

Allow sustained work on Chemistry without unrelated Engineering Questions or
Answers appearing in capture queues.

### Design

Add one workspace-level **Working Subject** selector in the left/capture pane.
It is a transient UI filter/default, not persisted metadata.

The selected Subject filters at least:

- imported/pending Question capture choices; and
- unanswered Answer capture choices.

New Question classification remains consistent with the active working Subject.
Changing the Working Subject must respect pending-selection/accepted-region
transition guards so data is not silently discarded.

Search Questions, Corpus Audit and other bank-wide administrative tools retain
their own explicit Subject/scope controls and are not silently restricted by the
Working Subject.

## 8. Slice 4 — response-type defaults

### Required inference

For **new Question capture**:

- if the Question code has a recognised part-letter suffix, default to
  `WRITTEN_RESPONSE`;
- if the Question code is a whole-number Question and marks > 1, default to
  `WRITTEN_RESPONSE`;
- otherwise do not force Written Response merely from code/marks.

The existing conservative SourceQuestionCodeParser should be reused for the
part-letter rule where appropriate rather than creating a competing parser.

When a new exam booklet is opened, the option to mark it as MCQ, short answer or both should be presented.

### Behaviour boundary

- The automatic selection is a default and remains manually overridable.
- Do not auto-select Multiple Choice from the inverse condition.
- Do not override persisted response type when loading imported Questions or
  editing existing Questions.

## 9. Slice 5 — shared context for independent Questions and MCQs

### Requirement

The normal capture UI must allow a Question to use SharedQuestionContext even
when it has no SourceQuestion multipart identity.

A valid example is:

```text
Shared stimulus
  -> MCQ 5 -> Subtopic A
  -> MCQ 6 -> Subtopic B
```

MCQ 5 and MCQ 6 remain independent Questions and keep independent curriculum
classification.

### Capture model

Expose an explicit general shared-context choice, conceptually:

```text
Shared context:
    None
    Capture new
    Reuse existing
```

Reuse is limited to compatible context (at minimum the same booklet/source
scope already required by persistence).

Do not silently carry a shared context to the next Question. A convenience to
reuse the previous context may be offered only as an explicit user action.

### Persistence

Existing SharedQuestionContext relationships are expected to be sufficient. Do
not create a SourceQuestion merely because two independent Questions share
context.

### Output

Shared context may be repeated on separate Subtopic/Descriptor pages. If several
adjacent presentations on one page legitimately use the same context, render it
once for that adjacent sequence where doing so remains unambiguous.

## 10. Slice 6 — revision HTML grouping choice

### Requirement

At export time choose how the student resource groups Questions where Descriptor
structure exists:

```text
Subtopic
Descriptor
```

### Subtopic mode

- Descriptor-classified Questions roll up to their parent Subtopic.
- Descriptor headings are not emitted as separate question buckets.
- Ordering remains deterministic and should retain curriculum order before
  Question order.
- For a Topic whose curriculum has direct Descriptor children and no Subtopics,
  Topic is the practical roll-up parent rather than inventing a fake Subtopic.

### Descriptor mode

- Descriptor-classified Questions remain beneath their Descriptor.
- A Question classified directly to a Subtopic must not disappear; present it as
  a general/unassigned-to-descriptor block within that Subtopic before or beside
  Descriptor sections using a clear deterministic rule.

### Persistence boundary

Grouping mode never changes stored classification or mapping relationships.

## 11. Slice 7 — MCQ before written-response output

Within each final output bucket, order student-facing presentations by response
category:

1. Multiple Choice;
2. Written Response;
3. unresolved/Unknown, if any are still renderable.

Within a response category retain deterministic source/curriculum ordering.
Multipart grouping is resolved before final card ordering so one multipart
SourceQuestion presentation is not split for display purposes.

Explicit `Multiple Choice` / `Written Response` section headings are not required
in Sprint 10; that remains backlog polish after the ordering is exercised.

## 12. Slice 8 — page-local Question numbering

### Requirement

Every generated question page starts at:

```text
Question 1
```

and increments only across presentations actually rendered on that page.

A multipart presentation receives one displayed Question number. Source Question
codes remain provenance and are not renumbered in persistence.

### Architecture

Displayed numbering belongs to page presentation/rendering rather than a single
global RevisionPresentationPlanner sequence. Anchors, alternative text and
answer references must use the same page-local displayed number.

## 13. Slice 9 — empty navigation pruning and Unit selection

### Default export

Generate/link only curriculum branches that contain at least one student-facing
revision presentation in the selected export configuration.

Therefore:

- an empty Unit has no subject-index link and no unnecessary Unit page;
- empty Topics/Subtopics are similarly omitted from navigation/pages;
- Descriptor sections with no presentations are omitted.

### Unit scope

Revision HTML export supports:

- **All non-empty Units** — default; or
- **Selected Units** — explicit subset.

Selecting Units is an output scope choice only. It does not alter corpus
classification/applicability.

## 14. Slice 10 — generated-site status information

### Student-facing status

The subject index should show useful resource metadata, including:

- Subject;
- syllabus/version;
- generation date and time;
- number of student-facing revision presentations/cards generated for this
  export.

Internal concepts such as stored captured parts are not relevant to the student
site and must not be presented as the primary output count.

The displayed revision-question count must use the same presentation semantics as
the generated pages so homepage/Unit counts do not appear contradictory merely
because multipart member Questions were grouped into one card.

### Export timestamp and determinism

Capture one generation timestamp for the export and reuse it consistently. Test
code should use a fixed/injected clock or explicit generated-at value so tests do
not become time-dependent.

Operational diagnostics such as missing source regions, unresolved preambles or
missing Answers may remain available in the application/export result rather
than being presented as student resource statistics.

## 15. Explicitly outside Sprint 10

The following remain backlog/future work:

- multi-page automatic shared-preamble capture;
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

## 16. Testing strategy

Implementation should proceed slice by slice with focused tests.

At minimum Sprint 10 requires regression coverage for:

- visual PDF cancellation clearing logical selection ownership;
- full-code visible curriculum hierarchy population;
- shortening a full code to Unit without stale Topic;
- Working Subject queue filtering and safe subject transitions;
- response-type default inference and manual override;
- independent MCQs sharing one context while retaining different
  classifications;
- context rendering across different output buckets/pages;
- Subtopic and Descriptor export grouping;
- response-type ordering;
- per-page numbering reset;
- empty-branch pruning and selected-Unit scope;
- stable generated status with a fixed timestamp.

Broader non-UI/headless UI suites should be run at meaningful checkpoints and at
sprint closeout, not after every small edit.

## 17. Documentation and closeout

During implementation:

- `docs/current-status.md` must distinguish planned from implemented slices;
- completed Sprint 10 work should be removed from `docs/design/backlog.md` if any
  deferred item is promoted during the sprint;
- architectural changes should update `docs/design/architecture-evolution.md`;
- `docs/issues.txt` should contain only still-active working defects, not resolved
  chat transcripts.

At sprint closeout, this document becomes the canonical final-state record and
must be updated with completed slices, verification evidence and merge state.
