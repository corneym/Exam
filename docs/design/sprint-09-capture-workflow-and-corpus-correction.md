# Sprint 09 — Capture Workflow and Corpus Correction

## Purpose

Sprint 09 is driven by sustained use of the application while completing real
Question-bank data.

Sprint 08 established the required curriculum-authoring, mapping-coverage,
metadata-correction and corpus-audit foundations. The next problem is not a new
persistence architecture: it is that several ordinary capture and correction
workflows are still slower, less predictable or less legible than they need to
be for sustained corpus work.

Sprint 09 therefore has three connected goals:

1. make Question and Answer capture controls remain usable across the supported
   workspace width and selection states;
2. make queues, Search and Corpus Audit follow source/natural Question order
   rather than persistence/capture order; and
3. add the correction operations now required by real legacy data: shared
   preamble replacement, exam-level metadata correction and deliberate splitting
   of one imported Question into multipart parts.

Revision HTML presentation redesign is not part of this sprint. Those related
requirements remain together in `backlog.md` as a later output-focused design
pass.

## Starting point

Sprint 08 is complete and merged to `main`.

The existing architecture already provides:

- explicit `SourceQuestion` multipart identity;
- explicit `SharedQuestionContext` and ordered context regions;
- transactional Question capture/edit persistence;
- legacy metadata correction;
- response-type-aware Question/Answer semantics;
- explicit capture-selection ownership;
- asynchronous Question Search and background Question/Answer save work;
- corpus audit filters and routing into existing correction workflows.

Sprint 09 must reuse those concepts rather than create parallel capture,
metadata or queue systems.

## Design principles

### User-visible state should match logical capture ownership

If Answer capture owns the active selection, Question controls that cannot safely
act on it should be disabled. If no pending region exists, Add Region/Clear
controls that require one should not appear actionable.

### Responsive layout is functional, not merely cosmetic

A narrow but supported capture pane must not hide the meaning of field labels or
button actions. Controls may reflow or use compact sizing, but important labels
must not collapse into ambiguous `...` text.

### Source order is distinct from database identity

Insertion IDs remain persistence identities. They must not define user-facing
exam order.

Where a source-order queue is required, use deterministic ordering by:

```text
provider / authority
-> year
-> booklet
-> natural Question code
```

Natural Question ordering must handle numeric question numbers and alphabetic
parts correctly.

### Corrections preserve authoritative relationships

A correction should change the smallest authoritative entity that is wrong:

- wrong Exam metadata -> correct the Exam/provider relationship, not every
  linked Question display string;
- wrong shared preamble source -> replace the SharedQuestionContext source
  regions, not independent copies on each part;
- a legacy `3` that is really `3a` + `3b` -> create explicit multipart identity
  and separate Question rows, not encode two parts into one region list.

### Slow UI transitions should be measured before optimised

Entering Imported Questions currently performs several reconciliation/refresh
operations. Determine which work is expensive and whether it needs to run on
every activation before changing threading or adding progress UI.

### No silent inference from filenames or legacy patterns

Known-PDF metadata reuse must be based on persisted identity/content rules, not a
similar filename. Legacy split/preamble operations must require explicit user
confirmation of the intended structure.

## Work slices

### Slice 1 — Baseline, source ordering and UI-state regressions

Before changing layouts or correction workflows, establish reusable behaviour
and tests for the rules Sprint 09 depends on.

Add/confirm regression coverage for:

- natural Question-code ordering (`3`, `3a`, `3b`, `10`, etc.);
- provider/year/booklet/question source ordering;
- current capture-selection ownership exposed to pane enablement;
- Add Region/Clear disabled when no compatible pending selection exists;
- representative narrow preview-pane widths without loss of essential control
  labels.

Prefer one reusable ordering component rather than independent comparators in
Answer capture, Corpus Audit and any Search list that needs source ordering.

**Outcome:** later UI and queue slices have explicit behavioural rules instead of
ad-hoc local sorting/disable logic.

### Slice 2 — Answer capture usability and ownership

Fix the Answer pane for sustained capture use.

Required behaviour:

- button text remains visible when the left workspace pane is narrowed within
  the supported range;
- adding an accepted Answer region does not cause excessive blank vertical
  growth;
- region/page status is placed where it cannot compress the button row, with the
  preferred design being a right-aligned status in the row above;
- Answer-region previews consume only required space;
- the unanswered queue uses source/natural ordering, not capture-time/database
  insertion order;
- Add Region and Clear are enabled only when their actions are valid;
- while Answer capture owns the active selection, incompatible Question-pane
  region actions are disabled.

Do not change persisted Answer-completeness semantics in this slice.

**Outcome:** ordinary Answer capture remains legible and predictable while the
workspace is resized and while selections move between panes.

### Slice 3 — Question capture and metadata-dialog usability

Refine the Question-side controls without altering the persisted response-type
model.

Required behaviour:

- Question field labels remain readable at supported narrow pane widths;
- Question number entry is compact rather than consuming unnecessary width;
- normal Question capture chooses Multiple Choice versus Written Response using
  explicit radio buttons rather than a combo box;
- Edit Question Metadata labels no longer truncate to ambiguous `...`;
- Question Add Region/Clear enablement follows pending-selection state;
- review the `Questions -> Capture New Questions` menu action and remove it if it
  has no distinct safe function beyond the already visible mode control.

If the menu action is retained, its distinct purpose must be evident and tested.

**Outcome:** Question capture and metadata correction remain readable and expose
only meaningful actions.

### Slice 4 — Imported Questions activation performance

Measure the delay observed when switching to Imported Questions.

Profile/measure at least:

- source-question backfill;
- shared-context reconciliation;
- `questionRepository.findAll()` or equivalent reconstruction;
- imported queue filtering/sorting;
- PDF/booklet activation;
- JavaFX control population.

Then change only the work shown to be responsible.

Possible valid outcomes include:

- moving safe persistence/reconstruction work off the FX thread;
- performing one-time reconciliation at a better lifecycle boundary;
- avoiding repeated full-bank reconstruction;
- refreshing only data that can actually have changed;
- adding explicit progress only when real unavoidable work remains.

All asynchronous changes require stale/lifecycle protection consistent with
Question Search and PDF-load precedent.

**Outcome:** entering Imported Questions no longer causes unexplained avoidable
UI delay, and the reason for any remaining wait is explicit.

### Slice 5 — Shared-preamble replacement / recapture

Add a first-class edit workflow for an existing `SharedQuestionContext`.

The user must be able to select a stored shared preamble and replace its source
region(s).

Persistence requirements:

- replacement is transactional;
- all existing Questions linked to the same SharedQuestionContext continue to
  share that one context after success;
- source-question shared-context consistency remains valid;
- old persisted regions remain intact until the replacement commit succeeds;
- cancellation or failure leaves the old regions untouched;
- linked Questions do not need to be rewritten as independent copies;
- reload reconstructs the new context correctly;
- revision/Search presentation uses the replacement context after reload.

This slice does not have to implement the separate multi-page automatic
preamble-capture workflow, but it must not introduce persistence assumptions
that would prevent multiple ordered context regions.

**Outcome:** an incorrectly captured shared preamble can be corrected without
breaking multipart/shared-context identity.

### Slice 6 — Exam metadata correction and known-PDF recognition

Add supported correction at the entity that actually owns the incorrect data.

#### Exam metadata correction

Provide an Exam-level correction workflow suitable for cases such as a legacy
import where provider/year naming produced display like `2022 QCAA 2022`.

Design and validate editable fields, including at least the currently observed
exam/provider naming problem. The workflow must preserve linked:

- ExamBooklets;
- SourceDocuments;
- Questions;
- Answers;
- SourceQuestions;
- SharedQuestionContexts.

Natural-identity collisions or attempts to merge incompatible existing Exam
records must be rejected explicitly rather than silently reassigning data.

#### Import Exam dialog

- fix truncated left-side labels;
- when a selected source PDF is already known to persistence, populate the
  associated stored metadata rather than making the user re-enter it;
- matching must use authoritative persisted document/relationship rules and
  existing byte/path identity behaviour where appropriate, not filename guesswork;
- do not create duplicate Exam/Booklet records when the user is actually opening
  an already-known source.

**Outcome:** incorrect Exam-level metadata is corrected once, and known source
PDFs reuse stored metadata safely.

### Slice 7 — Dedicated legacy Question split workflow

Add a deliberate conversion workflow for a legacy imported Question that was
stored as one Question but is actually multipart.

Representative case:

```text
Before
    Question 3

After
    SourceQuestion 3
        SharedQuestionContext (optional/required by source)
        Question 3a
        Question 3b
```

The workflow should collect/confirm at least:

- destination part codes;
- marks per part;
- classification per part;
- response type per part;
- whether a shared preamble is required and which stored/new context is used;
- Question region capture/recapture for each part.

Persistence rules:

- reuse the original Question row as one resulting part where safe;
- create additional part rows transactionally;
- create or reuse the intended SourceQuestion identity;
- reject duplicate Question natural identities;
- reject joining an incompatible existing multipart group;
- shared context must be consistent across the source group;
- do not guess how an existing single Question Answer should be divided between
  parts; preserve/transfer only where ownership is unambiguous and otherwise
  require explicit correction;
- failure/cancel must not leave a half-created multipart group;
- reload must preserve the resulting identities and relationships.

Regression coverage should include:

- split without shared context;
- split with newly captured shared context;
- split joining/reusing a compatible existing source identity where allowed;
- duplicate/incompatible destination rejection with rollback;
- Corpus Audit reconstruction;
- RevisionPresentationPlanner grouping after reload.

**Outcome:** real legacy source structure can be corrected explicitly rather than
worked around through several fragile manual operations.

**Implementation status — COMPLETE, 19 September 2026**

Implemented behaviour:

- Search Questions exposes a dedicated `Split Question...` correction workflow
  for eligible legacy single Questions.
- Split metadata is confirmed before region capture, including destination part
  codes, marks, classification, response type and shared-preamble strategy.
- Question regions for all resulting parts are staged in memory before any
  persistence occurs.
- the original Question row is retained as the explicitly nominated resulting
  part where safe;
- an existing Answer is retained only on the explicitly selected resulting part;
- a split may use no shared preamble, capture one new shared preamble, or reuse
  the compatible shared preamble of an existing SourceQuestion group;
- new SourceQuestion, Question and SharedQuestionContext relationships are
  persisted atomically;
- compatible existing SourceQuestion/shared-context identity can be reused;
- duplicate destination identities and incompatible existing groups are rejected;
- cancellation after staging a preamble and one or more parts leaves the
  original persisted Question unchanged;
- reload preserves resulting multipart and shared-context identities;
- Corpus Audit reconstruction and RevisionPresentationPlanner multipart grouping
  are covered after reload.

Verification completed:

- `LegacyQuestionSplitServiceTest` green;
- focused Corpus Audit reload regression green;
- focused RevisionPresentationPlanner reload/grouping regression green;
- Search split workflow regressions green for no preamble, newly captured
  preamble, reused existing preamble and explicit Answer ownership;
- split cancellation regression green;
- `QuestionEditingWorkflowTest` and `SharedContextWorkflowTest` green together;
- complete non-UI suite green with `./mvnw test`;
- complete UI suite green with `./mvnw -Pui-tests test`;
- `./mvnw spotless:check` green.

### Slice 8 — Search Questions and Corpus Audit usability

#### Search Questions

Add explicit search scope capable of showing:

```text
All Questions
Current syllabus / selected curriculum scope
```

All-bank display must not alter or imply current applicability. Existing
curriculum retrieval semantics remain authoritative for syllabus-scoped search.

Determine whether all-bank search also needs provider/year/booklet filters in
this sprint or whether explicit All versus curriculum scope is sufficient.

#### Corpus Audit

Sort displayed work by provider/authority, year, booklet and natural Question
code. Existing scope filters and summary totals must remain semantically
unchanged.

**Outcome:** bank-management views follow the source organisation a teacher
expects instead of persistence insertion order.

### Slice 9 — Regression, acceptance and documentation closeout

Run focused tests throughout, then complete:

```bash
./mvnw test
./mvnw -Pheadless-ui-tests test
./mvnw javadoc:javadoc
git diff --check
```

Manual acceptance should exercise at least:

- narrowed left workspace pane during Question and Answer capture;
- Question/Answer selection ownership and disabled action controls;
- natural Answer queue ordering on a real booklet;
- Imported Questions activation responsiveness;
- replacement of a stored shared preamble;
- correction of the known malformed legacy Exam metadata case;
- reopening a database-known exam PDF;
- splitting one real legacy Question into multipart parts with shared preamble;
- Search Questions All versus syllabus scope;
- Corpus Audit source ordering.

Update current status, roadmap and backlog only to describe behaviour actually
implemented and verified.

## Sprint acceptance criteria

Sprint 09 is complete when all of the following are true:

- Answer-pane button labels and essential Question-pane labels remain readable
  at the supported narrow workspace width;
- adding Answer regions does not create excessive blank pane growth;
- Answer status text no longer destabilises the action-button row;
- Answer capture and Corpus Audit use deterministic source/natural ordering;
- Add Region/Clear controls accurately reflect whether a compatible pending
  selection exists;
- Answer selection ownership disables incompatible Question-region actions;
- Question response type uses the agreed radio-button capture UI without
  changing persisted semantics;
- Edit Question Metadata and Import Exam labels remain readable;
- Imported Questions activation has been measured and avoidable delay removed;
- an existing shared preamble can be replaced safely and survives reload;
- Exam-level metadata can be corrected without rewriting linked Questions;
- a database-known exam PDF can reuse stored metadata safely;
- a legacy single Question can be split into multipart Questions transactionally
  and, where required, share one explicit preamble;
- Search Questions can deliberately show all-bank versus current-syllabus scope;
- Corpus Audit display ordering follows provider/authority, year, booklet and
  natural Question number;
- the redundant Capture New Questions menu action has been resolved deliberately;
- standard tests, headless UI tests, Javadoc and whitespace validation are clean.

## Explicitly outside Sprint 09

The following remain backlog/future work unless deliberately rescheduled:

- revision HTML Descriptor-versus-Subtopic grouping selection;
- multipart provenance decoration redesign;
- MCQ-before-written output grouping and optional response-type sections;
- automatic multi-page shared-preamble capture;
- optional MCQ explanation regions;
- multiple original classifications;
- Question-level mapped-applicability exceptions;
- explicit out-of-scope disposition;
- Exam Builder and printable assessment generation;
- clipboard/image-attachment Questions;
- packaging/deployment.

## Documentation relationship

Every requirement originating in the 17 September application-use review remains
listed in `docs/design/backlog.md`, including those intentionally deferred from
Sprint 09.

This sprint document defines the implementation scope. The backlog remains the
inventory of all known work.

## Branch

Sprint 09 implementation branch:

`feature/capture-workflow`