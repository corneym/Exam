# Exam Question Bank Backlog

This document is the authoritative backlog for work deliberately deferred from completed sprints.

Sprint design documents should remain historical records of what was planned and completed. Items that are intentionally postponed should be recorded here rather than extending a completed sprint.

---

## High Priority

### Stabilise and isolate JavaFX tests

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

The complete suite remains sensitive to mouse/focus, particularly `JavaFxToolchainSmokeTest`.

Acceptance criteria:

- TestFX tests do not depend on the developer leaving the mouse untouched.
- UI tests can run separately from the non-UI suite.
- CI/full-suite execution is repeatable.
- A failure reliably indicates an application defect rather than lost window focus.

### Complete asynchronous question-search regression coverage

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

Production lifecycle protection is implemented, but several asynchronous paths need stronger regression coverage.

Add tests for:

- stale question-search completion;
- stale preview completion;
- hierarchy failure clearing previous results, details and preview;
- disposal while hierarchy, search and preview operations are in flight;
- repeated/idempotent disposal;
- no-current-syllabus Subject navigation;
- multiple-current-syllabus failure handling;
- zero-region legacy questions displaying `No stored question image.`;
- missing or corrupt source PDFs displaying the controlled preview-unavailable message.

### Build the region-capture-required work queue

Origin: Legacy metadata import follow-on work.

Create a workflow for imported questions that still require source-region capture.

The queue should identify questions requiring:

- question-region capture;
- answer-region capture;
- both;
- possible preamble capture.

Useful filters should include:

- Subject;
- provider;
- year;
- booklet;
- completion state.

Attaching the required regions should remove the item from the relevant queue without altering imported metadata or historical classification.

---

## Normal Priority

### Strengthen retrieval-domain invariants

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

Review and strengthen construction rules for:

- `QuestionApplicabilityMatch`;
- `QuestionRetrievalResult`.

Decide and enforce:

- original and current classification must have compatible curriculum levels;
- applicability nodes must belong to the question's Subject;
- applicability may contain only Subtopic or Descriptor nodes;
- duplicate applicability nodes should either be rejected or deliberately normalised;
- directly constructed results must not admit historical applicability nodes, Unit nodes or Topic nodes.

The current SQLite retrieval path already protects most normal production flows, so this is API hardening rather than a current correctness blocker.

### Extend retrieval integration coverage

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

Add realistic SQLite integration tests for:

- Subject-wide retrieval spanning multiple Units;
- one historical Subtopic mapping to multiple current Subtopics;
- strict same-Subject isolation;
- empty Subtopics as valid retrieval scopes;
- questions directly classified under Topic-level Descriptors;
- reconstruction after closing and reopening the database.

### Finish preamble capture semantics

Origin: Legacy metadata import follow-on work.

This requires a short design decision before implementation.

Determine whether a preamble should be represented as:

- an ordered region associated with several questions;
- a separately persisted shared source section;
- repeated regions attached to each affected question.

Do not implement this until ownership, ordering and document-generation behaviour are settled.

### Add import audit and reconciliation reporting

Origin: Legacy metadata import follow-on work.

Provide an administrative report for repeated or batch imports showing:

- records created;
- records already identical;
- conflicts rejected;
- missing PDFs/booklets;
- questions still needing regions;
- answers supplied or absent;
- classifications that could not be resolved.

A later dry-run mode could use the same validation without changing the database.

### Validate additional real-world legacy workbook variants

Origin: Legacy metadata import follow-on work.

As representative workbooks become available, test:

- multiple Subjects and providers;
- unexpected workbook formatting;
- blank or formula-driven cells;
- duplicate rows with conflicting metadata;
- unusual question codes or part-question formats;
- missing MCQ answers;
- preamble groups spanning multiple rows;
- renamed or relocated source PDFs.

Do not generalise the importer speculatively before representative workbooks are available.

---

## Performance

### Benchmark and optimise broad question searches

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

`SqliteQuestionRepository.findApplicableToNodes()` currently performs a broad requested-node/question operation followed by per-question reconstruction.

Work should include:

- generate a representative large database;
- benchmark Subject-, Unit- and Topic-wide searches;
- inspect the SQLite query plan;
- measure the current cross-product behaviour;
- measure the per-question `findById()` reconstruction cost;
- eliminate N+1 reconstruction only if measurements justify it;
- preserve deterministic ordering;
- preserve original classifications;
- verify mapped and directly current classifications still produce identical results.

Do not add indexes until query-plan evidence or realistic benchmarks justify them.

### Measure preview image conversion performance

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

PDF rendering already occurs away from the JavaFX application thread, but `BufferedImage` to JavaFX `Image` conversion occurs on the FX thread.

Measure large and multi-region previews.

If visible pauses are demonstrated, investigate moving or restructuring conversion work without breaking JavaFX thread-safety.

---

## Curriculum Mapping

### Decide whether mapping invariants need database enforcement

Origin: Curriculum mapping/retrieval hardening review.

The writer currently enforces:

- same Subject;
- different syllabus versions;
- same curriculum level;
- allowed mapping direction.

Direct SQL access could bypass some of these rules because foreign keys alone do not express every invariant.

Decide whether application-level enforcement is sufficient or whether a later migration should introduce triggers or another database-level mechanism.

This is hardening, not a current writer defect.

### Mapping workflow extensions

Origin: Earlier curriculum-mapping work deliberately deferred.

Possible later features include:

- bulk mapping import;
- assisted review of mapping gaps;
- improved similarity scoring beyond the current TF-IDF implementation;
- optional AI-assisted suggestions;
- mapping coverage/reporting across whole syllabus versions.

Any assisted mechanism must continue to require explicit confirmation. Rank or confidence must never automatically create a confirmed mapping.

---

## Testing / Reliability

### JavaFX TestFX suite isolation

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

As part of stabilising JavaFX tests:

- investigate focus-sensitive `JavaFxToolchainSmokeTest` behaviour;
- separate UI tests from non-UI tests where useful;
- ensure repeatable CI/full-suite execution;
- keep TestFX instability separate from retrieval feature work unless evidence shows a real feature regression.

### Question Search lifecycle

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

Beyond the high-priority asynchronous coverage, retain focused lifecycle hardening for:

- disposal while work is deliberately held in flight;
- late hierarchy completion after disposal;
- late question-search completion after disposal;
- late preview completion after disposal;
- hierarchy failure after changing scope;
- repeated disposal;
- detached-pane event handling.

---

## Technical Debt

### Public API documentation

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

- Document in `QuestionSearchDialog` that hiding the dialog disposes its search pane and the dialog is effectively single-use.
- Document Subject-search behaviour explicitly in `QuestionRetrievalService`:
  - no current syllabus returns an empty result;
  - multiple current syllabus versions are invalid and cause failure.
- Document equivalent Subject-expansion behaviour in `CurriculumSearchNodeExpansionService`.
- Update `QuestionRetrievalResult` Javadoc to state that historical applicability nodes are rejected.
- Update documentation again if applicability invariants are strengthened.

---

## Future Features

### Assessment assembly

Later product work should include:

- selecting and assembling questions into a new assessment;
- reproducible question ordering;
- question/section ordering controls;
- answer/marking-material inclusion options.

### Output generation

Later output work should include:

- HTML generation;
- PDF generation;
- reproducible pagination;
- source attribution;
- copyright-safe output handling.

### Operational maintenance

Later operational work should include:

- backup and restore;
- database maintenance beyond the existing development reset tool;
- administrative diagnostics where justified.

---

## Completed / Not Backlog

The following are intentionally not backlog items because they are already completed or substantially addressed:

- schema migrations through version 3;
- exact v3 structural validation;
- syllabus selection;
- mapping persistence and review;
- atomic review editing;
- curriculum-import idempotency;
- package reorganisation;
- legacy metadata import foundations;
- database reset tooling;
- Question Retrieval Sprint v3 core retrieval implementation;
- asynchronous curriculum hierarchy loading;
- question-search lifecycle disposal;
- stale hierarchy protection;
- stored question preview;
- the two merge blockers identified during the Question Retrieval Sprint review.

---

## Backlog Rules

- Add an origin for each item so its context can be traced.
- Do not move unfinished work back into a completed sprint document.
- When an item becomes part of a new sprint, reference the backlog item from that sprint and remove or mark the backlog entry as scheduled.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage-percentage targets.
- Do not implement design-decision items until the underlying ownership and semantics are agreed.
