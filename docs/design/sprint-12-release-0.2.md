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

A first-class `Add Subject...` workflow must allow a new Subject to be created
without requiring an Exam or curriculum to be created at the same time.

Changing Subject changes the corpus and work being displayed. Existing
safeguards that prevent Subject changes while unsaved capture work is pending
must remain.

### 3.8 Legacy import and Exam Setup share one authoritative Exam model

Legacy import is an intake source, not a separate long-term Exam-management
model.

Legacy-imported Exams and booklets must feed the same Exam Setup / Asset
Management workflow used for newly entered Exams.

Reliable legacy metadata may pre-populate known Exam structure, including:

- provider;
- year;
- booklet identity;
- Question codes;
- marks;
- classifications;
- response-type evidence;
- multipart/source-Question relationships;
- existing MCQ Answer letters where supplied.

Legacy Question records provide evidence about Questions already encountered.
They must not establish the authoritative expected top-level Question count.

For example, finding 19 distinct top-level Questions in a legacy workbook does
not prove that the original booklet contained only 19 Questions. The expected
count is confirmed separately against the source Question booklet.

Legacy-imported Exams remain `ACTIVE` until the user deliberately reviews and
declares their structure complete.

Question PDFs, Answer/marking assets, booklet assignments, expected Question
counts, booklet format and AnswerFile capability metadata are reviewed through
the ordinary Exam Setup workflow.

## 4. Slice 1 --- Exam setup, assets, hashes and safe correction

### 4.1 Purpose

Establish the complete Exam-level source context required before Question
capture, including expected Question structure, managed source assets and safe
correction of incorrectly selected assets.

### 4.2 Exam setup / asset-management dialog

Starting new Question capture from a blank state opens an Exam setup /
asset-management workflow.

The workflow must support:

- selecting an existing Exam;
- creating a new Exam;
- reviewing all Question booklets for that Exam;
- reviewing all Answer/marking PDFs for that Exam;
- selecting the Question booklet to use for capture;
- returning later to correct or extend an active Exam.

The dialog is an Exam-level management surface rather than a one-PDF import
form.

### 4.3 Question booklet setup

For each Question booklet record:

- booklet name;
- booklet format:
  - `MULTIPLE_CHOICE`;
  - `WRITTEN_RESPONSE`;
  - `MIXED`;
- expected number of top-level Questions;
- Question booklet PDF when available;
- managed source-document hash.

The expected count is the number of numbered Questions, not the number of
captured Question parts.

### 4.4 Preview-only booklet inspection

When a Question booklet PDF is selected during Exam setup, the PDF must be open
for inspection before the user records the expected Question count.

This viewer is inspection-only:

- page navigation is available;
- the user may inspect the complete booklet;
- Question-region selection/capture is disabled;
- no Question content is created from this view.

This avoids asking the user to estimate the Question count from memory.

### 4.5 Expected-but-not-yet-available assets

Planning/expectation data must not require fictitious authoritative domain
objects.

If the user knows that an Exam should contain a Question booklet but does not
yet have its PDF, the application may retain planning/expectation information
without creating an authoritative `ExamBooklet` whose required source document
does not exist.

The implementation design must preserve existing domain invariants unless there
is a strong reason to change them.

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

The Exam setup workflow must show the Exam's Answer/marking PDFs and their
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
moved into the planned Exam Setup / Asset Management surface without changing
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

### 4.10 Exam completion control

An active Exam can be marked complete by the user.

Before confirmation the application may show advisory information such as:

- expected versus encountered top-level Questions;
- missing assets;
- Question/Answer audit findings.

Warnings do not prevent completion unless a genuine persistence/data-integrity
constraint would be violated.

A completed Exam is structurally locked until `Reactivate Exam` is selected.

## 5. Slice 2 --- Streamlined Question capture

### 5.1 Purpose

Make sustained Question capture clearer and more efficient after Slice 1 has
established the active Exam and Question booklet.

### 5.2 Workspace structure

Subject is application-level context established before entering Question
capture.

The capture workflow inherits the selected Subject and clearly identifies the
active Exam and Question booklet.

The preferred layout is conceptually:

``` text
APPLICATION CONTEXT
    Subject: Chemistry
    [Change Subject...] [Add Subject...]

ACTIVE EXAM / BOOKLET
    QCAA 2025 External Assessment — Paper 1
    [Change Exam / Assets...]

CAPTURE WORKSPACE
    CLASSIFICATION
    QUESTION
    ANSWER
```

CLASSIFICATION, QUESTION and ANSWER visually belong to one capture
workspace.

The Subject selector must not appear to be part of Question classification.
Changing the Subject changes application context and therefore the Dashboard,
available Exams and capture work.

### 5.3 Explicit capture entry

Classification and Question controls remain inactive until a capture/edit
workflow has begun.

`Start New Question Capture` explicitly enters new-Question capture.

After saving a new Question, the application remains in new-Question capture
mode and resets ready for the next Question.

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

Existing multipart detection remains based on codes such as `21a`, `21b`,
`21c`.

Entering a multipart code establishes or reuses the existing source identity,
for example:

``` text
Question code: 22a
SourceQuestion: 22
```

This indicates that shared context may be required.

The existing SourceQuestion / Shared Context model remains authoritative.
Sequential parts should reuse an already-resolved Shared Context decision rather
than repeatedly asking the user the same question.

### 5.6 Imported/incomplete Question capture

Imported/incomplete Question capture remains available, but controls appear only
when relevant work exists for the current workspace context.

Use task-oriented wording such as:

``` text
Complete Imported Question
```

When no relevant imported work exists, both the action and its Question selector
should be absent from the normal workspace.

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

### 7.7 Correction routing

Use existing or Sprint 12 workflows rather than creating parallel editors.

Examples:

``` text
Missing/wrong Exam asset
    -> Exam Setup / Asset Management

Missing Question content
    -> Question Capture

Missing Answer
    -> Answer Capture

Unknown response type
    -> metadata correction / existing bulk correction

Unresolved Shared Context
    -> Question / Shared Context workflow
```

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

Application-level Subject context must also provide:
[Add Subject...]
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
