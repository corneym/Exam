# Sprint 07 — Preamble-aware Question Capture and UI Redesign

> **Status:** IMPLEMENTED — feature branch closeout  
> **Feature branch:** `feature/preamble-capture`  
> **Status date:** 10 September 2026  
> **Repository:** `corneym/Exam`

## 1. Sprint objective

Sprint 07 establishes explicit source-question and shared-context semantics, makes question and answer capture safer and more usable, and carries those semantics through revision HTML and SCORM output.

The sprint deliberately does not redesign curriculum import, mapping, applicability, retrieval, backup/restore, the SCORM package profile, printable output, or the unresolved multiple-original-classification question.

## 2. Final implemented domain model

Sprint 07 distinguishes three separate concepts.

### Question

A `Question` remains one independently classified and marked question or question part. It retains its own question code, marks, classification, ordered ordinary question regions and optional answer.

### SourceQuestion

A `SourceQuestion` represents common source identity in the original examination, for example source question `21` containing `21a`, `21b` and `21c`.

Membership is persisted explicitly. Multipart identity is not reconstructed on every read merely by stripping suffixes from question codes.

### SharedQuestionContext

A `SharedQuestionContext` represents material required by one or more Questions, such as a common stem, preamble, table, graph or diagram.

It is persisted separately from ordinary Question regions and owns one or more ordered `SharedQuestionContextRegion` records. Shared-context identity is independent of multipart/source-question identity: unrelated questions may share context without becoming one multipart question.

Sprint 07 supports zero or one linked shared context per Question. One context may be reused by many Questions.

## 3. Database schema

Sprint 07 advances the database through schema versions 5 and 6.

### Schema v5

Added:

- `source_questions`;
- `shared_question_contexts`;
- `shared_question_context_regions`;
- nullable source-question relationship on `questions`;
- nullable shared-context relationship on `questions`.

Migration from v4 preserves existing data and does not infer source-question or shared-context relationships from question codes or legacy flags.

### Schema v6

`source_questions` gains persisted `preamble_status` with values:

```text
UNKNOWN
NONE
PRESENT
```

Existing rows migrate to `UNKNOWN`.

The latest supported schema version is therefore 6.

## 4. Legacy preamble semantics

The imported legacy `preambleCaptureRequired` value remains historical evidence and is not rewritten when later capture resolves the requirement.

Legacy import may conservatively derive/reuse `SourceQuestion` identity where the capture rule establishes it, but must not manufacture a `SharedQuestionContext` from metadata alone.

A required preamble remains unresolved until actual shared-context source material has been captured and linked.

Sprint 07 provides relationship-only resolution for imported Questions that already have ordinary regions, so shared-context resolution does not require replacing valid question content.

## 5. Question capture workflow

Implemented behaviour includes:

- normal multi-region Question capture;
- persisted SourceQuestion relationships;
- shared-context/preamble capture and reuse;
- conservative multipart source-code derivation;
- explicit first-region-as-shared-preamble workflow;
- reuse of an already drawn selection when multipart/preamble intent is selected;
- clear unresolved-preamble status for legacy records;
- separate ordinary Question and shared-context region ownership;
- stable Question number and marks editing while ordinary regions are accumulated;
- accepted-region removal and preview;
- same-page anchored selection for large preamble regions;
- immediate Save-state recalculation from capture/classification state;
- background Question save work so repository I/O does not block the JavaFX thread.

Automatic imported-question preamble capture currently completes after one accepted source region. Multi-page automatic preamble capture is intentionally deferred to the backlog. The persistence model already supports multiple ordered shared-context regions.

## 6. Question editing and correction

Persisted Questions can be reopened from Question Search for correction.

Supported correction includes:

- question code;
- marks;
- syllabus-sensitive classification;
- ordered ordinary Question regions;
- source-question relationship;
- shared-context relationship.

Exam/booklet ownership remains immutable in this workflow. Existing Question identity is retained, so a linked Answer remains attached.

Question editing uses transactional persistence for the related Question save state rather than leaving partially applied source/shared-context changes if the final Question update fails.

No Question deletion workflow is introduced by Sprint 07.

## 7. Answer capture and editing

The Answer pane is an unanswered-question work queue. It does not need a second mode that also lists answered Questions.

Existing Answers are reached through Question Search and opened for editing. This supersedes the earlier design wording that said the Answer selector itself should support both answered and unanswered Questions.

Implemented behaviour includes:

- selected Question and marks display;
- written-response region capture;
- multiple ordered Answer regions;
- accepted-region previews and removal;
- A/B/C/D multiple-choice entry for recognised MCQ booklets;
- existing Answer correction while retaining Question identity;
- background Answer persistence;
- asynchronous post-save loading of the next Answer PDF;
- automatic reuse of a registered Answer/marking-guide PDF for other Questions from the same Exam;
- same-selection document activation when the selector is reopened;
- `Choose PDF...` controls shown only when a usable registered Answer PDF is not already known;
- fallback PDF selection remains available when no registered marking guide exists.

The current MCQ detection based on booklet naming is transitional. Persisted Question-level response type is deferred.

## 8. Legacy Answer-PDF completion

Legacy metadata import now permits optional registration of an Answer/marking-guide PDF even when all required Question booklets already exist.

This closes a convenience gap in the legacy workflow. It does not remove the existing Answer-pane PDF chooser fallback.

## 9. Selection ownership and accidental-loss protection

The shared PDF workspace has explicit transient selection owners:

```text
QUESTION
SHARED_CONTEXT
ANSWER
```

Only the owning capture workflow may clear its selection.

Transitions that could discard an unaccepted current rectangle are guarded. Accepted regions remain available across normal page navigation so multi-region capture remains possible.

Changing capture/document context clears or protects transient state according to ownership rather than allowing unrelated controls to interfere with it.

## 10. Capture workspace

The fixed narrow capture area has been replaced by a resizable horizontal workspace with a vertically scrollable capture side.

The UI retains the logical sections:

```text
Classification
Question
Answer
```

The additional width is used for controls and previews rather than permanent blank spacing.

Remaining cosmetic Answer-pane layout issues are tracked in `docs/design/backlog.md` rather than reopening Sprint 07 architecture.

## 11. Syllabus-sensitive classification

Question classification follows the hierarchy actually present in the selected syllabus branch.

Supported examples include:

```text
Unit -> Topic -> Descriptor
```

and:

```text
Unit -> Topic -> Subtopic -> Descriptor
```

Implemented rules include:

- show only hierarchy levels that actually exist in the selected branch;
- Descriptor selection is available where applicable;
- child selections are filtered by parent selections;
- invalid descendant selections are cleared when a parent changes;
- a Question may stop at Subtopic even when Descriptor children exist;
- a Topic with direct Descriptor children is not a valid final classification by itself;
- progressive classification-code entry synchronises with hierarchy controls;
- invalid/impossible code extensions are rejected;
- editing reconstructs the persisted syllabus/classification path.

This changes capture and validation only. It does not alter curriculum import, mapping, applicability or retrieval semantics.

## 12. Revision presentation semantics

Sprint 07 adds an explicit presentation/grouping boundary after normal current-curriculum retrieval has determined the final output bucket.

Within one final curriculum bucket:

- Questions sharing one `SourceQuestion` may be presented as one multipart revision question;
- member order is deterministic;
- grouped marks are derived by summing the included member marks;
- shared context is rendered once where appropriate;
- Questions in different final curriculum buckets remain separate;
- independent Questions sharing only a `SharedQuestionContext` remain independent Questions;
- inconsistent source/shared-context relationships are not guessed silently.

Shared context has its own revision asset handling rather than being implicitly folded into ordinary Question regions.

## 13. HTML and SCORM

Revision HTML consumes the Sprint 07 presentation semantics and renders shared context/multipart content accordingly while preserving existing navigation, provenance and answer-disclosure behaviour.

SCORM remains a packaging layer over the static revision site. Sprint 07 does not introduce a second SCORM-specific grouping model or change the established SCORM 1.2 profile, manifest structure, launch target or ZIP mechanics.

## 14. PDF loading and responsiveness

Question and Answer persistence no longer perform the relevant long-running repository work on the JavaFX application thread.

Post-save Answer PDF loading is asynchronous. The UI remains available while the next marking guide is opened, and a committed Answer remains saved even if the subsequent PDF load fails.

The PDF workspace tracks stale/cancelled document requests so outdated asynchronous loads do not overwrite a newer user selection.

## 15. Test and acceptance state

At Sprint 07 closeout the following have been reported green:

- standard Maven test suite;
- full headless TestFX UI suite;
- Javadoc/doclint with private members included.

Manual acceptance has confirmed, among other cases:

- large same-page preamble selection;
- required-preamble warning behaviour;
- shared preamble reuse by later parts;
- Question Search status clearing;
- Question editing preserving Answer association;
- Answer editing preserving Question association;
- MCQ answer selection;
- ordinary written-answer region capture;
- stable Answer-region preview sizing;
- responsive Question and Answer saves;
- registered marking-guide reuse;
- hiding the Answer PDF chooser when the selected Question already has a usable Answer PDF.

The branch should still receive the normal final branch-versus-`main` merge-readiness review before merge.

## 16. Deferred work explicitly not part of Sprint 07 completion

The following remain in `docs/design/backlog.md`:

- multi-page automatic shared-preamble capture;
- shared-context inclusion in Search Questions preview;
- Answer-pane layout annoyances;
- Save-button ready-state regression after selecting then clearing a new pending region;
- Question-level response type;
- MCQ explanation-region capture;
- broad capture/audit queue;
- additional JavaFX/TestFX and async-search hardening;
- multiple-original-classification decision;
- question-level applicability exceptions after curriculum mapping;
- exam-specific metadata correction beyond the current Question/Answer edit workflow;
- later printable output, image/clipboard content, packaging and multi-user work.

## 17. Superseded Sprint 07 design statements

The following earlier design statements are no longer current:

- Sprint 07 is not “design complete / implementation not started”; implementation is complete on the feature branch and is in closeout.
- The Answer selector does not need to list both answered and unanswered Questions; unanswered capture is handled in the Answer queue and existing Answer editing is launched through Question Search.
- Automatic imported preamble capture does not yet support a multi-page sequence even though the persistence model permits multiple ordered context regions; that UI extension is deferred.
- Earlier cleanup-review gaps concerning shared-context revision output and atomic Question capture were interim findings and were addressed by later Sprint 07 implementation.

## 18. Documentation consolidation

This file is the canonical Sprint 07 design and final implementation record.

The temporary working documents:

- `docs/design/sprint-07-status-and-backlog-notes.md`;
- `docs/sprint07-cleanup-review.md`;

were useful during implementation but should be retired after their final evidence has been incorporated here, `docs/current-status.md`, `docs/project-history.md` and `docs/design/backlog.md`.

Future Sprint 07 corrections should update this canonical file rather than recreate parallel status documents.
