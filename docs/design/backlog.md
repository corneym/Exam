# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 27 September 2026.\
> Sprint 11 is complete and merged.\
> Sprint 12 Corpus Dashboard and richer Question filtering are committed roadmap
> work and are not duplicated here.
>
> Priority in this document means priority among deferred/unresolved work. It does
> not make an item active sprint scope until it is explicitly promoted into a
> sprint document and roadmap position.

## High priority

These items should be considered early because they address data integrity,
repeated capture friction, or durable infrastructure that will support later
work.

### Capture workspace UI and state cleanup

The main capture workspace should better distinguish workspace context from the
Question currently being captured.

Working Subject is workspace-level state because it controls the active Subject
and filters Question/Answer work queues. It should therefore be removed from the
`CLASSIFICATION` pane and placed in its own pane above the capture controls.

The preferred layout is conceptually:

``` text
WORKING SUBJECT

CAPTURE WORKSPACE
    CLASSIFICATION
    QUESTION
    ANSWER
```

`CLASSIFICATION`, `QUESTION` and `ANSWER` should visually belong to one bordered
capture-workspace container. Working Subject should remain outside and above that
container.

Classification controls should be disabled until a Question capture/edit
workflow has actually begun. Merely opening the application or choosing a Working
Subject should not make Question-classification controls appear active.

The Question capture-mode controls also need simplification:

- `Capture New Questions` currently provides little visible effect when new
  capture is already selected by default. New Question capture should have an
  explicit inactive -> active transition, with a clearer label such as
  `Capture New Question` or `Start New Question Capture`.
- after saving a new Question, remaining in new-question capture mode is
  desirable for efficient sequential capture;
- imported-question capture controls should be shown only when the current
  Working Subject actually has imported Questions awaiting capture/resolution;
- when no imported work exists, both the imported-capture action and imported
  Question ComboBox should be hidden or otherwise absent from the normal
  workflow;
- `Capture Imported Questions` should use task-oriented wording such as
  `Complete Imported Question`.

Question content actions should use labels that describe their different scopes.
The current two Clear actions are semantically different and should not look
interchangeable:

``` text
Add Region             Discard Selection
Add Clipboard Image    Clear All Content
```

`Discard Selection` clears only the current unaccepted PDF rectangle.
`Clear All Content` clears all accepted staged Question content, including PDF
regions and clipboard images.

`Add Clipboard Image` is preferred over `Paste Image` because the operation adds
a first-class ordered Question content part rather than merely pasting into a
field.

### Correct an incorrectly assigned booklet Answer PDF

The application needs an explicit correction workflow for a booklet whose
assigned Answer/marking PDF is wrong.

This belongs at `ExamBooklet`/AnswerFile level rather than being hidden inside
`Edit Answer`, because the Answer PDF is assigned to the booklet and may serve
many Questions.

A suitable task-oriented action is:

``` text
Change Booklet Answer PDF...
```

Required behaviour:

- select/import the replacement Answer PDF;
- if the booklet has no persisted Answer regions, replace the booklet's
  AnswerFile assignment directly;
- if persisted written-response Answer regions already refer to the old file,
  do not silently remap them to the new PDF;
- warn that the stored page/rectangle coordinates belong to the old PDF;
- require explicit confirmation before invalidating those regions;
- after confirmation, remove the affected Answer regions, assign the correct
  AnswerFile and return the affected Questions to Answer capture;
- preserve Question content, classifications, marks and any independent MCQ
  answer letters.

The existing repository protection against assigning a new AnswerFile while old
regions refer to another file should remain. The correction workflow must work
with that invariant rather than bypass it.

### First-time Exam intake and asset checklist

When an Exam is being added for the first time, provide an optional intake
dialog that helps establish what source material is expected before detailed
capture begins.

The dialog could collect planning information such as:

- expected number of Question booklets;
- expected/approximate number of Questions in each booklet;
- expected number of Answer/marking booklets or PDFs;
- which Question and Answer PDFs are already available;
- any immediately known missing source material.

This is an intake/checklist concept, not permission to invent authoritative
Questions, booklets or Answers. Expected counts may be planning metadata until
the corresponding real assets are confirmed.

The same information should feed an Exam-level asset/completeness view so the
application can report whether all expected source assets have been captured.

### Exam asset audit and reconciliation reporting

Extend import/reconciliation reporting into an Exam-level asset audit rather
than treating it only as a legacy-import report.

Useful reporting may include:

- expected versus present Question booklets;
- expected versus present Answer/marking documents;
- Questions created or already present;
- Questions still needing Question content;
- Questions still needing Answers;
- unresolved response types;
- unresolved classifications;
- unresolved Shared Context requirements;
- source-document conflicts or duplicates;
- records rejected because they conflict with authoritative data.

Where practical, use the same validation/reconciliation model for first-time Exam
intake, later correction, legacy import and future portable work-package import.

A dry-run/preview mode may later reuse the same validation.

### Managed document hashing and duplicate detection

Persist SHA-256 or equivalent content hashes for managed source documents so
identical files can be recognised independently of filename or storage path.

Current collision handling can compare file bytes when needed, but persisted
hashes would make duplicate detection, Exam intake, reconciliation and future
distributed work-package import cheaper and more reliable.

Hashing should support, rather than replace, existing path and ownership
validation.

### Remove empty managed directories after Exam relocation

When Exam/provider/year correction moves managed source files, prune empty
managed directories left behind.

Work upwards only within the configured managed PDF root and stop at the first
non-empty or protected directory. Never delete outside the managed root.

### MCQ explanation regions

Support optional marking-PDF explanation material for multiple-choice Questions.

The stored A/B/C/D choice remains the primary MCQ Answer. One or more Answer PDF
regions may optionally be attached as explanation/working material for revision
output.

Explanation regions must remain supplementary:

- they are not required for MCQ completeness;
- absence of explanation material must not block capture or output;
- the ordinary MCQ answer letter remains authoritative.

## Planned future capability

These items are more concrete than "some day/maybe", but are not yet active
sprint work.

### Portable collection packages / offline distributed collection

Support multiple collectors working independently without sharing or
concurrently editing the authoritative SQLite database.

The preferred architecture is a portable work-package model:

``` text
Collector
    Capture Questions
        ↓
    Review local work
        ↓
    Export Work Package
        ↓
      .eqwork

Coordinator
    Import Work Package
        ↓
    Validate / preview
        ↓
    Resolve duplicates or conflicts
        ↓
    Import accepted work
        ↓
    Authoritative Question Bank
```

Collectors should work in their own local application/database. They should not
send copies of the authoritative database to one another and should not place a
live SQLite database on SharePoint, network sync or another concurrently edited
shared location.

A package may use an application-specific extension such as `.eqwork` and may
internally be a ZIP container with a versioned structure, for example:

``` text
collection.eqwork
    manifest.json
    questions.json
    sources.json
    images/
        ...
```

The exact treatment of source PDFs and other binary source assets remains to be
designed. The package must contain or identify everything required to validate
and import the collector's work without giving the package arbitrary authority
over the coordinator's database.

#### Stable identities

Database-local integer primary keys are not suitable as cross-database identity.

Entities that can be independently created and exchanged should gain stable
portable identifiers, normally UUIDs. At minimum the design must consider stable
identity for Questions and work packages, and should define stable identifiers
for referenced entities where necessary.

Example:

``` text
questionId = 92ab8e84-43cf-4f53-a114-...
packageId  = 751a45bb-...
```

A package UUID should be recorded after successful import so accidental
re-import can be detected immediately.

#### Idempotent import

Importing the same package more than once must not duplicate data.

A repeated import should be reportable in terms such as:

``` text
47 questions examined
0 new questions
47 already imported
```

#### Duplicate and conflict handling

Import must distinguish at least:

``` text
same stable ID + identical data
    -> already imported; ignore

same stable ID + changed data
    -> conflict; coordinator decides

different stable IDs + apparently same source/question
    -> possible duplicate; warn/review

completely new stable ID
    -> import candidate
```

Managed document hashing should be used where appropriate to help identify
duplicate source material.

#### Provenance

Imported work should retain collection/import provenance, including enough
information to answer questions such as:

``` text
Imported from: collection-A-2026-09-27.eqwork
Collected by: Collector A
Collected: 24 September 2026
Imported: 27 September 2026
```

The package manifest should include a format version, package identity, creation
time, application version and useful summary counts. Collector identity should
be recorded when supplied.

#### Reference-data boundary

A collection package should normally contain the collector's work, not a
replacement copy of every Subject, curriculum node, Exam and other authoritative
reference record.

Portable records should refer to stable identifiers for required reference data.
The coordinator's database remains authoritative for those records.

Import must not permit a collection package to arbitrarily rewrite existing
authoritative data. Normal package authority should be limited to defined
collection operations such as adding collected Questions, Question images/source
metadata, permitted classifications and collection/import provenance.

#### Preview and coordinator confirmation

Before applying a package, show a reconciliation summary such as:

``` text
43 new
3 duplicates
1 conflict
```

The coordinator should be able to inspect conflicts and confirm the accepted
import.

#### Later assignment packages

A later extension may allow the coordinator to export a collection assignment,
for example "collect the 2023 Chemistry examination", provide it to a collector,
and receive completed work back.

The same stable-identity, provenance and reconciliation design should support
that workflow rather than creating a separate synchronisation architecture.

This portable-package approach is preferred for the first distributed collection
implementation because it supports offline work and avoids the authentication,
networking, concurrent-editing, locking, server-deployment and synchronisation
complexity of a shared live database.

### Additional capture-workflow productivity features

Possible improvements include:

- next unresolved Question;
- automatic advance after save;
- skip/defer;
- previous item;
- direct entry from bank-management tools into the relevant capture workflow.

Promote these only when real corpus use demonstrates a repeated workflow cost.

### Explicit resolved-but-intentionally-incomplete dispositions

Consider whether corpus/bank management eventually needs explicit reviewed
states distinguishing unresolved work from deliberately accepted exceptions
such as source unavailable, Answer unavailable or deliberately out-of-scope
material.

Do not conflate this with the implemented Question-specific revision-output
exclusion mechanism.

## Retrieval / performance hardening

### Strengthen retrieval-domain invariants

Review applicability/result rules including compatible curriculum levels,
same-Subject enforcement, duplicate handling and invalid-level rejection.

### Extend SQLite retrieval integration coverage

Add realistic Subject-wide retrieval, one-to-many mapped Subtopics,
same-Subject isolation, empty Subtopics, direct-Descriptor Topic structures and
database reopen cases when a concrete regression risk justifies them.

### Benchmark broad searches

Measure Subject/Unit/Topic-wide searches, query plans, reconstruction cost and
deterministic ordering before adding indexes or redesigning queries.

### Measure preview rendering overlap

Measure large/multi-region previews and rapid selection changes before changing
threading or serialisation.

## Curriculum mapping hardening

### Database enforcement decision

Application writers enforce same Subject, different versions, same level and
allowed mapping direction. Decide later whether direct-SQL bypass risk justifies
database triggers or other enforcement.

Mapping-review completion should otherwise be reported through the corpus
reporting/dashboard work. No separate mapping-review workflow extension is
currently required.

## Later product areas

### Exam Builder

Potential capabilities include Question selection, ordering, section structure,
total marks, reproducible drafts, Answer/marking inclusion and retained
provenance.

Exam Builder consumes the bank and must not become a dependency of
revision/SCORM output.

### Printable/vector-preserving assessment and solution output

For print-oriented generation, evaluate direct source-PDF page/viewport clipping
rather than rasterising web assets. Preserve generated numbering, solution
alignment and source attribution.

## Some day / maybe

These items are deliberately retained as possibilities, but current evidence
does not justify scheduling them.

### Curriculum authoring follow-on work

Possible later assistance includes:

- PDF region-based text extraction;
- detection/highlighting of embedded images and diagrams;
- manual exclusion regions for maths/figures;
- Markdown/LaTeX maths editing and rendered preview;
- maths-authoring helper buttons and later equation-recognition assistance;
- further keyboard/efficiency improvements.

Curriculum hierarchy remains expert-authored; assistance must not infer
authoritative structure.

### Additional real-world workbook variants

Test representative legacy files only if continued use of legacy workbooks makes
the coverage worthwhile.

Possible cases include multiple Subjects/providers, unexpected formatting,
blank/formula-driven cells, conflicting duplicates, unusual Question codes,
missing MCQ answers, Shared Context evidence and relocated source PDFs.

Do not infer multipart/Shared Context relationships merely because workbook data
appears patterned.

### Multiple original classifications

Current `Question` stores one best-fit original Subtopic or Descriptor.
Reconsider many-to-many original classification only if real use demonstrates
that one best-fit provenance is inadequate.

Any future change must review schema/repositories, capture/import UI,
applicability, retrieval duplicate semantics, export placement, provenance and
tests.

### Additional capture assistance

Possible later assistance includes Question-boundary suggestions,
multipart/dependency suggestions and further keyboard/efficiency improvements.

Suggestions remain non-authoritative until confirmed.

## Rejected / superseded directions

### Multi-page Shared Context capture

**REJECTED**

Multi-page Shared Context capture will not be implemented. A Shared Context is
contained within a single source page.

Do not re-add this as planned or backlog work. Historical sprint/design records
may retain earlier proposals where necessary to describe genuine history, but
forward-looking documentation must identify the decision as rejected.

### Concurrent shared SQLite database for faculty collection

**REJECTED AS THE FIRST DISTRIBUTED-COLLECTION MODEL**

Do not use a live SQLite database on SharePoint, a synchronised drive or a
network share as the normal multi-user collection architecture.

Portable local work packages are the preferred first distributed-collection
direction. A future centrally hosted database may still be considered if the
deployment model later justifies it.

## Completed / scheduled elsewhere --- not backlog

Do not re-add:

- Sprint 04--10 completed backup, revision, SCORM, capture, curriculum, Search
  and output work;
- Sprint 11 integration regressions;
- Sprint 11 clipboard/Snipping Tool Question capture;
- Sprint 11 Search layout redesign;
- Sprint 11 tooltips;
- Sprint 11 Help/About/version work;
- Sprint 11 packaging/deployment and Release 0.1;
- Sprint 12 Corpus Dashboard;
- Sprint 12 richer Question filtering;
- standalone mapping-review completion tooling beyond reporting remaining
  mapping work in the corpus dashboard/reporting tools.

The following former backlog directions have been deliberately removed and
should not be reintroduced without new evidence:

- legacy image-snip import/support;
- mapping workflow extensions such as bulk/AI-assisted mapping;
- richer mapping-relation metadata.

## Backlog rules

- Keep deliberately deferred requirements represented until implemented,
  rejected or superseded.
- Do not duplicate active sprint work or committed next-sprint work here.
- Preserve completed sprint documents as historical evidence.
- Keep performance work measurement-driven.
- Prefer behaviour-focused regressions over coverage percentages.
- Do not invent placeholder data to resolve unclear ownership/semantics.
- Intake estimates and expected asset counts are planning/checklist data, not
  permission to create fictitious authoritative Questions, booklets or Answers.
- Do not promote chat prototypes or standalone mapping artefacts to implemented
  capability without repository/persistence evidence.
- Prefer safe, reviewable import/reconciliation over direct cross-database
  mutation.
