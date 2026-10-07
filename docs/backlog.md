# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 7 October 2026.  
> Sprints 01–13 are complete and merged. Sprint 14 active work is recorded in `docs/design/sprint-14.md` and GitHub issues #100–#106; it is not duplicated here.
>
> Priority here means priority among deliberately deferred/unresolved work. An item is not active sprint scope until it is explicitly promoted.

## High priority

### Richer Question Search filtering — #94

Extend Question Search with retrieval-oriented filters justified by real corpus use.

Candidate dimensions include:

- Exam provider;
- Exam year;
- assessment/Exam;
- booklet;
- response type;
- current applicability;
- revision-output exclusion state.

Keep Search conceptually distinct from Corpus Dashboard:

```text
Dashboard
    operational work:
    what is incomplete/problematic and where to fix it

Search
    retrieval work:
    which Questions do I want to inspect or use
```

Do not duplicate Dashboard work-queue filters into Search unless later real use demonstrates a genuine retrieval need.

Sprint 13 #92 already established reusable immutable Exam/booklet Search narrowing. Build #94 on that mechanism rather than introducing a separate filtering architecture.

### Benchmark broad searches — #27

Run this after #94, not before it.

Measure Subject/Unit/Topic-wide and filtered searches, query plans, reconstruction cost and deterministic ordering only after the richer Search workload exists.

Use the measurements to decide whether retrieval invariants/integration work (#25/#26), indexes or query redesign are justified.

### Dashboard workflow for assigning missing Question descriptors — #99

Add a direct Dashboard workflow for missing Descriptor classification.

Required direction:

- include missing Descriptor work in the Exam-level `Work` count using an explicit non-double-counting rule;
- expose a booklet-level `Assign Descriptors`-style action when `No descriptor > 0`;
- scope the classification workflow strictly to unresolved Questions in the selected booklet;
- keep the user within that scope as classifications are saved;
- represent `Missing descriptor` as Question-level work in the Dashboard;
- refresh booklet and Exam counts immediately after persistence;
- reuse existing Search/classification persistence behaviour rather than build a Dashboard-only editor.

Issue #99 was deliberately excluded from Sprint 13 and remains future work.

## Active Sprint 14 work — not backlog

Sprint 14 is the Subject-first managed-data restructure under umbrella issue #100:

- #101 — Subject-first managed data layout contract;
- #102 — move Exam and Answer PDFs to the subject-first layout;
- #103 — move curriculum assets to the subject-first layout;
- #104 — retain legacy import workbooks as managed Subject assets;
- #105 — migrate existing data roots safely;
- #106 — update backup/restore for the subject-first data layout.

See `docs/design/sprint-14.md`.

Do not duplicate these items as backlog work while Sprint 14 is active.

The earlier roadmap assumption that printable/vector-preserving output (#38) would be Sprint 14 is superseded. #38 remains future product work.

## Planned future capability

### Portable collection packages / offline distributed collection — #36

Support multiple collectors working independently without sharing or concurrently editing the authoritative SQLite database.

Preferred model:

```text
Collector
    local capture/review
        ↓
    export .eqwork package
        ↓
Coordinator
    validate / preview
        ↓
    resolve duplicate/conflict
        ↓
    import accepted work
        ↓
Authoritative Question Bank
```

Collectors should work in their own local application/database. Do not place a live SQLite database on SharePoint, a synchronised drive or another concurrently edited shared location.

The package design should include:

- versioned manifest;
- stable portable identities, normally UUIDs, rather than database-local integer IDs;
- package identity and idempotent import detection;
- duplicate/conflict classification;
- source-document hash use where appropriate;
- collection/import provenance;
- explicit reference-data authority boundaries;
- preview and coordinator confirmation before mutation.

Sprint 14’s Subject-first managed filesystem is intended to make later Subject-scoped source exchange cleaner, but distributed worker/coordinator work-package exchange remains out of Sprint 14 scope.

A later assignment-package extension may let a coordinator distribute a defined collection task and receive completed work through the same reconciliation architecture.

### Capture-workflow productivity assistance — #95

Use #95 as a deferred evidence/design tracker for possible:

- skip/defer current work;
- previous unresolved item;
- keyboard/efficiency shortcuts;
- Question-boundary suggestions;
- multipart/dependency suggestions.

Promote individual implementation issues only when sustained real-corpus use demonstrates repeated cost.

Suggestions must remain non-authoritative until confirmed by the teacher.

### Explicit resolved-but-intentionally-incomplete dispositions — #20

Consider whether corpus management eventually needs reviewed states such as source unavailable, Answer unavailable or deliberately out of scope.

Do not conflate these with persisted Exam `COMPLETE` or revision-output exclusions.

## Retrieval / performance hardening

### Strengthen retrieval-domain invariants — #25

Review applicability/result rules including compatible curriculum levels, same-Subject enforcement, duplicate handling and invalid-level rejection.

Use evidence from #27 before broadening this work.

### Extend SQLite retrieval integration coverage — #26

Add broader realistic retrieval/reopen cases when a concrete regression risk or #27 measurement justifies them.

### Measure preview rendering overlap — #28

Measure large/multi-region previews and rapid selection changes before changing threading or serialisation.

## Curriculum mapping hardening

### Database enforcement decision — #29

Application writers enforce same Subject, different versions, same level and allowed mapping direction.

Decide later whether direct-SQL bypass risk justifies database triggers or other enforcement.

### Mapping workflow assistance — #30

Consider additional mapping-review assistance only after the current review workflow remains stable under real corpus use.

### Richer mapping relation metadata — #31

Decide whether mapping relationships need persisted metadata beyond the current accepted mapping structure only if a concrete use case requires it.

## Later product areas

### Exam Builder — #37

Potential capabilities include Question selection, ordering, section structure, total marks, reproducible drafts, Answer/marking inclusion and retained provenance.

Exam Builder consumes the bank and must not become a dependency of revision/SCORM output.

### Printable/vector-preserving assessment and solution output — #38

This remains future work. It is no longer assigned to Sprint 14; the current Sprint 14 is the Subject-first managed-data restructure.

For print-oriented generation, evaluate direct source-PDF page/viewport clipping rather than rasterising web assets.

Preserve generated numbering, solution alignment and source attribution.

## Some day / maybe

### Curriculum authoring follow-on work — #17 / #18

Possible assistance includes PDF region-based text extraction, diagram/image handling, manual maths exclusion regions, Markdown/LaTeX editing/preview and later equation-recognition support.

Curriculum hierarchy remains expert-authored.

### Additional real-world workbook variants — #23

Test representative legacy workbook variants only if continued real use makes the coverage worthwhile.

Do not infer multipart/Shared Context relationships merely from patterned workbook data.

### Multiple original classifications — #19

Current `Question` stores one best-fit original Subtopic or Descriptor.

Reconsider many-to-many original classification only if real use shows one provenance location is inadequate.

## Rejected / superseded directions

### Multi-page Shared Context capture — rejected

A Shared Context is contained within one source page.

Do not re-add multi-page Shared Context as planned/backlog work.

### Concurrent shared SQLite database for faculty collection — rejected as first distributed model

Portable local work packages are the preferred first distributed-collection direction.

A future centrally hosted service may be reconsidered only if later deployment requirements justify it.

### Per-response-type expected counts for mixed Question booklets — #73 not planned

Sprint 12 retained one total expected top-level Question count for mixed booklets.

Do not reintroduce expected-MCQ/expected-written subcounts without new evidence.

### Parallel import-audit/reconciliation system — #22 superseded

The broad #22 concept is covered by #58, #65, #68 and #81.

Do not build another parallel reporting model.

## Completed/scheduled elsewhere — not backlog

Do not re-add completed Sprint 12 or Sprint 13 work.

### Sprint 13 completed through PR #107

The following issues are closed and are historical evidence, not backlog work:

- #93 — UI refactoring;
- #89 — Search classification Save/refresh defects;
- #82 — Search spacing/action layout;
- #92 — Dashboard Inspect Questions / scoped Search entry;
- #86 — Dashboard column alignment;
- #87 — Dashboard lifecycle progress feedback;
- #85 — Mapping Review single-syllabus unavailable state;
- #78 — Revision HTML/SCORM authoritative Working Subject;
- #97 — managed curriculum-workbook onboarding;
- #98 — automatic restart after data-root change or restore.

Issue #99 is deliberately separate future work.

### Managed syllabus PDFs — #83 completed

Issue #83 describes functionality already present before Sprint 13.

Implemented behaviour includes:

- managed syllabus-PDF storage;
- persisted portable `source_pdf_path` on `SyllabusVersion`;
- Subject/version/current-version identity supplied by the syllabus model;
- automatic resolution/opening of the managed source PDF in Curriculum Authoring;
- replacement support subject to curriculum lifecycle rules.

Record #83 as completed under Sprint 11.

Issue #34 is also not backlog work: its requested old-backup regression already existed and the issue is closed.

## Backlog rules

- Keep deliberately deferred requirements represented until implemented, rejected or superseded.
- Do not duplicate active sprint or closeout work.
- Preserve completed sprint documents as historical evidence.
- Keep performance work measurement-driven.
- Prefer behaviour-focused regressions over coverage percentages.
- Do not invent placeholder data to resolve unclear ownership/semantics.
- Expected asset/question counts are planning/audit metadata, not permission to create fictitious authoritative records.
- Prefer safe, reviewable import/reconciliation over direct cross-database mutation.
