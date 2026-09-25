# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 26 September 2026.\
> Active Sprint 11 work is recorded in `sprint-11-release-0.1.md` and is
> not duplicated here.\
> Sprint 12 Corpus Dashboard and richer Question filtering are committed
> roadmap work and are not "some day/maybe" backlog.

## Some day / maybe

These items are deliberately retained as possibilities, but current
evidence does not justify scheduling them.

### Explicit resolved-but-intentionally-incomplete dispositions

Consider whether corpus/bank management eventually needs explicit
reviewed states distinguishing unresolved work from deliberately
accepted exceptions such as source unavailable, Answer unavailable or
deliberately out-of-scope material.

Do not conflate this with the implemented Question-specific
revision-output exclusion mechanism.

### Mapping-review completion tooling

Possible future operational tooling includes coverage counts, rapid
movement through unmapped classifications and clearer reviewed "no
current equivalent" workflows.

Existing teacher-reviewed mapping remains authoritative. Automated
suggestions, if extended, remain aids rather than authoritative mapping
decisions.

### Additional capture-workflow productivity features

Possible improvements include next unresolved Question, automatic
advance after save, skip/defer, previous item and direct entry from
bank-management tools into the relevant capture workflow.

Only promote these into a sprint when real corpus use demonstrates that
they solve a repeated workflow cost.

### Multiple original classifications

Current `Question` stores one best-fit original Subtopic or Descriptor.
Reconsider many-to-many original classification only if real use
demonstrates that one best-fit provenance is inadequate.

Any future change must review schema/repositories, capture/import UI,
applicability, retrieval duplicate semantics, export placement,
provenance and tests.

### MCQ explanation capture

A future workflow may retain A/B/C/D as the primary MCQ Answer while
optionally allowing marking-PDF regions as explanation material for
revision output.

Explanation material must remain supplementary rather than required for
completeness.

### Additional capture assistance

Possible later assistance includes Question-boundary suggestions,
multipart/dependency suggestions and further keyboard/efficiency
improvements. Suggestions remain non-authoritative until confirmed.

## Capture / correction housekeeping

### Remove empty managed directories after Exam relocation

Sprint 09 relocates managed exam/answer PDF files and updates persisted
`SourceDocument` paths when corrected provider/year metadata changes the
authoritative storage directory.

Future housekeeping may prune empty managed year/provider directories
after a successful move, working upwards only within the managed PDF
root and stopping at the first non-empty or protected directory.

## Curriculum authoring follow-on work

Possible later assistance includes:

-   PDF region-based text extraction;
-   detection/highlighting of embedded images and diagrams;
-   manual exclusion regions for maths/figures;
-   Markdown/LaTeX maths editing and rendered preview;
-   maths-authoring helper buttons and later equation-recognition
    assistance;
-   further keyboard/efficiency improvements.

Curriculum hierarchy remains expert-authored; assistance must not infer
authoritative structure.

## Import / reconciliation

### Managed document hashing and duplicate detection

Consider persisted SHA-256 content hashing so identical files can be
recognised independently of filename/storage path. Current collision
handling compares file content where needed but no hash is persisted.

### Import audit and reconciliation reporting

Possible later reporting includes records created, records already
identical, conflicts rejected, missing PDFs/booklets, Questions still
needing regions, Answer state, unresolved classifications and unresolved
Shared Context requirements.

A dry-run mode may later reuse the same validation.

### Additional real-world workbook variants

Test representative legacy files for multiple Subjects/providers,
unexpected formatting, blank/formula-driven cells, conflicting
duplicates, unusual Question codes, missing MCQ answers, Shared Context
evidence and relocated source PDFs.

Do not infer multipart/Shared Context relationships merely because
workbook data appears patterned.

### Legacy image-snip import/support decision

After Sprint 11 clipboard/image Question support exists, separately
determine whether irreplaceable legacy Questions/Answers stored only as
old image snips warrant one-time import tooling.

## Retrieval / performance hardening

### Strengthen retrieval-domain invariants

Review applicability/result rules including compatible curriculum
levels, same-Subject enforcement, duplicate handling and invalid-level
rejection.

### Extend SQLite retrieval integration coverage

Add realistic Subject-wide retrieval, one-to-many mapped Subtopics,
same-Subject isolation, empty Subtopics, direct-Descriptor Topic
structures and database reopen cases when a concrete regression risk
justifies them.

### Benchmark broad searches

Measure Subject/Unit/Topic-wide searches, query plans, reconstruction
cost and deterministic ordering before adding indexes or redesigning
queries.

### Measure preview rendering overlap

Measure large/multi-region previews and rapid selection changes before
changing threading or serialisation.

## Curriculum mapping hardening

### Database enforcement decision

Application writers enforce same Subject, different versions, same level
and allowed mapping direction. Decide later whether direct-SQL bypass
risk justifies DB triggers or other enforcement.

### Mapping workflow extensions

Possible later work includes bulk mapping import, improved similarity
scoring, optional AI-assisted suggestions and broader coverage
reporting.

Any assisted mechanism must still require explicit teacher confirmation.

### Richer mapping-relation metadata

Decide whether relation type/confidence/notes such as split, merged,
partial, removed and new should remain external review metadata or
become persisted application data.

## Later product areas

### Exam Builder

Potential capabilities include Question selection, ordering, section
structure, total marks, reproducible drafts, Answer/marking inclusion
and retained provenance.

Exam Builder consumes the bank and must not become a dependency of
revision/SCORM output.

### Printable/vector-preserving assessment and solution output

For print-oriented generation, evaluate direct source-PDF page/viewport
clipping rather than rasterising web assets. Preserve generated
numbering, solution alignment and source attribution.

## Shared faculty operation

Do not treat a live SQLite database on SharePoint/network sync as a safe
concurrently edited datastore.

Possible later models include local SQLite plus controlled
import/export/merge or an IT-supported central database.

## Rejected / superseded directions

### Multi-page Shared Context capture

**REJECTED**

Multi-page Shared Context capture will not be implemented. A Shared
Context is contained within a single source page.

Do not re-add this as planned or backlog work. Historical sprint/design
records may retain earlier proposals where necessary to describe genuine
history, but forward-looking documentation must identify the decision as
rejected.

## Completed / scheduled elsewhere --- not backlog

Do not re-add:

-   Sprint 04--10 completed backup, revision, SCORM, capture,
    curriculum, Search and output work;
-   Sprint 11 integration regressions;
-   Sprint 11 clipboard/Snipping Tool Question capture;
-   Sprint 11 Search layout redesign;
-   Sprint 11 tooltips;
-   Sprint 11 Help/About/version work;
-   Sprint 11 packaging/deployment and release 0.1;
-   Sprint 12 Corpus Dashboard;
-   Sprint 12 richer Question filtering.

## Backlog rules

-   Keep deliberately deferred requirements represented until
    implemented, rejected or superseded.
-   Do not duplicate active sprint work or committed next-sprint work
    here.
-   Preserve completed sprint documents as historical evidence.
-   Keep performance work measurement-driven.
-   Prefer behaviour-focused regressions over coverage percentages.
-   Do not invent placeholder data to resolve unclear
    ownership/semantics.
-   Do not promote chat prototypes or standalone mapping artefacts to
    implemented capability without repository/persistence evidence.
