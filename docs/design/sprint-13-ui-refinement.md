# Sprint 13 — UI Refactoring, Search and Dashboard Refinement

> **Status:** PLANNED / READY TO START  
> **Planning reference:** 5 October 2026  
> **Predecessor:** Sprint 12 / Release 0.2, merged and verified  
> **Primary intent:** bounded, evidence-driven refinement after real-corpus use

## 1. Sprint objective

Sprint 13 is deliberately smaller than Sprint 12. It focuses on behaviour-preserving UI refactoring plus targeted Search and Dashboard refinements discovered through real-corpus use.

The sprint must not expand into another broad redesign. Each slice must remain independently testable, reviewable and suitable for a clean stopping point.

## 2. Final Sprint 13 scope

### Slice 1 — #93 Refactor UI classes where marked

Treat #93 as mandatory behaviour-preserving refactoring before further work in the affected UI classes.

The production UI audit found 12 real `// TODO` markers across three classes.

#### `QuestionBankApplication`

- split `initialiseCaptureWorkflow(...)` into smaller responsibility-focused composition/wiring methods;
- remove anonymous `Task` implementations from `loadCorpusDashboardHome(...)`, `startDashboardQuestionCaptureRefresh(...)`, `startLegacyQuestionCaptureRefresh(...)`, `startWorkingSubjectCaptureRefresh(...)`, and `startWorkingSubjectExamAssetsRefresh(...)`;
- also refactor the structurally identical anonymous `Task` in `startExamAssetsQuestionCaptureRefresh(...)` so no inconsistent anonymous Task implementation remains.

Recommended internal batches:

1. extract one focused callable-backed application background-task helper and replace all six anonymous Task bodies;
2. split `initialiseCaptureWorkflow(...)` into focused construction/wiring methods.

Preserve all stale-generation, lifecycle, failure and JavaFX-thread semantics.

#### `CorpusDashboardPane`

Refactor the marked methods without changing Dashboard semantics:

- `buildContent()` — separate major layout construction concerns;
- `configureActions()` — separate filter, selection, Question-work and command action wiring;
- `configureControls()` — separate curriculum, Exam/booklet and action-control presentation setup.

#### `CurriculumMappingReviewDialog`

Remove anonymous/inline cell implementations from:

- `configureReviewedMappingsList()`;
- `configureSourceNodeControls()`;
- `configureSuggestionList()`.

Use named focused cell types or factories while preserving current text, reviewed/current markers, checkbox state and selection behaviour.

### Slice 2 — #89 Question Search save/refresh defects

Real-corpus use exposed two related problems when editing classification in Questions → Search.

Current behaviour:

- Save persists the changed Descriptor correctly;
- Search unnecessarily blanks/rebuilds the visible result pane;
- when Search closes, the Corpus Dashboard can retain its old snapshot;
- consequently a booklet `No descriptor` count may remain non-zero until application restart even though the descriptors were saved.

Required outcome:

- persist classification changes correctly;
- avoid unnecessarily clearing/rebuilding the whole visible Search result pane;
- retain/reselect the edited Question;
- refresh or invalidate the Dashboard snapshot after a persisted Search classification change;
- ensure Dashboard `No descriptor` counts reflect saved classifications without restart;
- avoid unnecessary Dashboard refresh when Search closes with no persisted change;
- add a regression reproducing Search Save → close Search → updated Dashboard count.

### Slice 3 — #82 Question Search spacing/action layout

Improve Search presentation without redesigning retrieval:

- improve internal margins;
- regularise Edit / Split / Metadata / Shared Context / Answer action spacing;
- preserve current Search semantics and control behaviour.

### Slice 4 — #92 Inspect Questions from Dashboard

Add a Dashboard action that opens Question Search narrowed to the selected Exam or booklet.

Design this as a reusable Search scope/narrowing mechanism rather than a one-off Dashboard hack.

This slice should establish architecture that richer Search filtering can later reuse, but it must not implement the broader #94 filter set.

### Slice 5 — #86 Dashboard column alignment

Regularise Dashboard table alignment where appropriate.

This is presentation-only work. Do not alter audit values, filtering, selection or action semantics.

### Slice 6 — #87 Dashboard lifecycle progress/responsiveness

Improve `Mark Active` / `Mark Complete` workflow feedback.

Required outcome:

- persistence/refresh work must not block the JavaFX application thread;
- show visible busy/progress feedback;
- prevent duplicate lifecycle actions while the operation is active;
- clear progress on success or failure;
- preserve current readiness and lifecycle semantics;
- preserve valid Dashboard selection/context across refresh where possible.

### Slice 7 — #85 + #78 Working Subject consistency

Implement the two small remaining Working Subject consistency gaps together while keeping the GitHub issues separate.

#### #85 Mapping Review single-syllabus state

Before opening Curriculum Mapping Review:

- use the authoritative Working Subject;
- determine whether a second syllabus version exists for comparison;
- if not, do not construct/open a meaningless review dialog;
- present a clear unavailable/not-applicable message.

#### #78 Revision export Working Subject scope

Revision HTML and SCORM export must inherit the authoritative Working Subject rather than provide a competing Subject selection.

Preserve export eligibility, grouping, destination and unit-selection semantics.

## 3. Explicitly outside Sprint 13

### #83 — completed, retrospectively Sprint 11

Managed syllabus-PDF support already exists.

Current implementation:

- attaches a syllabus PDF from Curriculum Authoring;
- copies it beneath managed curriculum storage;
- stores a portable relative `source_pdf_path`;
- associates the path with `SyllabusVersion`, which already owns Subject/version/current state;
- resolves and reopens the managed PDF on later authoring sessions;
- supports replacement subject to curriculum lifecycle rules.

Ordinary File → Open already provides direct opening when a separate authoring workflow is unnecessary.

Issue #83 is complete and should not consume Sprint 13 work. Record it under Sprint 11 on the Project board.

### #27 — move with #94

Do not benchmark broad Search behaviour before richer retrieval filters exist.

Pair #27 with #94 in a later Search-focused sprint:

1. implement #94 richer retrieval-oriented Search filters;
2. benchmark the resulting real Search workload under #27;
3. use measurements to decide whether #25, #26, indexing or query redesign are justified.

### #38 — Sprint 14

Printable/vector-preserving assessment and solution output is too large for Sprint 13 and should remain separate.

When resumed, favour direct source-PDF composition/clipping and avoid unnecessary rasterisation.

### #95 — deferred evidence tracker

Capture-workflow productivity assistance remains deferred until sustained real-corpus use demonstrates repeated cost.

## 4. Search and Dashboard architectural boundary

Keep the established distinction:

```text
Dashboard
    operational work:
    what is incomplete/problematic and where to fix it

Search
    retrieval work:
    which Questions do I want to inspect or use
```

Dashboard-to-Search inspection may supply an initial retrieval scope, but Search must not become a duplicate Dashboard work queue.

## 5. Working Subject rule

The Corpus Dashboard owns the authoritative Working Subject.

Search, Exam/Assets, capture, curriculum workflows and Revision exports inherit it.

Secondary dialogs must not establish a competing application-level Subject context.

## 6. Testing expectations

For every slice:

- inspect the current branch and relevant tests before coding;
- add focused regressions for real production defects;
- preserve asynchronous stale-generation protection;
- keep persistence and slow PDF work off the JavaFX application thread;
- use deterministic semantic TestFX activation for ordinary controls;
- run only the tests needed for the current slice before broader checkpoints;
- require visual inspection when layout/presentation acceptance cannot be proven by tests alone.

#93 refactoring must be behaviour-preserving. Existing tests should remain green before and after each internal batch.

## 7. Project tracking

Recommended current Sprint 13 Project slice assignment:

| Slice | Issues | Purpose |
|---|---|---|
| 1 | #93 | UI refactoring |
| 2 | #89 | Search save/refresh defects |
| 3 | #82 | Search layout |
| 4 | #92 | Dashboard → scoped Search inspection |
| 5 | #86 | Dashboard alignment |
| 6 | #87 | Dashboard lifecycle progress |
| 7 | #85, #78 | Working Subject consistency |

Project fields remain manually managed.

Record #83 as completed under Sprint 11. Move #27 to the future sprint containing #94. Keep #38 in Sprint 14.
