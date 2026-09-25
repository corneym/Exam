# Sprint 09 — Capture Workflow and Corpus Correction

> **Final status:** COMPLETE AND MERGED 20 September 2026.  
> Implementation branch: `feature/capture-workflow`.  
> Final feature documentation commit: `57777aa`.  
> Merge to `main`: `2533586`.

## Purpose

Sprint 09 was driven by sustained use of the application while completing real
Question-bank data.

Sprint 08 had established curriculum authoring, mapping coverage,
metadata-correction and corpus-audit foundations. Sprint 09 addressed practical
problems exposed by longer capture sessions: controls that were awkward at narrow
workspace widths, persistence-order lists that did not match exam source order,
and correction operations required by real legacy data.

Revision HTML presentation redesign was deliberately outside Sprint 09.

## Starting point

The architecture already provided:

- explicit SourceQuestion multipart identity;
- explicit SharedQuestionContext and ordered context regions;
- transactional Question capture/edit persistence;
- legacy metadata correction;
- response-type-aware Question/Answer semantics;
- explicit capture-selection ownership;
- asynchronous Search and background save work;
- Corpus Audit filters and routing to correction workflows.

Sprint 09 reused these concepts rather than creating parallel systems.

## Design principles retained

### User-visible state follows logical capture ownership

Question, Shared Context and Answer workflows share one PDF workspace. Controls
that cannot safely act on another workflow's pending selection are disabled.

### Responsive layout is functional

Important field labels/actions must remain readable at supported narrow capture
widths.

### Source order is distinct from database identity

Persistent IDs are identity, not display order. Human work queues should follow
provider/year/booklet/natural Question order where appropriate.

### Corrections preserve identity unless identity itself is what is being corrected

Exam metadata correction, Question editing, shared-preamble replacement and
legacy split must retain legitimate linked entities and reject unsafe merges.

## Slice 1 — deterministic source/natural ordering

**COMPLETE**

Capture/management views use deterministic provider/year/booklet/natural
Question ordering where source order is the relevant human workflow.

## Slice 2 — Answer capture workflow refinement

**COMPLETE**

Answer capture was adjusted for sustained use, including Question marks,
selection-aware controls, source-order queue behaviour and registered Answer-PDF
visibility/reuse for MCQ work without changing MCQ completeness semantics.

## Slice 3 — Question capture control refinement

**COMPLETE**

Question capture received responsive control/layout adjustments and explicit
Multiple Choice/Written Response radio-button presentation while preserving the
persisted Question-level response type established in Sprint 08.

## Slice 4 — imported-question activation/performance

**COMPLETE**

Imported Questions activation no longer performs the pathological repeated
refresh/reconstruction work observed during real capture use. Asynchronous and
stale-result rules remain consistent with existing Search/PDF behaviour.

## Slice 5 — shared-preamble replacement / recapture

**COMPLETE**

An existing persisted SharedQuestionContext can be deliberately recaptured while
retaining context identity and legitimate Question links. Replacement is staged,
old regions remain authoritative until commit, cancellation/failure preserves the
old content, and reload/Search/revision output use the replacement after success.

## Slice 6 — Exam metadata correction and known-PDF recognition

**COMPLETE**

Exam-level correction changes authoritative Exam metadata while preserving
linked Booklets/Questions/Answers/source relationships.

When provider/year changes the managed directory, booklet/answer PDFs are moved
and persisted SourceDocument paths are updated with collision checks and rollback
protection. Assessment-name-only edits do not move files because assessment name
is not part of the managed path.

Selecting a PDF already known to persistence reuses stored metadata rather than
creating duplicate Exam/Booklet records.

## Slice 7 — legacy single-Question -> multipart split

**COMPLETE**

The workflow supports explicit destination part codes, marks, classification and
response type; staged Question regions; optional new/reused shared preamble;
explicit ownership of an existing Answer; compatible SourceQuestion reuse;
atomic persistence; duplicate/incompatible identity rejection; cancellation
protection and reload reconstruction.

The original Question row is reused for one resulting part where safe.

## Slice 8 — Search Questions and Corpus Audit usability

**COMPLETE**

Search Questions now exposes `Current syllabus` and `All Questions`. The latter
shows the stored bank without falsely claiming current applicability. Both use
deterministic source ordering.

Corpus Audit uses deterministic provider/year/booklet/natural Question order and
numeric year ordering while retaining existing filters/totals.

## Additional sustained-use corrections

### Search dialog size persistence

User-resized Search dimensions survive temporary hide/redisplay cycles caused by
correction actions.

### MCQ Answer-PDF visibility

Selecting an MCQ can display/reuse the registered marking PDF even though MCQ
completeness remains letter based and region controls remain response-type
appropriate.

### Redundant capture-menu navigation removed

The redundant `Questions -> Capture New Questions` menu action was removed. The
visible capture-mode controls in the main workspace are the authoritative entry
point for new/imported Question capture.

### Managed-file relocation on Exam correction

Provider/year correction now keeps filesystem placement and persisted source
paths consistent.

### UI package refactor and CI hardening

JavaFX UI code was reorganised into focused feature packages without changing
domain semantics. GitHub Actions was split into non-UI, remaining-UI and
workflow-UI matrix jobs and configured for feature/refactor/chore branches and
pull requests to `main`.

## Verification and closeout

Sprint 09 implementation head `11acb24` passed the configured GitHub Actions
jobs (run `35491556692`). Final documentation was committed as `57777aa`, then
the feature branch was merged to `main` as `2533586` on 20 September 2026.

The subsequent repository-governance step protected `main` and exercised the
protected pull-request workflow through PR #1. That governance step is not
unfinished Sprint 09 product scope.

## Explicitly outside Sprint 09

The following were deliberately not Sprint 09 completion requirements:

- revision HTML Subtopic/Descriptor grouping selection;
- MCQ-before-written output ordering;
- page-local revision numbering;
- empty-branch/unit-selection export options;
- general shared-context capture for independent Questions/MCQs;
- curriculum-code/ComboBox synchronisation repair;
- multi-page automatic shared-preamble capture;
- optional MCQ explanation regions;
- multiple original classifications;
- Question-level applicability exceptions;
- explicit out-of-scope disposition;
- Exam Builder and printable assessment generation;
- clipboard/image-attachment Questions;
- packaging/deployment.

The first five relevant items above were promoted into Sprint 10 after further
real-use discussion. Remaining deferred work stays in `docs/design/backlog.md`.

## Documentation relationship

This file is the canonical Sprint 09 design/final-state record.

Current implemented capability belongs in `docs/current-status.md`. Forward
sequencing belongs in `docs/DEVELOPMENT_ROADMAP.md`. Active Sprint 10 design is
`docs/design/sprint-10-capture-output.md`. Deferred work belongs in
`docs/design/backlog.md`.
