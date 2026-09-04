# Exam Question Bank Backlog

This document is the authoritative backlog for work deliberately deferred from completed sprints.

Sprint design documents should remain historical records of what was planned and completed. Items that are intentionally postponed should be recorded here rather than extending a completed sprint.

---

## High Priority

_No current items._

---

## Normal Priority

### Question Retrieval follow-up

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

- Strengthen `QuestionApplicabilityMatch` invariants so a current applicability node must be at the same curriculum level as the question's original classification. This should prevent accidental invention of Descriptor precision from Subtopic-classified questions.
- Strengthen `QuestionRetrievalResult` invariants so directly constructed results cannot contain invalid applicability nodes such as Unit or Topic nodes, historical nodes, or nodes from another subject.
- Add explicit stale-completion tests for question-search and question-preview tasks.
- Add UI coverage for Subject selection when:
  - no current syllabus exists;
  - more than one current syllabus exists.
- Add broader SQLite integration coverage for:
  - Subject retrieval across multiple Units;
  - one-to-many historical Subtopic mappings;
  - same-subject isolation.

---

## Performance

### Question Retrieval

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

- Benchmark Subject-wide SQLite retrieval with realistic curriculum and question-bank sizes before changing the current query design.
- If measurements show a problem, investigate replacing the requested-node/question cross-product and per-question `findById()` reconstruction with a more set-oriented retrieval path.
- Inspect query plans before adding indexes. Add indexes only when measurements justify them.
- Measure JavaFX preview conversion performance for large or multi-region question images before moving `SwingFXUtils.toFXImage()` away from the JavaFX application thread or otherwise optimising preview conversion.

---

## Testing / Reliability

### JavaFX TestFX

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

- Investigate the focus-sensitive `JavaFxToolchainSmokeTest` failure that can occur during the complete suite while passing immediately in isolation.
- Keep this separate from retrieval feature work unless evidence shows the flake is caused by retrieval UI lifecycle changes.

### Question Search lifecycle

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

- Add stronger disposal tests that deliberately hold hierarchy, search and preview work in flight, dispose the dialog, then release each task and verify that no late completion updates the detached pane.
- Add a hierarchy-failure regression proving that results, details and preview from the previous scope are cleared when a new hierarchy lookup fails.
- Add UI coverage for zero-region legacy questions displaying `No stored question image.`
- Add UI coverage for missing or corrupt source PDFs displaying the controlled preview-unavailable message.

---

## Technical Debt

### Public API documentation

Origin: Question Retrieval Sprint v3 / final Codex merge-readiness review.

- Document in `QuestionSearchDialog` that hiding the dialog disposes its search pane and the dialog is therefore effectively single-use.
- Document Subject-search behaviour explicitly in `QuestionRetrievalService`:
  - no current syllabus returns an empty result;
  - multiple current syllabus versions are invalid and cause failure.
- Document equivalent Subject-expansion behaviour in `CurriculumSearchNodeExpansionService`.
- Update `QuestionRetrievalResult` Javadoc to state that historical applicability nodes are rejected.

---

## Future Features

_No items recorded here yet._

---

## Backlog Rules

- Add an origin for each item so its context can be traced.
- Do not move unfinished work back into a completed sprint document.
- When an item becomes part of a new sprint, reference the backlog item from that sprint and remove or mark the backlog entry as scheduled.
- Keep performance work measurement-driven.
- Prefer behaviour-focused tests over coverage-percentage targets.
