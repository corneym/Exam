# Exam Question Bank Backlog

> Authoritative deferred-work list at 7 September 2026.
>
> Completed sprint documents remain historical records. Items intentionally deferred from completed work belong here until scheduled into a future sprint.
>
> Sprint 07 shared-context/multipart capture, editing and revision-output semantics are now scheduled and are therefore not deferred backlog work.

## High Priority

### Stabilise and isolate JavaFX tests

Origin: Question Retrieval Sprint v3 / merge-readiness review.

The complete suite remains sensitive to mouse/focus in some TestFX paths.

Acceptance criteria:

- UI tests do not depend on the developer leaving the mouse untouched;
- UI tests can run separately from non-UI tests where useful;
- repeated clean execution is reliable;
- failures distinguish application defects from lost focus/window interaction.

Sprint 07 should add focused tests for the workflows it changes, but general TestFX stabilisation remains separate.

### Complete asynchronous question-search regression coverage

Origin: Question Retrieval Sprint v3 / final review.

Add focused tests for:

- stale question-search completion;
- stale preview completion;
- hierarchy failure clearing prior results/details/preview;
- disposal while hierarchy/search/preview operations are in flight;
- repeated/idempotent disposal;
- no-current-syllabus Subject navigation;
- multiple-current-syllabus failure handling;
- zero-region legacy questions;
- missing/corrupt source PDFs.

### Build the broad capture/audit work queue

Origin: Legacy Metadata Import follow-on work.

Sprint 07 will provide enough status semantics to distinguish missing ordinary question regions from unresolved required shared context.

A broader administrative queue remains deferred.

Future queue should identify/filter questions requiring:

- question-region capture;
- answer-region capture;
- both;
- unresolved shared context;
- other later completion states if adopted.

Filters may include:

- Subject;
- provider;
- year;
- booklet;
- completion state.

Completion must not rewrite imported historical metadata/classification.

### Reconcile the 5 September Chemistry 2019 -> 2025 mapping workbook

Origin: Curriculum Descriptor Mapping chat, 5 September 2026.

A standalone normalized mapping workbook exists, but its integration with current SQLite mapping records is not established.

Required work:

- manually review all `YES` Low/no-match rows;
- check all `CHECK` Medium rows;
- confirm 2019 removed/no-direct-equivalent content;
- confirm 2025 new/no-direct-predecessor content;
- compare confirmed pairwise relationships with current mapping records;
- import/reconcile confirmed mappings without overwriting historical question classifications;
- produce coverage statistics after reconciliation;
- retain audit notes/confidence where useful.

Do not treat the standalone workbook as authoritative application state until reconciliation is complete.

## Normal Priority

### Decide whether `Question` requires multiple original classifications

Origin: Batching Exam Questions / early metadata design.

Evidence:

- the 2021 Neap classification exercise identified a question reasonably mapped to three descriptors;
- the early domain design proposed many-to-many `Question <-> SyllabusDescriptor`;
- the current `Question` model stores one best-fit Subtopic/Descriptor classification.

Decision required:

- explicitly affirm one-best-fit as permanent policy; or
- implement multiple original classifications.

If multiple classifications are required, review:

- schema/repositories;
- capture/import UI;
- applicability derivation;
- retrieval duplicate semantics;
- export placement;
- provenance display;
- tests.

Do not confuse multiple original classifications with one historical node mapping to several current nodes.

### Decide whether source-question processing needs an explicit out-of-scope disposition

Origin: Batching Exam Questions prototype.

During the Neap 2021 classification exercise, several questions were deliberately marked out of scope.

A later exam-ingestion/audit workflow may need to distinguish:

```text
not yet reviewed
captured/classified
explicitly out of scope
```

without forcing out-of-scope questions into the bank.

### Strengthen retrieval-domain invariants

Origin: Question Retrieval Sprint v3 / final review.

Review `QuestionApplicabilityMatch` and `QuestionRetrievalResult` rules, including:

- compatible original/current curriculum levels;
- same Subject;
- only Subtopic/Descriptor applicability nodes;
- duplicate handling;
- rejection of historical/Unit/Topic applicability where inappropriate.

### Extend retrieval integration coverage

Origin: Question Retrieval Sprint v3 / final review.

Add realistic SQLite integration tests for:

- Subject-wide retrieval across multiple Units;
- one historical Subtopic mapping to multiple current Subtopics;
- strict same-Subject isolation;
- empty Subtopics;
- Topic-level Descriptor structures;
- database close/reopen reconstruction.

### Add import audit and reconciliation reporting

Origin: Legacy Metadata Import follow-on work.

Report:

- records created;
- records already identical;
- conflicts rejected;
- missing PDFs/booklets;
- questions still needing regions;
- answers supplied/absent/unknown;
- unresolved classifications;
- unresolved shared-context requirements once Sprint 07 semantics exist.

A dry-run mode may later reuse the same validation.

### Validate additional real-world legacy workbook variants

Origin: Legacy Metadata Import follow-on work.

Test representative files for:

- multiple Subjects/providers;
- unexpected formatting;
- blank or formula-driven cells;
- duplicate conflicting rows;
- unusual question codes;
- missing MCQ answers;
- shared/preamble evidence spanning rows;
- renamed/relocated source PDFs.

Sprint 07 must still not infer relationships merely because a workbook appears to contain a pattern.

### Decide whether legacy image-snip import/support is still required

Origin: Batching Exam Questions.

The legacy application has many named question/answer snips. The current redesign prefers original PDFs + regions.

Decide whether irreplaceable legacy items exist only as image snips and therefore need:

- one-time attachment import;
- managed-file compatibility;
- or no special support because original PDFs are available.

Do not convert all legacy snips merely for architectural purity.

### Decide whether richer curriculum mapping relation metadata should be persisted

Origin: Curriculum Descriptor Mapping.

The standalone mapping work distinguishes conceptual cases such as direct/reworded, split, consolidated/merged, partial, removed and new content.

Current retrieval mainly needs confirmed directional pairs.

Decide whether relation type/confidence/notes should remain external review/audit metadata or become persisted application data for reporting and assisted review.

## Scheduled in Sprint 07 — not deferred backlog

### Shared context / preamble and multipart semantics

Origin: Batching Exam Questions, legacy `MultiPartQuestion`, Legacy Metadata Import follow-on work, Sprint 07 design.

Scheduled design:

`docs/design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

Sprint 07 has decided:

- explicit persisted source-question identity;
- explicit persisted reusable shared context;
- shared context and multipart identity are separate relationships;
- no guessed grouping during legacy migration/import;
- one shared-context link per question for this sprint;
- ordinary multi-region questions remain separate;
- multipart grouping occurs within the final current-curriculum output bucket;
- grouped marks are derived from included parts;
- HTML and SCORM output are amended in the same sprint;
- no persisted UI `pinned` flag.

Remove this section from the backlog after Sprint 07 is implemented and merged; until then it records that the prior unresolved backlog item is actively scheduled rather than deferred.

### Capture correction, syllabus-sensitive classification and workflow-critical UI redesign

Origin: current capture use / Sprint 07 design.

Sprint 07 now includes:

- existing-question correction;
- existing-answer correction;
- marks display during answer capture;
- guarded transient selections;
- selection ownership bug fix;
- resizable capture workspace;
- Descriptor-level selection where the selected syllabus branch contains Descriptors;
- classification controls that show the hierarchy actually present in the selected syllabus;
- validity rules allowing a Question to stop at Subtopic even when Descriptors exist below it;
- a requirement to select a Descriptor when a selected Topic has direct Descriptor children.

This is a consumer-side capture/validation change. Curriculum import, mapping, applicability and retrieval remain separate.

General cosmetic UI polish remains deferred below.

## Output / Future Sprint Inputs

### Later printable output — preserve vector source content

Origin: Batching Exam Questions / legacy output behaviour.

For print-oriented PDF generation, evaluate direct source-PDF page/viewport clipping through LaTeX `graphicx` or equivalent vector-preserving output rather than rasterizing web assets.

Preserve generated numbering and original source attribution; question and solution numbering should stay aligned.

## Performance

### Benchmark and optimise broad question searches

Origin: Question Retrieval Sprint v3 / final review.

Measure before optimizing:

- Subject/Unit/Topic-wide searches;
- SQLite query plans;
- current requested-node/question cross-product behaviour;
- per-question reconstruction/N+1 cost;
- deterministic ordering;
- mapped/direct equivalence.

Add indexes only when measurements justify them.

### Measure preview image conversion performance

Origin: Question Retrieval Sprint v3 / final review.

Measure large and multi-region previews. Investigate moving/restructuring image conversion only if visible FX-thread pauses are demonstrated.

### Measure overlap between superseded preview renders

Origin: Sprint 06 merge-readiness review.

Question preview cancellation deliberately does not interrupt PDFBox rendering because interruption can close a channel while PDFBox is still reading it.

The generation guard prevents a superseded preview from reaching the UI, but a new preview may begin before the superseded render has finished in the background.

Measure realistic rapid selection changes before deciding whether preview rendering needs serialisation or another bounded-work mechanism.

## Curriculum Mapping Hardening

### Decide whether mapping invariants need database enforcement

Origin: Curriculum mapping/retrieval hardening review.

Application writers enforce same Subject, different versions, same level and allowed direction.

Decide whether direct SQL bypass risk justifies triggers or other DB enforcement.

### Mapping workflow extensions

Possible later work:

- bulk mapping import;
- assisted review of gaps;
- improved similarity scoring;
- optional AI-assisted suggestions;
- whole-version coverage reporting.

Any assisted mechanism must still require explicit confirmation.

## UI / Usability

### Maintain a deliberate UI polish backlog

Origin: Exam Builder Design Slice.

Spacing, alignment, sizing, label wording and visual consistency should remain separate from functional work unless they block workflow or create incorrect data.

Sprint 07 promotes the capture-workspace issues that are workflow-critical. Other cosmetic polish remains here.

### Consider assisted PDF question-boundary detection

Origin: Batching Exam Questions.

Possible workflow:

- detect headings such as `QUESTION 12` using PDF text positions;
- estimate crop boundaries;
- present a suggested region;
- require user correction/confirmation.

Do not assume text extraction is reliable enough to replace visual source content.

### Consider assisted part/dependency recognition

Origin: Batching Exam Questions.

After Sprint 07 persists source-question identity, future tooling may suggest part relationships from question codes or phrases such as “using your answer to part a”.

Suggestions must remain non-authoritative until confirmed.

## Future Content Sources

### Clipboard / image-attachment questions

Origin: future image-snips discussion.

Support an eventual workflow such as:

```text
Windows Snipping Tool / copied image
        -> system clipboard
        -> Paste Image / Ctrl+V
        -> question content attachment
```

Design requirements:

- question content can be text plus zero or more images;
- optional drag/drop PNG/JPEG;
- choose SQLite BLOB versus application-managed file storage;
- define provenance metadata;
- include images in backup/restore;
- render consistently to HTML/PDF/SCORM;
- avoid separate mutually exclusive “text question” and “image question” hierarchies unless evidence requires it.

OCR is optional later work.

## Packaging / Deployment

### Self-contained desktop packaging

Origin: Batching Exam Questions.

Investigate `jpackage` once deployment becomes a priority.

Requirements:

- Maven-driven reproducible packaging;
- runtime/dependencies bundled appropriately;
- writable SQLite/data root outside installed application files;
- upgrade/migration/backup safety.

### Shared faculty operation

Origin: Batching Exam Questions.

Do not treat a live SQLite DB on SharePoint/network sync as a safe concurrently edited datastore.

Possible later models:

- local SQLite + controlled import/export/merge;
- SharePoint for backups, exports and distributed source files;
- IT-supported central database when simultaneous multi-user editing becomes necessary;
- SharePoint/Graph integration if justified.

## Testing / Reliability

### JavaFX TestFX suite isolation

Origin: Question Retrieval Sprint v3 / final review.

Continue focus-sensitive test isolation and repeatable clean-suite work.

### Question Search lifecycle

Retain focused tests for late hierarchy/search/preview completion, repeated disposal and detached-pane event handling.

### Add SQLite-backed revision export integration coverage

Origin: Sprint 05 merge-readiness review.

The revision-export integration test exercises complete PDF-region-to-HTML generation but uses an in-memory curriculum/retrieval fixture.

Add a temporary-SQLite end-to-end export test using fresh production repository/service instances to cover:

- persisted current/historical curriculum reconstruction;
- confirmed mapping retrieval;
- ordered question and answer regions;
- answer reconstruction;
- complete revision export after database reopen.

After Sprint 07, include source-question/shared-context reconstruction in this hardening path where appropriate.

Real Chemistry acceptance has already exercised the production path manually, so this remains hardening rather than a prior sprint blocker.

## Technical Debt

### Public API documentation

Origin: Question Retrieval Sprint v3 / final review.

Document:

- single-use/disposal behaviour of question search UI;
- Subject-search no-current/multiple-current semantics;
- hierarchy-expansion behaviour;
- historical applicability rejection and any strengthened invariants.

## Completed / Not Backlog

Do not re-add these as unimplemented work:

- Sprint 04 backup/restore/data safety;
- Sprint 05 deterministic revision corpus;
- static hierarchical revision HTML/assets;
- Subject/Unit/Topic/Subtopic revision navigation;
- empty-Descriptor suppression;
- static answer disclosure;
- revision-export staging and validation;
- JavaFX revision-export workflow and progress reporting;
- Sprint 06 SCORM 1.2 package generation;
- successful QLearn import/launch acceptance;
- current SCORM manifest/schema/ZIP profile;
- migrations through schema v4;
- syllabus selection;
- mapping persistence/review;
- curriculum-import idempotency;
- package reorganisation;
- legacy metadata import foundation;
- question/answer persistence;
- PDF-region capture;
- question retrieval Sprint 03;
- async hierarchy loading;
- stale-result protection;
- stored-question preview;
- arbitrary-PDF viewer mode;
- one-root managed-data configuration.

Do not mark Sprint 07 items complete until implementation and tests establish that state.

## Backlog Rules

- Include an origin for each item.
- Preserve completed sprint documents as history.
- When an item is scheduled into a sprint, reference it there and remove/mark it here.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage percentages.
- Do not implement unresolved ownership/semantics by inventing placeholder data.
- Do not promote chat prototypes or standalone mapping artifacts to application implementation without repository/persistence evidence.
