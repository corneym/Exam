# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 7 October 2026.  
> Sprint 13 is complete and merged. Sprint 14 Subject-first managed-data work is active. Sprint 15 has selected #99, #94 and #27, with performance decomposition recorded in #109–#111 and `docs/design/sprint-15.md`.
>
> Priority here means priority among deliberately deferred/unresolved work. An item is not active sprint scope until it is explicitly promoted.

## Active / selected sprint work — not backlog

Do not duplicate these items as deferred backlog while their sprint work is active or selected.

### Sprint 14

Subject-first managed-data work is tracked under umbrella #100 and child issues #101–#106.

### Sprint 15

Selected product/performance work is:

- #99 — Dashboard workflow for assigning missing Question descriptors;
- #94 — richer retrieval-oriented Question Search filters;
- #27 — measured performance umbrella;
- #109 — opt-in runtime performance instrumentation;
- #110 — repeatable real-corpus performance benchmark runner;
- #111 — evidence-led Question reconstruction and SQLite hot-path investigation.

Sprint 15 performance work must establish measurements before optimisation. Search benchmarking is repeated after #94 so optimisation decisions use the richer real workload rather than an incomplete Search implementation.

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

## Retrieval / performance hardening after Sprint 15 evidence

### Strengthen retrieval-domain invariants — #25

Review applicability/result rules including compatible curriculum levels, same-Subject enforcement, duplicate handling and invalid-level rejection.

Use evidence from Sprint 15 #27/#111 before broadening this work.

### Extend SQLite retrieval integration coverage — #26

Add broader realistic retrieval/reopen cases when a concrete regression risk or Sprint 15 measurement justifies them.

### Measure preview rendering overlap — #28

Measure large/multi-region previews and rapid selection changes before changing threading or serialisation.

Sprint 15 instrumentation may provide useful evidence for this later issue, but #28 is not part of the selected Sprint 15 scope unless measurements demonstrate that preview rendering is the dominant current slowdown and the issue is explicitly promoted.

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

This remains future product work after the Sprint 14 reprioritisation.

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

## Corpus completion / review work

The following open issues represent real-corpus completion or review rather than current feature development:

- #5 — complete systematic curriculum mapping review;
- #6 — resolve Questions with unknown response type;
- #7 — complete missing Question source capture;
- #8 — resolve outstanding Shared Context requirements;
- #9 — complete missing Answers;
- #10 — correct outstanding Question and Exam metadata;
- #11 — complete outstanding legacy Question split corrections;
- #12 — exercise Revision HTML and SCORM against the real Chemistry corpus.

These may proceed as corpus work without redefining sprint feature scope unless deliberately promoted.

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

Do not re-add completed Sprint 12/13 work.

### Managed syllabus PDFs — #83 completed

Issue #83 was reviewed after Sprint 12 planning and found to describe functionality already present.

Implemented behaviour includes:

- managed syllabus-PDF storage;
- persisted portable `source_pdf_path` on `SyllabusVersion`;
- Subject/version/current-version identity supplied by the syllabus model;
- automatic resolution/opening of the managed source PDF in Curriculum Authoring;
- replacement support subject to curriculum lifecycle rules.

Sprint 14 moves the managed destination into the Subject-first layout without changing that completed feature status.

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
