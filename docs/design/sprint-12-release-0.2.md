# Sprint 12 --- Exam Intake, Capture Workflow and Corpus Status

> **Status:** IN IMPLEMENTATION\
> **Branch:** `feature/sprint-12`\
> **Prepared:** 28 September 2026\
> **Target release:** 0.2
>
> This document is the active Sprint 12 design and working-status record.
> Durable architecture and forward-plan changes are also reflected in
> `docs/development-roadmap.md`. Deliberately deferred work remains in
> `docs/design/backlog.md`.

## 1. Sprint objective

Turn the post-0.1 application into a stronger real-corpus collection and
maintenance tool by making Exam structure explicit before capture, simplifying
the Question-capture workflow, adding optional MCQ explanation capture, and
evolving Corpus Audit into an operational Corpus Dashboard.

Sprint 12 is deliberately limited to four related slices:

1. Exam setup, assets, hashes and safe asset correction;
2. streamlined Question capture;
3. MCQ explanation regions;
4. Corpus Dashboard / Audit refinement.

By Sprint 12 closeout, the selected Subject becomes application-level context
and the Corpus Dashboard is intended to become the main application working
surface for that Subject. A Subject with little or no data must present useful
onboarding actions rather than an empty audit display.

Richer Question Search filtering is not Sprint 12 work and has returned to the
backlog.

Because Sprint 12 materially changes the user interface and capture workflow,
the intended release target is **0.2**, subject to normal release verification at
sprint closeout.

## 2. Starting state

Sprint 11 Release 0.1 is complete, verified and merged to protected `main`.

At Sprint 12 design start:

- Java/JavaFX/Maven application is operational;
- SQLite is the authoritative runtime datastore;
- latest supported schema version is 14;
- Question bodies may contain ordered PDF regions and clipboard images;
- Question/Answer PDF-region capture is operational;
- `ExamBooklet` already records booklet question format;
- `ExamBooklet` may already be assigned one `AnswerFile`;
- `SourceQuestion` already represents multipart identity such as Question 21
  behind parts `21a`, `21b`, `21c`;
- `SharedQuestionContext` already represents reusable source material;
- Corpus Audit already reports Question-level source, Answer, response-type and
  Shared Context problems;
- Search remains a retrieval/edit surface and is not expanded in this sprint.
- legacy Question metadata import already reads the previous ExamBuilder workbook
  format, identifies required Exam booklets, imports Question metadata and may
  create missing booklet/Answer assets after user selection;
- legacy workbook data may therefore supply much of the initial Exam catalogue
  for a Subject, but it does not prove the authoritative expected number of
  Questions in a booklet;
- Subject creation currently exists indirectly through curriculum creation but
  is not yet a first-class application-level workflow.
  
Current code and GitHub state remain authoritative during implementation.

## 3. Sprint-wide design principles

### 3.1 Exam structure is established before new Question capture

A blank `Start New Question Capture` action must not silently assume an active
Exam or source PDF.

New capture begins by selecting or setting up an Exam and selecting a real
Question booklet asset. Exam setup is therefore a first-class workflow rather
than incidental metadata attached to one selected PDF.

### 3.2 Expected counts are planning/audit metadata, not placeholder Questions

For each Question booklet the user records the expected number of **top-level
numbered Questions** visible in that booklet.

Multipart parts do not increase the expected count.

For example:

``` text
Question 21
    a
    b
    c

Question 22
    a
    b
    c
```

has:

``` text
Expected questions: 2
```

The application must not create fictitious Question rows to satisfy an expected
count.

### 3.3 Multipart semantics remain the existing simple convention

Existing detection based on codes such as:

``` text
21a
21b
21c
```

is sufficient.

No additional multipart parser is required for forms such as `21(a)`, `21.1` or
other variants.

The existing `SourceQuestion` and Shared Context semantics are retained and
refined rather than redesigned.

### 3.4 Completeness is declared by the user

Exam completeness is not inferred automatically from expected counts, assets or
Question audit findings.

An Exam has a user-controlled lifecycle:

``` text
ACTIVE
    ↓
Mark Exam Complete
    ↓
COMPLETE
    ↓
Reactivate Exam
    ↓
ACTIVE
```

Audit findings may warn that a completed Exam has outstanding or inconsistent
data, but the application does not silently reverse the user's completion
decision.

### 3.5 Completion locks structure, not ordinary content correction

When an Exam is `COMPLETE`, structural changes require explicit reactivation.

Structural changes include:

- adding or removing Question booklets;
- changing a booklet Question PDF;
- changing expected top-level Question count;
- changing booklet format;
- changing booklet identity/name where structure is affected;
- adding, removing or reassigning Answer/marking PDFs;
- changing Exam identity such as Subject/provider/year/assessment;
- adding or deleting Questions belonging to the Exam;
- changing a Question code where that changes source-question structure.

The following remain available without reactivating the Exam because they
correct or enrich captured content rather than redefining Exam structure:

- editing Question regions/content;
- adding, removing or reordering Question content parts;
- editing marks;
- editing curriculum classification;
- correcting MCQ answer letters;
- editing written Answer regions;
- adding/editing MCQ explanation regions;
- resolving or recapturing Shared Context;
- correcting response type where necessary;
- editing clipboard/image Question content;
- correcting metadata about whether an existing Answer/marking PDF contains MCQ
  explanations.

### 3.6 Source-dependent data is invalidated, never silently remapped

Question, Shared Context and Answer regions store coordinates that are meaningful
only against the PDF from which they were captured.

Replacing a wrong PDF must never silently reinterpret old page/rectangle
coordinates against the replacement document.

Replacement operations must identify affected source-dependent capture, warn the
user, require confirmation where data will be lost, and invalidate the affected
regions before assigning the replacement asset.

### 3.7 Subject is application-level context

Subject selection is not merely Question-capture state.

By Sprint 12 closeout the selected Subject is application-level context for:

- Corpus Dashboard;
- Exam setup and asset management;
- Question and Answer capture;
- curriculum workflows;
- Search and other Subject-scoped operations where appropriate.

The application should present the selected Subject independently from Question
classification.

A first-class Subject-creation workflow is available from the application-level
Subject row through a compact `+` action beside the Subject selector.

Creating a Subject persists the Subject independently, refreshes the authoritative
Subject selector and immediately activates the new Subject as the Working Subject.
It does not require an Exam or curriculum to be created at the same time.
Changing Subject changes the corpus and work being displayed. Existing
safeguards that prevent Subject changes while unsaved capture work is pending
must remain.

Working Subject transitions are application-coordinated and asynchronous. Once
the existing capture-state guard accepts a Subject change, the new Subject
becomes authoritative immediately and stale Subject-dependent presentation is
cleared before replacement persistence data is loaded.

One application-level refresh generation coordinates the transition. Curriculum
syllabus/root-Unit data is loaded away from the JavaFX application thread, and
one immutable Question-corpus snapshot is shared by Question Capture and Answer
Capture. When Exam/Assets is visible, its persisted Exam/asset snapshot is loaded
in parallel under the same refresh generation.

All resulting JavaFX control updates are published on the JavaFX application
thread. Results belonging to an older Subject generation are discarded, so a
slower earlier refresh cannot overwrite a later Subject selection. Clearing
Working Subject requires no replacement persistence load. A current-generation
persistence failure leaves the accepted Subject authoritative while dependent
state remains cleared and the failure is surfaced to the user.

An accepted Working Subject change also clears the PDF workspace immediately so
an Exam, Answer or standalone viewer document from the previous Subject cannot
remain visible as current context. If a booklet-activation workflow has already
opened the replacement Exam PDF for the newly accepted Subject, that new
document is retained rather than cleared.

Curriculum Subject snapshots include syllabus versions and the root Units for
each available version. Switching between current and historical syllabus
versions after the Subject snapshot has loaded therefore does not return to
SQLite for root-Unit lookup on the JavaFX thread.

### 3.8 Legacy Question metadata intake uses Exam/Assets

Legacy Question import is an intake source, not a second Exam-management model.

The implemented Sprint 12 workflow begins from the main-window Exam/Assets
workspace. It inherits the authoritative Working Subject and does not present
another Subject selector.

The intake dialog asks only for:

- the historical syllabus version represented by the workbook;
- the legacy Excel workbook.

Workbook preflight validates classification data and determines every required
provider/year/Question-booklet identity before Question metadata is imported.

Existing uniquely matching Exams and Question booklets are reused. The workbook
does not recreate them and does not overwrite authoritative Exam/Assets planning
metadata.

If required structure is missing, Exam/Assets displays the unresolved
provider/year/booklet requirements. The user then creates or corrects the
necessary Exam and Question-booklet structure through the ordinary Exam/Assets
controls.

The workbook does not invent:

- Assessment name;
- Question booklet PDF;
- Answer/marking PDF;
- booklet format;
- Expected Questions;
- AnswerFile assignment;
- Exam completion state.

`Recheck and Import` reruns authoritative preflight after the user has completed
the required Exam/Assets work. Question metadata is imported only when every
required booklet resolves uniquely. Ambiguous matches are rejected rather than
guessed.

The existing atomic `LegacyQuestionMetadataImporter` remains responsible for
Question metadata semantics, including:

- Question codes and marks;
- historical classification;
- response-type evidence;
- multipart `SourceQuestion` identity;
- legacy Shared Context/preamble evidence;
- supplied MCQ Answer letters;
- conflict detection and idempotent re-import.

Question and Answer source assets are managed only through Exam/Assets. The
former legacy booklet-import and Answer-PDF dialogs are retired.

After a successful atomic metadata import, one Question corpus snapshot is
loaded off the JavaFX application thread and published to both Question and
Answer capture. Imported/incomplete work therefore becomes available without
performing duplicate corpus reads or changing the Working Subject.

A pending legacy preflight belongs to exactly one Working Subject. An accepted
Working Subject change cancels that pending intake so stale requirements cannot
survive into another Subject.

The former standalone Exam-menu legacy-import action is removed. Exam/Assets is
the single application entry point for legacy Question metadata intake.

Regression coverage verifies:

- Exam/Assets-created assets are reused without changing Expected Questions,
  booklet format or ACTIVE Exam state;
- missing requirements remain visible in Exam/Assets until resolved, rechecked
  or cancelled;
- Working Subject changes clear pending requirements;
- the import dialog has no duplicate Subject selector;
- the post-import Question corpus is loaded once off JavaFX and shared by
  Question and Answer capture.

## 4. Slice 1 --- Exam setup, assets, hashes and safe correction

### 4.1 Purpose

Establish the complete Exam-level source context required before Question
capture, including expected Question structure, managed source assets and safe
correction of incorrectly selected assets.

### 4.2 Main-window Exam/Assets workspace

Exam management is performed in the main application window rather than through
the former modal Exam Setup / Add Exam workflow.

Exam identity correction may relocate managed Question and Answer PDFs when
Provider or Year changes the authoritative storage path.

After a successful relocation and persistence commit, obsolete empty source
directories are pruned upwards within the configured managed PDF root. Pruning
stops at the first non-empty directory and never removes the managed root.
Symbolic-link components are not traversed during cleanup.

Directory pruning is filesystem housekeeping rather than authoritative Exam
state. Failure to remove an obsolete empty directory therefore does not convert
an otherwise successfully committed Exam correction into a failed correction.

`Change Exam` in Capture mode and `Exam -> Exam / Assets...` both switch the
left-hand workspace to Exam/Assets while retaining the authoritative Working
Subject above it and reusing the existing PDF workspace on the right.

The workspace supports:

- selecting an existing Exam for the Working Subject;
- creating a new Exam without another Subject selector;
- correcting Provider, Year and Assessment;
- reviewing Question booklets;
- reviewing Answer/marking PDFs;
- adding Question and Answer assets;
- inspecting Question and Answer PDFs in the shared read-only viewer;
- recording Question-booklet format and Expected Questions;
- assigning zero or one AnswerFile to each Question booklet;
- allowing one AnswerFile to serve several Question booklets;
- selecting a Question booklet independently of the current capture booklet;
- preflighting and importing legacy Question metadata for the Working Subject,
  with unresolved Exam/booklet requirements completed through the ordinary
  Exam/Assets controls;
- explicitly activating the selected booklet through `Use Selected Booklet for Capture`.

New Exam creation persists Provider, Year and Assessment first. Question and
Answer assets are then added to that authoritative persisted Exam.

A new Question booklet imports its source PDF into managed storage, records the
managed source-document hash, and opens the selected source immediately in
read-only viewer mode. This allows Expected Questions to be entered while the
booklet is being inspected.

Saving the booklet replaces the temporary source preview with inspection of the
authoritative managed PDF. Inspection itself does not change the active capture
booklet.

`Use Selected Booklet for Capture` is the explicit transition from Exam
management to Question Capture. It activates the persisted booklet, opens its
managed Question PDF, refreshes capture state and restores the Question Capture
workspace.

The former modal `ExamSetupDialog` / `ExamImportDialog` path is no longer part
of normal application workflow.

### 4.3 Question booklet setup
For each Question booklet the application records:
- booklet name;
- booklet format:
  - MULTIPLE_CHOICE;
  - WRITTEN_RESPONSE;
  - MIXED;
- expected number of top-level Questions;
- Question booklet PDF;
- managed source-document hash.
The expected count is the number of numbered top-level Questions, not the
number of captured Question parts.
For example:
21a
21b
21c

represents one top-level Question, Question 21.
Expected Question count remains nullable until the source booklet has been
reviewed. Legacy Question data is not used to infer the authoritative expected
count.
Existing and legacy-imported booklets can therefore be inspected
retrospectively and have their expected counts recorded later.
Changing the expected Question count is structural Exam planning. A COMPLETE
Exam must be reactivated before that value can be changed.

### 4.4 Preview-only booklet inspection

Question-booklet inspection uses the existing shared PDF workspace in `VIEWER`
mode.

Inspection provides:

- normal page navigation;
- review of the complete Question booklet;
- no Question-region selection;
- no Question-content creation;
- no change to the active capture booklet merely because another booklet is
  being inspected.

For an existing persisted booklet, Exam/Assets opens the authoritative managed
PDF belonging to that `ExamBooklet`.

When adding a new Question booklet, the user-selected source PDF is previewed
immediately before persistence so the booklet can be visually reviewed while
Question format and Expected Questions are entered.

After Save, the managed persisted copy becomes authoritative and remains
available for inspection through the same shared viewer.

Expected Questions is maintained directly as Question-booklet metadata in
Exam/Assets. It records the number of numbered top-level Questions, so multipart
parts such as `21a`, `21b` and `21c` represent one top-level Question.

Expected Questions may remain unknown until the source booklet has been
reviewed. Legacy Question rows must not be used to infer that authoritative
count.

Inspection remains separate from capture activation. Only `Use Selected Booklet
for Capture` changes the application's active capture booklet.

### 4.5 Expected-but-not-yet-available assets

Planning/expectation data must not require fictitious authoritative domain
objects.

If the user knows that an Exam should contain a Question booklet but does not
yet have its PDF, the application may retain planning/expectation information
without creating an authoritative `ExamBooklet` whose required source document
does not exist.

The implementation design must preserve existing domain invariants unless there
is a strong reason to change them.

Implementation status as at 28 September 2026:

Schema version 18 adds Exam-level planning expectations for source assets that
may not yet be available:

- `exams.expected_question_booklet_count`;
- `exams.expected_answer_file_count`.

These values are deliberately separate from authoritative asset rows.

The application does not create placeholder `ExamBooklet`, `AnswerFile` or
`SourceDocument` records merely to represent an expected future asset.

Expected counts are nullable because an Exam may not yet have been reviewed
sufficiently to establish them. When supplied:

- expected Question booklet count must be positive;
- expected Answer/marking file count may be zero.

Available asset counts are derived independently from actual persisted
`ExamBooklet` and `AnswerFile` rows.

This allows later Exam/Assets and Corpus Dashboard workflows to distinguish, for
example:

```text
Question booklets: 1 available / 2 expected
Answer files:      0 available / 1 expected
```

without treating unavailable source material as though it already exists.
Changing these expectations is structural Exam planning and therefore requires
an ACTIVE Exam.

### 4.6 Managed source-document hashes

Managed Question and Answer/marking PDFs gain a persisted cryptographic content
hash, using SHA-256 unless implementation evidence requires another choice.

Hashes support:

- duplicate detection independent of filename/path;
- safer Exam intake;
- correction/reconciliation;
- later distributed work-package import.

A matching hash is detection evidence, not automatic permission to overwrite or
merge records.

Implementation status as at 28 September 2026:

- schema version 16 added nullable `source_documents.content_sha256`;
- managed document identity uses lower-case SHA-256;
- existing migrated source documents may legitimately retain `NULL` until their
  bytes are inspected;
- the hash is deliberately not unique because duplicate content must be
  representable and surfaced to the application for review;
- fresh Question and Answer PDF intake records the hash of the final managed
  copy;
- legacy Question and Answer PDF intake uses the same hashing path;
- existing source-document rows with an unknown hash may be safely back-filled;
- a conflicting hash for the same persisted source-document identity is rejected;
- known Question documents may be recognised by content independently of
  filename or external path;
- ambiguous duplicate matches are reported rather than resolved arbitrarily.

Hashing is also used by the Question-PDF replacement workflow described below.
Answer-PDF replacement remains part of the subsequent Answer-asset correction
work.

### 4.7 Answer/marking assets

The Exam/Assets workflow must show the Exam's Answer/marking PDFs and their
booklet assignments.

One `AnswerFile` may continue to serve multiple Question booklets where that is
the real Exam structure.

An Answer/marking asset may also be marked:

``` text
Contains MCQ explanations
```

This describes the contents of the AnswerFile and enables Slice 3 explanation
capture. It does not itself make explanations mandatory.

### 4.8 Safe replacement of a wrong Question booklet PDF

Replacing an incorrectly selected Question booklet PDF must inspect the
booklet's dependent PDF-derived capture.

If no dependent PDF-derived content exists, replacement may proceed directly.

If dependent capture exists:

1. report the affected data;
2. warn that stored coordinates belong to the old PDF;
3. require explicit confirmation;
4. invalidate/delete the affected PDF-backed Question content;
5. invalidate/delete affected Shared Context region material;
6. register/assign the replacement managed document and hash;
7. return affected Questions to the appropriate capture workflow.

Preserve data that does not depend on the replaced PDF where semantically safe,
including:

- Question identity/code;
- marks;
- response type;
- curriculum classification;
- independent MCQ answer letters;
- clipboard/image Question content.

Shared Context persistence must be handled deliberately because a persisted
`SharedQuestionContext` currently requires region content. A replacement
transaction must not leave structurally invalid empty context objects.

Implementation status as at 28 September 2026:

The Question-booklet replacement workflow is implemented through a dedicated
replacement service and the current Exam workflow.

Before replacement, the application reports the affected:

- Questions;
- Question PDF regions;
- Shared Contexts;
- independent stored image parts that will be preserved.

Replacement requires explicit confirmation when source-dependent capture exists.

The replacement transaction:

1. verifies that the Exam is still `ACTIVE`;
2. verifies the currently managed bytes against their persisted SHA-256 identity;
3. rejects replacement content already owned by another managed source document;
4. preserves the existing `ExamBooklet` and `SourceDocument` identities;
5. replaces the managed PDF bytes and updates their SHA-256 hash;
6. removes Question PDF regions whose coordinates belong to the old PDF;
7. removes obsolete Shared Context region/context data;
8. preserves Question identity, code, marks, classification, response type,
   Answers and clipboard/image content;
9. marks affected Questions as requiring source recapture;
10. restores the previous managed bytes if persistence fails after filesystem
    replacement.

Schema version 17 introduced `questions.source_capture_required` so a mixed
Question whose PDF regions were invalidated remains visible in the capture work
queue even when independent image content survives.

A normal image-only Question does not acquire this flag and therefore is not
incorrectly classified as incomplete.

For an affected Question, source recapture:

- starts with its preserved image content still present;
- requires at least one newly captured PDF region;
- clears `source_capture_required` only after the recaptured Question is
  successfully persisted;
- preserves any associated Answer.

Selecting a byte-identical replacement is non-destructive and may back-fill an
older missing source-document hash without invalidating existing capture.

The current user-facing action is `Replace Active Question PDF...` on the Exam
menu. The replacement service is UI-independent so the same operation can be
moved into the Exam/Assets workspace without changing
its persistence semantics.

### 4.9 Safe replacement of a wrong Answer/marking PDF

The correction belongs at booklet/AnswerFile level rather than inside ordinary
`Edit Answer`.

If no persisted Answer regions depend on the old AnswerFile, replacement may
proceed directly.

If Answer regions depend on the old file:

1. identify the affected Questions;
2. warn that their stored regions belong to the old PDF;
3. require explicit confirmation;
4. remove/invalidate those Answer regions;
5. assign the correct AnswerFile;
6. preserve independent MCQ A/B/C/D answers;
7. return affected written-response Questions to Answer capture when their
   required Answer content has been removed.

The existing repository invariant preventing assignment of a conflicting
AnswerFile while old regions still refer to another file must remain. The
correction workflow must work above that invariant rather than bypass it.

Implementation status as at 29 September 2026:

Answer-PDF correction is implemented as booklet-level AnswerFile reassignment.

An existing AnswerFile is never overwritten in place because the same file may
legitimately serve multiple booklets. Correcting one booklet therefore leaves
other booklet assignments unchanged.

Before reassignment the workflow reports:

- affected Questions;
- Answer regions whose coordinates belong to the old PDF;
- Answers with independent textual content that will be preserved;
- region-only Answers that will become unanswered.

Confirmed reassignment:

1. requires the Exam to be `ACTIVE`;
2. verifies that persisted Answer regions agree with the booklet's current
   AnswerFile assignment;
3. removes only Answer regions belonging to the old AnswerFile and corrected
   booklet;
4. preserves independent Answer text, including authoritative MCQ A/B/C/D
   letters;
5. removes Answer rows that contained only invalidated regions, causing those
   written-response Questions to return naturally to the Answer-capture queue;
6. reassigns the booklet to the replacement AnswerFile atomically;
7. retires the old AnswerFile when no booklet or Answer region still refers to
   it;
8. retires the old SourceDocument when no Question booklet or remaining
   AnswerFile refers to it.

When the old SourceDocument is retired, its obsolete managed PDF is also removed
from the managed Exam hierarchy.

A shared old AnswerFile is retained together with its SourceDocument and managed
PDF while another booklet still uses it.

Newly selected replacement PDFs are copied into the managed Exam hierarchy and
hashed before registration. If the normal managed filename already contains a
different file, the replacement is first stored under a hash-qualified filename
rather than overwriting bytes that may still be referenced.

When the selected bytes already identify one AnswerFile belonging to the same
Exam, that existing managed AnswerFile is reused. Ambiguous or conflicting
content matches are rejected.

Selecting byte-identical content is non-destructive. Existing Answer regions
remain valid and an older missing SourceDocument hash may be back-filled.

The Answer-capture work queue is rebuilt from persistence after correction so a
written-response Question whose region-only Answer was removed becomes
immediately available for recapture.

Repository regression tests verify both cleanup cases:

- an orphaned old AnswerFile, SourceDocument and managed PDF are removed;
- a shared old AnswerFile, SourceDocument and managed PDF remain intact.

### 4.10 Exam completion control

An active Exam can be marked complete by the user.

Before confirmation the application may show advisory information such as:

- expected versus encountered top-level Questions;
- missing assets;
- Question/Answer audit findings.

Warnings do not prevent completion unless a genuine persistence/data-integrity
constraint would be violated.

A completed Exam is structurally locked until `Reactivate Exam` is selected.

Implementation status as at 28 September 2026:

Exam lifecycle is persisted as `ACTIVE` or `COMPLETE`.

The Exam menu provides:

- `Mark Active Exam Complete...`;
- `Reactivate Active Exam`.

Marking an Exam complete requires explicit confirmation. The active in-memory
Exam is refreshed immediately after the persisted state changes.

Repository-level structural locking uses persisted Exam state rather than
trusting potentially stale domain objects.

A `COMPLETE` Exam rejects structural operations including:

- adding another Question booklet;
- changing booklet format or expected Question counts;
- changing Exam-level expected asset counts;
- correcting Exam identity metadata;
- replacing Question or Answer PDFs;
- adding new Questions;
- changing Question code/structural identity;
- registering a new AnswerFile;
- establishing or changing a booklet-to-AnswerFile assignment.

Existing non-structural capture and correction remain available while complete,
including:

- Question marks, regions and classification;
- existing Question content correction;
- Answer text and Answer regions using the already assigned AnswerFile;
- Shared Context correction and recapture.

Reusing an already registered AnswerFile or reasserting an existing booklet
assignment does not itself alter Exam structure and remains permitted.

Explicit reactivation restores structural editing.

Repository and TestFX regression tests cover completion persistence, structural
rejection, permitted non-structural correction and reactivation.

## 5. Slice 2 --- Streamlined Question capture

### 5.1 Purpose

Make sustained Question capture clearer and more efficient after Slice 1 has
established the active Exam and Question booklet.

### 5.2 Workspace structure

Subject is application-level context established before entering Question
capture.

The capture workflow inherits the selected Subject and clearly identifies the
active Exam and Question booklet.

The implemented layout is conceptually:

```text
WORKING SUBJECT
    Subject: Chemistry

ACTIVE EXAM / BOOKLET
    QCAA 2024 External Assessment — Paper 1 MCQ [ACTIVE]
    [Change Exam]

    +--------------------------------------+
    | Classification                       |
    |     [classification controls]         |
    |                                      |
    | Question                             |
    |     [Question capture controls]       |
    |                                      |
    | Answer                               |
    |     [Answer capture controls]         |
    +--------------------------------------+
```

`WORKING SUBJECT` is application-level context and is visually separate from
Question classification.

`ACTIVE EXAM / BOOKLET` is also separate from the Question-capture panes. It
shows the currently active persisted Exam and booklet, including the Exam
lifecycle state.

`Change Exam` switches the left side of the main window directly to the
Exam/Assets workspace. It does not open the retired modal Exam Setup workflow.

`Classification`, `Question` and `Answer` remain separate bordered panes. They
are visually grouped inside a larger, unobtrusive bordered container without
adding another visible heading.

The Classification controls continue to use the existing
`CurriculumSelectorPane` and `CurriculumSelectionModel`; restructuring the
visible layout does not create another curriculum-selection state.

Changing Working Subject continues to change application context and therefore
the available Exam and capture work. If the previous active Exam belongs to a
different Subject, its visible active-Exam context is invalidated.

The active Exam/booklet display is refreshed when:

- a persisted booklet becomes active;
- Working Subject changes;
- Exam identity metadata changes;
- the Exam is marked `COMPLETE`;
- the Exam is reactivated.

The main application window now opens at 900 px high rather than 840 px to
accommodate the additional Working Subject and active Exam/booklet context
without forcing unnecessary initial vertical scrolling.

The workspace restructuring changes presentation and navigation only. It does
not introduce parallel Exam, booklet, curriculum or capture state.

### 5.3 Explicit capture entry

Question capture does not begin merely because an Exam booklet has been opened.

After a booklet becomes active:

- Working Subject remains available as application-level context;
- Classification is inactive;
- ordinary Question controls are inactive;
- no new-Question capture mode is selected;
- dragging on the Exam PDF cannot create a Question-region selection.

`Start New Question Capture` explicitly enters ordinary new-Question capture. It enables Classification and Question work and allows the Exam PDF to create Question selections.

Imported-Question capture, Question editing, legacy split capture and Shared Context correction are separate explicit workflows. Entering one of those workflows activates only the controls required for that work.

Completing or cancelling an edit or correction returns the Question workspace to its idle state. It does not silently start a new Question.

A successful ordinary new-Question save is deliberately different. The saved Question is cleared from the transient controls, but new-Question capture remains active so the teacher can continue directly with the next Question.

Changing the active booklet clears transient Question state and returns capture to idle, requiring `Start New Question Capture` again for the newly selected booklet.

### 5.4 Question content controls

Question content creation uses only:

``` text
Add Region
Add From Clipboard
```

There is no separate `Discard Selection` button.

Clicking elsewhere in the PDF already abandons an unaccepted selection and must
continue to clear the corresponding logical selection.

There is no global `Clear All Content` button.

Accepted Question content parts remain individually removable and reorderable.
If real use later demonstrates a need for bulk clearing it can be reconsidered.

### 5.5 Multipart and Shared Context behaviour

Multipart detection remains based on Question codes such as `21a`, `21b` and
`21c`.

Entering a multipart code derives the common persisted source identity. For
example:

``` text
Question code: 22a
SourceQuestion: 22
```

The existing `SourceQuestion` / `SharedQuestionContext` model remains
authoritative.

The first captured part establishes the persisted Shared Context decision for
the source Question:

- `PRESENT` when Shared Context is captured;
- `NONE` when the Question is explicitly saved without Shared Context.

Later parts deriving the same `SourceQuestion` reuse that persisted decision.

For `PRESENT`:

- the existing Shared Context is reused automatically;
- the Shared Context question is not presented again;
- no duplicate `SharedQuestionContext` is created.

For `NONE`:

- the absence of Shared Context is reused automatically;
- the Shared Context question is not presented again.

Sequential capture therefore depends on persisted source-Question state rather
than temporary UI state from the preceding capture.

The same behaviour also applies when multipart Questions are revisited later;
the relationship is not dependent on capturing all parts in one session.

Implementation status as at 29 September 2026:

- sequential multipart capture reuses persisted `PRESENT` decisions;
- sequential multipart capture reuses persisted `NONE` decisions;
- regression coverage verifies that later parts are not asked the same Shared
  Context question again;
- regression coverage verifies reuse of the same persisted source and Shared
  Context identities;
- existing `SourceQuestion` semantics were retained without introducing a second
  multipart workflow model.

### 5.6 Imported/incomplete Question capture

Imported/incomplete Question capture is an operational work queue rather than a
permanently visible capture mode.

The existing Question-state rules determine whether a Question belongs in this
queue. A Question is included when it has any of the following outstanding work:

- no Question body content;
- Question PDF replacement has marked source capture as required;
- Shared Context remains unresolved.

The queue is filtered by the current Working Subject. Filtering changes only
workspace visibility; it does not alter or delete Questions belonging to another
Subject.

When at least one relevant Question exists, the Question pane exposes:

``` text
Complete Imported Question
```

The Question selector itself remains hidden until that action is chosen.

When no relevant imported/incomplete work exists, both the action and its
selector are absent from the normal workspace.

If the final Question in the imported/incomplete queue is completed, the
workflow closes automatically and returns the Question workspace to its explicit
idle state. It does not silently begin ordinary new-Question capture.

Changing Working Subject immediately recalculates this availability from the
same persisted Question state. The action therefore appears or disappears as
the filtered operational queue changes.

This conditional presentation does not introduce a second definition of
Question completeness. It reuses the existing imported/incomplete queue rules.

## 6. Slice 3 --- MCQ explanation regions

### 6.1 Purpose

Capture optional explanatory/working material from marking PDFs for
multiple-choice Questions, including retrofitting existing corpus Answers.

### 6.2 Answer semantics

The stored A/B/C/D choice remains the authoritative MCQ Answer.

One or more Answer PDF regions may additionally be stored as supplementary
explanation material.

An MCQ remains complete when it has a valid A/B/C/D answer even if it has no
explanation regions.

Explanation absence must not block ordinary capture, audit completeness or
output.

### 6.3 AnswerFile capability flag

An existing or newly registered Answer/marking PDF may be marked:

``` text
Contains MCQ explanations
```

The flag belongs to the AnswerFile because it describes that particular source
document.

Changing this descriptive flag on an existing asset is permitted even when the
Exam is complete because it does not change Exam structure.

### 6.4 New Answer capture

When the selected Question is MCQ and its assigned AnswerFile is marked as
containing explanations:

- the ordinary A/B/C/D controls remain;
- explanation-region capture controls become available;
- one or more explanation regions may be added;
- Save persists the letter plus optional regions in the same `Answer`.

When the AnswerFile is not marked as containing explanations, MCQ capture stays
focused on the A/B/C/D choice.

### 6.5 Retrofit existing MCQ Answers

Existing MCQ Answers already stored as A/B/C/D must be eligible for later
explanation capture.

Provide an explicit workflow such as:

``` text
Capture MCQ Explanations
```

The workflow may present MCQs associated with an AnswerFile marked as containing
explanations, including already-answered Questions.

Adding explanation regions updates the existing Answer and must not alter its
stored A/B/C/D choice.

### 6.6 No inferred explanation completeness

The AnswerFile-level flag means the document contains explanation material
somewhere; it does not prove that every MCQ has an explanation.

Sprint 12 does not require explanation regions for every MCQ and does not infer
missing explanations as ordinary Question incompleteness.

A later audit view may report captured explanation coverage separately.

### 6.7 Implementation status

Slice 3 is implemented.

The existing `Answer` representation is reused rather than introducing a
separate explanation entity. An MCQ Answer may contain:

```text
answerText = A/B/C/D
regions    = zero or more optional explanation regions
```

`AnswerFile.contains_answer_explanations` is persisted asset metadata and is
editable through Exam/Assets. Changing that descriptive flag remains permitted
for a COMPLETE Exam because it does not alter Exam structure.

During ordinary MCQ Answer capture, explanation-region controls are available
only when the Question booklet's assigned `AnswerFile` is marked as containing
explanations. The A-D choice remains sufficient to save a complete MCQ Answer;
explanation regions remain optional.

The explicit `Capture MCQ Explanations` workflow provides retrofit capture for
already-answered MCQs. The user first activates a Question booklet through
Exam/Assets with `Use Selected Booklet for Capture`. Retrofit candidate discovery
is then restricted to answered MCQs belonging to that active booklet whose
assigned `AnswerFile` is marked as containing explanations.

Candidate discovery and AnswerFile lookup run away from the JavaFX application
thread. Subject and active-booklet context are checked again before asynchronous
results are published so stale work cannot enter a later capture context.

Retrofit capture reuses the ordinary persisted Answer-edit path. Saving
explanation regions preserves both the existing A-D choice and persisted Answer
identity.

Within one retrofit session, a successfully updated MCQ is removed from the
candidate list and the next remaining candidate is opened automatically.
Cancelling an edit does not remove its candidate. This queue behaviour is
session workflow state only; it does not create persisted per-MCQ explanation
completeness.

Existing explanation regions do not otherwise imply that a Question is complete
or incomplete for explanation purposes. The AnswerFile flag describes
source-document capability and does not prove that every MCQ has an explanation.

Ordinary corpus completeness remains unchanged:

```text
valid A/B/C/D
    -> MCQ Answer complete

valid A/B/C/D + no explanation regions
    -> MCQ Answer complete

explanation regions + no valid A/B/C/D
    -> MCQ Answer incomplete
```

No new missing-explanation audit problem is introduced.

## 7. Slice 4 --- Corpus Dashboard / Audit refinement

### 7.1 Purpose

Evolve the existing Corpus Audit into an operational Dashboard that answers:

``` text
What work remains?
Why does it need attention?
Take me to the workflow that can resolve it.
```

The Dashboard must reuse existing domain/repository truth rather than create
parallel completeness rules.

### 7.2 Distinguish declared Exam state from calculated audit findings

The Dashboard must treat these as separate dimensions:

``` text
Exam state:
    ACTIVE
    COMPLETE

Audit status:
    clear
    findings requiring attention
```

A completed Exam may still have audit findings. That is not a contradiction.

Structural correction of a completed Exam requires explicit reactivation.
Non-structural content correction remains available directly.

### 7.3 Exam-level view

The Dashboard should report Exam-level information including:

- declared Exam state;
- expected/present Question assets;
- expected/present Answer/marking assets;
- booklet states;
- structural warnings;
- aggregate Question/Answer work.

### 7.4 Booklet-level view

Booklet reporting should include useful information such as:

- booklet format;
- Question PDF availability;
- assigned AnswerFile;
- expected top-level Question count;
- encountered top-level/source Question count;
- captured Question-part count;
- Question content problems;
- Answer problems;
- unresolved response types;
- unresolved Shared Context.

Expected count is compared with top-level/source Questions, not with the number
of stored multipart Question records.

A mismatch is an advisory finding requiring investigation. The application must
not infer which side is wrong and must not automatically reverse a user's
`COMPLETE` declaration.

### 7.5 Question-level audit

Retain the useful existing Question-level concepts:

- missing Question content;
- missing Answer;
- unresolved Shared Context;
- unknown response type.

The current audit's PDF-only test for Question source content must be corrected.
An image-only Question created through clipboard capture is valid authoritative
Question content.

The problem terminology should therefore move from "missing Question source
region" toward "missing Question content" where appropriate.

Implementation now treats the authoritative ordered Question content-parts list
as the body-content test rather than using the legacy PDF-region projection.
An image-only Question therefore counts as having Question content. A Question
whose PDF source was explicitly invalidated still reports missing Question
content until the required source recapture is completed, even when independent
clipboard-image content remains.

### 7.6 Operational UI

The current concatenated work-item `ListView` should evolve into a useful table.

The Dashboard should support:

- Subject/provider/year/booklet scoping where useful;
- Exam/booklet selection;
- clear summary counts;
- clickable summary counts that filter the relevant work set;
- deterministic sorting;
- direct routing to correction/capture workflows;
- refresh after corrective work;
- retention of bulk response-type correction.

The Dashboard is an operational work queue, not a graph-heavy analytics screen.

Implementation status (Sprint 12.4):

The former concatenated Corpus Audit ListView has been replaced by the
operational Corpus Dashboard. The Dashboard is scoped to the authoritative
Working Subject and presents:

- Provider, year and declared Exam-state filters;
- Subject-scope Question completeness summaries;
- clickable Question-work summary filters;
- an Exam table with declared state, booklet/file counts and Question work;
- a selected-Exam summary;
- booklet Question-PDF, Answer-file, expected/found and Question-work status;
- advisory structural warnings without changing declared Exam state;
- an Exam/booklet-scoped Question work table;
- separate MCQ explanation coverage; and
- retained bulk UNKNOWN response-type correction.

The live Questions menu action now opens the Dashboard. Direct correction
routing from Dashboard rows and structural findings remains the responsibility
of the following correction-routing slice.

### 7.7 Correction routing

Use existing or Sprint 12 workflows rather than creating parallel editors.

Examples:

``` text
Missing/wrong Exam asset
    -> Exam/Assets

Missing Question content
    -> Question Capture

Missing Answer
    -> Answer Capture

Unknown response type
    -> metadata correction / existing bulk correction

Unresolved Shared Context
    -> Question / Shared Context workflow
```

Implementation status (Sprint 12.4):

Manual live-data validation identified that the original generic
`Resolve Selected` action obscured the distinction between booklet-level and
Question-level work. It has therefore been replaced by explicit operational
actions:

- `Capture Questions` starts new Question capture for the selected ACTIVE
  booklet when its Question PDF is available;
- `Capture Answers` starts at the first ready missing Answer in the selected
  booklet;
- `Complete Selected Question` handles missing Question content or unresolved
  Shared Context for one explicitly selected existing Question;
- response-type correction remains explicit through the existing Multiple
  choice / Written controls; and
- `Manage Exam / Assets` remains the structural correction route.

Changing Exam, booklet or Question-work scope clears the Question-table
selection before rebuilding the work queue. A selected row index therefore
cannot silently transfer to a different Question after a scope change.

An expected-versus-encountered Question-count mismatch is advisory. If the Exam
is COMPLETE, it must first be reactivated through Manage Exam / Assets before
new Questions can be captured. If the expected count itself is wrong, that
planning value is corrected through Manage Exam / Assets.

Dashboard-launched Question and Answer capture expose one common
`Return to Corpus Dashboard` action in the main Capture workspace. Returning is
blocked while unsaved Question, Shared Context or Answer capture work is staged.
The Dashboard reloads authoritative persistence state when it is reopened.

### 7.8 Curriculum mapping reporting

Curriculum mapping review coverage may be surfaced through the existing
`CurriculumMappingCoverageService`.

Mapping review remains a separate reporting dimension and does not make an
otherwise complete Question or Exam automatically incomplete.

### 7.9 MCQ explanation reporting

Explanation coverage may be reported separately from ordinary Answer
completeness.

An MCQ with a valid A/B/C/D answer remains complete even if no explanation
region has been captured.

### 7.10 Dashboard as the Subject home screen

By Sprint 12 closeout the intended application flow is:

``` text
Application start
    ↓
Select Subject
    ↓
Corpus Dashboard
```

The Dashboard becomes the main working surface for the selected Subject rather
than remaining only a modal audit dialog.
The Dashboard must preserve access to the application's specialised workflows,
including Exam setup, Question capture, Answer capture, Search, curriculum
management and output.
A Subject with little or no corpus data must not display only an empty work
queue.
Useful empty-state actions include at minimum:

```
No Exams have been added.
    [Add Exam]

No curriculum has been added.
    [Add Curriculum]

Application-level Subject context provides:
Subject [ Chemistry ▼ ] [+]
```

Creating a Subject does not require immediate creation of either an Exam or a
curriculum.
Exam setup may occur before curriculum entry. Question capture still requires a
valid persisted Question classification and therefore cannot complete until
usable curriculum data exists.
The Dashboard should make such dependencies visible through actionable status
rather than treating an empty Subject as an error.

## 8. Explicitly outside Sprint 12

The following are not Sprint 12 work:

- richer Question Search filtering;
- portable `.eqwork` distributed collection;
- stable cross-database UUID identity for distributed collection;
- Exam Builder;
- printable/vector-preserving assessment and solution output;
- OCR;
- multi-page Shared Context capture;
- speculative curriculum-authoring extensions;
- unrelated performance redesign without measured evidence.

Richer Question Search filtering is returned to `docs/design/backlog.md`.

Multi-page Shared Context capture remains a rejected direction, not backlog
work.

## 9. Implementation and verification approach

Implementation proceeds one agreed slice at a time.

For each slice:

1. inspect current live `main` / current feature branch before exact edits;
2. make the smallest coherent persistence/domain change first where needed;
3. add focused repository/service regressions;
4. add focused TestFX workflow coverage for actual UI behaviour;
5. run only the targeted tests needed for the current sub-slice;
6. broaden validation at slice checkpoints;
7. update this sprint document when implementation decisions differ from design;
8. keep public API Javadoc and algorithmic comments current.

Cross-slice schema changes must preserve sequential migration from existing
schema-v14 databases and backup/restore migration compatibility.

Current implemented Sprint 12 schema progression is:

```text
v14
  -> v15 Exam lifecycle and expected Question count
  -> v16 managed SourceDocument SHA-256
  -> v17 explicit Question source-recapture state
  -> v18 Exam-level expected Question/Answer asset counts
```

Migration, schema-verification and backup/restore compatibility regressions cover
the new boundaries.

Release 0.2 is not considered complete until the normal release gate succeeds,
including formatting, non-UI tests, headless UI tests, strict Javadoc and
Windows packaging/installation verification.

## 10. Durable decisions established by design

1. New Question capture starts from a deliberately selected/configured Exam and
   Question booklet.
2. Question booklet PDFs are inspectable in setup mode before expected Question
   counts are recorded.
3. Expected Question count means top-level numbered Questions, not multipart
   parts.
4. Existing `21a`, `21b`, etc. multipart detection remains sufficient.
5. Managed source documents gain persisted content hashes.
6. Wrong source PDFs are corrected by invalidating dependent coordinates, never
   by silently remapping them.
7. Exam completeness is user-declared.
8. Completing an Exam locks structure but does not block ordinary content
   correction/enrichment.
9. MCQ explanation regions are supplementary to the authoritative A/B/C/D
   answer.
10. Corpus Dashboard distinguishes declared Exam state from calculated audit
    findings.
11. Richer Question Search filtering is deferred to the backlog.
12. Sprint 12 targets Release 0.2 because the user-facing workflow changes are
    substantial.
13. Subject is application-level context rather than Question-classification
    state.
14. The Corpus Dashboard is intended to become the main Subject working surface
    by Sprint 12 closeout, including useful empty-state actions.
15. New and legacy-imported Exams converge on the same Exam Setup / Asset
    Management model; legacy Question counts are evidence, not authoritative
    expected counts.
