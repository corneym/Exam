# Exam Question Bank Backlog

> **Authoritative deferred/unresolved-work list:** 5 October 2026.  
> Sprint 12 implementation is complete and **READY FOR PR** on `feature/sprint-12`; pull-request merge and Release 0.2 closeout (#64) remain active and are therefore not backlog work.
>
> Priority here means priority among deliberately deferred/unresolved work. An item is not active sprint scope until it is explicitly promoted.

## High priority

### Richer Question Search filtering

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

### Scope Revision exports to the authoritative Working Subject — #78

Issue #78 remains open without accepted Sprint 12 implementation evidence. Review the current export workflow and decide whether Revision HTML/SCORM launch should inherit the Dashboard Working Subject in the same way as Search, curriculum and Exam/Assets.

Do not mark this complete merely because the application has an authoritative Working Subject elsewhere.

## Near-term UI/workflow refinements

These are explicit GitHub issues that were not Sprint 12 blockers.

### Question Search spacing/action layout — #82

Improve internal margins and regularise the Edit/Split/Metadata/Shared Context/Answer action-button spacing. Treat this as layout polish rather than a retrieval redesign.

### Store syllabus documents in managed data — #83

Design and implement managed syllabus-document storage with Subject/version/current-version metadata and an open-in-PDF-pane workflow. Preserve the distinction between syllabus source documents and authoritative curriculum hierarchy data.

### Mapping Review single-syllabus empty state — #85

When a Subject has no second syllabus to map, do not open a meaningless mapping-review dialog. Present a clear unavailable/not-applicable state instead.

### Dashboard column alignment — #86

Centre/regularise Dashboard table cell alignment where appropriate. Low priority visual polish; do not alter table semantics.

### Dashboard lifecycle progress feedback — #87

Show visible progress while `Mark Active` / `Mark Complete` persistence and Dashboard refresh are running, prevent duplicate lifecycle actions and clear progress on success/failure. Preserve existing lifecycle/readiness semantics. The issue explicitly records this as non-blocking UI polish.

## Planned future capability

### Portable collection packages / offline distributed collection

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
- stable portable identities (normally UUIDs) rather than database-local integer IDs;
- package identity and idempotent import detection;
- duplicate/conflict classification;
- source-document hash use where appropriate;
- collection/import provenance;
- explicit reference-data authority boundaries;
- preview and coordinator confirmation before mutation.

A later assignment-package extension may let a coordinator distribute a defined collection task and receive completed work back through the same reconciliation architecture.

### Additional capture-workflow productivity features

Possible later improvements include skip/defer current work, previous unresolved item and keyboard/efficiency shortcuts. Promote only when sustained real-corpus use demonstrates repeated cost.

### Explicit resolved-but-intentionally-incomplete dispositions

Consider whether corpus management eventually needs reviewed states such as source unavailable, Answer unavailable or deliberately out of scope. Do not conflate these with persisted Exam `COMPLETE` or revision-output exclusions.

## Retrieval / performance hardening

### Strengthen retrieval-domain invariants

Review applicability/result rules including compatible curriculum levels, same-Subject enforcement, duplicate handling and invalid-level rejection.

### Extend SQLite retrieval integration coverage

Add broader realistic retrieval/reopen cases when a concrete regression risk justifies them.

### Benchmark broad searches

Measure Subject/Unit/Topic-wide searches, query plans, reconstruction cost and deterministic ordering before adding indexes or redesigning queries.

### Measure preview rendering overlap

Measure large/multi-region previews and rapid selection changes before changing threading or serialisation.

## Curriculum mapping hardening

### Database enforcement decision

Application writers enforce same Subject, different versions, same level and allowed mapping direction. Decide later whether direct-SQL bypass risk justifies database triggers or other enforcement.

## Later product areas

### Exam Builder

Potential capabilities include Question selection, ordering, section structure, total marks, reproducible drafts, Answer/marking inclusion and retained provenance.

Exam Builder consumes the bank and must not become a dependency of revision/SCORM output.

### Printable/vector-preserving assessment and solution output

For print-oriented generation, evaluate direct source-PDF page/viewport clipping rather than rasterising web assets. Preserve generated numbering, solution alignment and source attribution.

## Some day / maybe

### Curriculum authoring follow-on work

Possible assistance includes PDF region-based text extraction, diagram/image handling, manual maths exclusion regions, Markdown/LaTeX editing/preview and later equation-recognition support. Curriculum hierarchy remains expert-authored.

### Additional real-world workbook variants

Test representative legacy workbook variants only if continued real use makes the coverage worthwhile. Do not infer multipart/Shared Context relationships merely from patterned workbook data.

### Multiple original classifications

Current `Question` stores one best-fit original Subtopic or Descriptor. Reconsider many-to-many original classification only if real use shows one provenance location is inadequate.

### Additional capture assistance

Possible later assistance includes Question-boundary suggestions, multipart/dependency suggestions and further keyboard/efficiency improvements. Suggestions remain non-authoritative until confirmed.

## Rejected / superseded directions

### Multi-page Shared Context capture — rejected

A Shared Context is contained within one source page. Do not re-add multi-page Shared Context as planned/backlog work.

### Concurrent shared SQLite database for faculty collection — rejected as first distributed model

Portable local work packages are the preferred first distributed-collection direction. A future centrally hosted service may be reconsidered only if later deployment requirements justify it.

### Per-response-type expected counts for mixed Question booklets — #73 not planned

Sprint 12 retained one total expected top-level Question count for mixed booklets. Do not reintroduce expected-MCQ/expected-written subcounts without new evidence.

### Parallel import-audit/reconciliation system — #22 superseded

The broad #22 concept is covered by #58, #65, #68 and #81. Do not build another parallel reporting model.

## Completed/scheduled elsewhere — not backlog

Do not re-add completed Sprint 12 work, including:

- Exam/Assets workspace, expected-count planning and authoritative Exam metadata correction (#43–#50, #69, #74);
- managed source-document hashing and safe PDF correction (#46–#48);
- Exam lifecycle/readiness and structural locking (#49, #65, #81);
- capture workflow simplification/refactors/layout closeout (#51–#54, #59, #70, #71, #79, #84);
- AnswerFile explanation metadata and MCQ explanation capture/retrofit (#55–#57, #72, final semantics in #65);
- operational Corpus Dashboard, routing, mapping status and home-screen ownership (#58, #60–#62, #65–#67, #76, #77);
- legacy import reconciliation (#68);
- asset deletion (#80);
- maintained Sprint 12 Help (#63).

Issue #34 is also not backlog work: its requested pre-v13 backup/restore/startup regression already existed and the issue is closed.

Issue #64 is active release closeout rather than backlog work.

## Backlog rules

- Keep deliberately deferred requirements represented until implemented, rejected or superseded.
- Do not duplicate active closeout work.
- Preserve completed sprint documents as historical evidence.
- Keep performance work measurement-driven.
- Prefer behaviour-focused regressions over coverage percentages.
- Do not invent placeholder data to resolve unclear ownership/semantics.
- Expected asset/question counts are planning/audit metadata, not permission to create fictitious authoritative records.
- Prefer safe, reviewable import/reconciliation over direct cross-database mutation.
