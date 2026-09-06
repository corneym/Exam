# Exam Question Bank Backlog

> Authoritative deferred-work list at 6 September 2026.
>
> Completed sprint documents remain historical records. Items intentionally deferred from completed work belong here until scheduled into a future sprint.

## High Priority

### Stabilise and isolate JavaFX tests

Origin: Question Retrieval Sprint v3 / merge-readiness review.

The complete suite remains sensitive to mouse/focus in some TestFX paths.

Acceptance criteria:

- UI tests do not depend on the developer leaving the mouse untouched;
- UI tests can run separately from non-UI tests where useful;
- repeated clean execution is reliable;
- failures distinguish application defects from lost focus/window interaction.

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

### Build the region-capture-required work queue

Origin: Legacy Metadata Import follow-on work.

Identify questions requiring:

- question-region capture;
- answer-region capture;
- both;
- possible preamble/shared-context capture.

Filters:

- Subject;
- provider;
- year;
- booklet;
- completion state.

Completion must attach regions without changing imported historical metadata/classification.

### Reconcile the 5 September Chemistry 2019 -> 2025 mapping workbook

Origin: Curriculum Descriptor Mapping chat, 5 September 2026.

A standalone normalized mapping workbook now exists, but its integration with current SQLite mapping records is not established.

Required work:

- manually review all `YES` Low/no-match rows;
- check all `CHECK` Medium rows;
- confirm 2019 removed/no-direct-equivalent content;
- confirm 2025 new/no-direct-predecessor content;
- compare confirmed pairwise relationships with the application's current mapping records;
- import/reconcile confirmed mappings without overwriting historical question classifications;
- produce coverage statistics after reconciliation;
- retain audit notes/confidence where useful.

Do not treat the standalone workbook as authoritative application state until this reconciliation is complete.

## Normal Priority

### Decide whether `Question` requires multiple original classifications

Origin: Batching Exam Questions / early metadata design.

Evidence:

- the 2021 Neap classification exercise identified a question (Q11) reasonably mapped to three descriptors;
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

### Finish generalized preamble/shared-context and multipart semantics

Origin: Batching Exam Questions, legacy `MultiPartQuestion`, Legacy Metadata Import follow-on work.

This is broader than the current `preambleCaptureRequired` hint.

Decide how to represent shared stems/tables/graphs/diagrams used by several questions or parts, including possibilities such as:

- repeated ordinary regions attached to each question;
- a separately persisted shared source section;
- ordered shared content blocks;
- question/part dependency relationships;
- keep-together output rules.

Constraints:

- source-document provenance;
- ordering;
- independent classification of parts where required;
- no guessed preamble grouping during legacy import;
- no persisted `pinned` flag merely to support capture UI.

After persistence semantics are settled, consider a capture-time “pin/reuse selected region” convenience.

### Add import audit and reconciliation reporting

Origin: Legacy Metadata Import follow-on work.

Report:

- records created;
- records already identical;
- conflicts rejected;
- missing PDFs/booklets;
- questions still needing regions;
- answers supplied/absent/unknown;
- unresolved classifications.

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
- shared/preamble groups spanning rows;
- renamed/relocated source PDFs.

Do not generalise speculatively before real files demonstrate the need.

### Decide whether legacy image-snip import/support is still required

Origin: Batching Exam Questions.

The legacy application has many named question/answer snips. The current redesign correctly prefers original PDFs + regions.

Decide whether any irreplaceable legacy items exist only as image snips and therefore need:

- one-time attachment import;
- managed-file compatibility;
- or no special support because original PDFs are available.

Do not convert all legacy snips merely for architectural purity.

### Decide whether richer curriculum mapping relation metadata should be persisted

Origin: Curriculum Descriptor Mapping.

The standalone mapping work distinguishes conceptual cases such as direct/reworded, split, consolidated/merged, partial, removed and new content.

Current retrieval mainly needs confirmed directional pairs.

Decide whether relation type/confidence/notes should remain external review/audit metadata or become persisted application data for reporting and assisted review.

## Output / Future Sprint Inputs

### Sprint 06 — SCORM packaging rules

Origin: Batching Exam Questions + current development roadmap.

Retain:

- static generated assets with ordinary relative paths;
- full source exam PDFs should not normally be included in the package;
- application generates the final ZIP;
- manifest/resources must match actual contents;
- validate against the real QLearn-supported profile.

### Later printable output — preserve vector source content

Origin: Batching Exam Questions / legacy output behaviour.

For print-oriented PDF generation, evaluate direct source-PDF page/viewport clipping through LaTeX `graphicx` or equivalent vector-preserving output rather than rasterizing the web assets.

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

## Curriculum Mapping Hardening

### Decide whether mapping invariants need database enforcement

Origin: Curriculum mapping/retrieval hardening review.

Application writers enforce same Subject, different versions, same level and allowed direction. Decide whether direct SQL bypass risk justifies triggers/other DB enforcement.

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

Spacing, alignment, sizing, label wording and visual consistency were deliberately deferred so they do not stall persistence/export work.

Promote a UI issue above polish only when it:

- blocks the capture workflow;
- causes incorrect data;
- makes an essential control unusable.

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

Suggest part labels and phrases such as “using your answer to part a”, but retain manual override because semantic dependency cannot be inferred reliably in all questions.

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
- avoid creating separate mutually exclusive “text question” and “image question” hierarchies unless evidence later requires it.

OCR is optional later work, not a prerequisite.

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

- Sprint 05 deterministic revision corpus;
- static hierarchical revision HTML/assets;
- Subject/Unit/Topic/Subtopic revision navigation;
- empty-Descriptor suppression;
- static answer disclosure;
- revision-export staging and validation;
- JavaFX revision-export workflow and progress reporting;
- migrations through the current implemented schema;
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

## Backlog Rules

- Include an origin for each item.
- Preserve completed sprint documents as history.
- When an item is scheduled into a sprint, reference it there and remove/mark it here.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage percentages.
- Do not implement unresolved ownership/semantics by inventing placeholder data.
- Do not promote chat prototypes or standalone mapping artifacts to application implementation without repository/persistence evidence.
