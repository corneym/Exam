# Sprint 13 — UI Refactoring, Search and Dashboard Refinement

> **Status:** COMPLETE / MERGED  
> **Planning reference:** 5 October 2026  
> **Merged:** 7 October 2026  
> **Pull request:** #107  
> **Merge commit:** `f497f1844c1eebf47f25ebe9e49454b965459f61`  
> **Predecessor:** Sprint 12 / Release 0.2  
> **Primary intent:** bounded, evidence-driven refinement after real-corpus use

## 1. Sprint objective and final result

Sprint 13 was deliberately smaller than Sprint 12. It focused on behaviour-preserving UI refactoring plus targeted Search, Dashboard and Working Subject refinements discovered through real-corpus use.

The original seven slices were completed. Two additional user-visible workflow issues (#97 and #98) were also implemented and verified before closeout.

Sprint 13 did not change the SQLite schema. The current published application/release version remained 0.2.

## 2. Final implemented scope

### Slice 1 — #93 Refactor UI classes where marked

Completed as behaviour-preserving refactoring.

#### `QuestionBankApplication`

- replaced the targeted anonymous background `Task` implementations with one focused callable-backed application task helper;
- included the structurally identical unmarked task site so no inconsistent anonymous implementation remained;
- preserved thread naming, callbacks, stale-generation protection, lifecycle/disposal checks and error handling;
- split `initialiseCaptureWorkflow(...)` into smaller responsibility-focused construction/wiring methods.

#### `CorpusDashboardPane`

Decomposed the marked large methods:

- `buildContent()`;
- `configureActions()`;
- `configureControls()`.

The refactor preserved Dashboard layout semantics, audit values, filters, selection, lifecycle actions and routing.

#### `CurriculumMappingReviewDialog`

Replaced inline/anonymous cell implementations with named focused cell types while preserving:

- reviewed/current markers;
- suggestion text;
- checkbox state;
- target selection behaviour.

The final PR review identified two extracted cell classes that did not use their enclosing dialog. `ReviewedMappingCell` and `SourceDescriptorButtonCell` were made static before merge.

### Slice 2 — #89 Question Search save/refresh defects

The real-corpus defect was reproduced and fixed.

Final behaviour:

- inline Descriptor Save persists correctly;
- Search no longer blanks the visible results while the post-save background refresh runs;
- the edited Question remains/re-becomes selected;
- the persisted Descriptor is shown immediately;
- Search records whether a real classification persistence change occurred;
- closing Search after a persisted change refreshes the Corpus Dashboard;
- Dashboard `No descriptor` counts update without application restart;
- closing an unchanged Search session does not trigger unnecessary Dashboard refresh work.

Manual real-corpus verification confirmed the booklet `No descriptors` count reduced immediately after returning from Search.

### Slice 3 — #82 Question Search spacing/action layout

Question Search presentation was regularised without changing retrieval semantics.

Final layout:

- the five Question-edit actions use the DialogPane button row;
- action buttons are uniformly sized and grouped consistently;
- Close remains the Dialog close action;
- left/right Search columns have consistent internal padding;
- Matching Questions and Question Details no longer sit hard against their SplitPane edges;
- existing IDs, tooltips, enablement, dirty-classification guard and action targets were preserved.

Manual visual verification was accepted.

### Slice 4 — #92 Inspect Questions from Dashboard

Dashboard now provides `Inspect Questions` at both selected Exam and selected Question-booklet scope.

The implementation introduced reusable Search narrowing rather than a Dashboard-only filter hack.

Final behaviour:

- Exam-level inspection supplies the selected Exam scope;
- booklet-level inspection supplies the selected booklet scope;
- Search displays the narrowing as immutable visible context;
- the scope persists across Current Syllabus / All Questions and post-edit refresh;
- COMPLETE Exams remain inspectable;
- returning from Search preserves the Dashboard workflow;
- Search remains retrieval UI rather than a duplicate Dashboard work queue.

This mechanism is the intended foundation for later richer Search filtering under #94.

Maintained Help was updated and its regression was green before closeout.

### Slice 5 — #86 Dashboard column alignment

Presentation-only change completed.

Displayed data cells are centred consistently across:

- Exams;
- Question Booklets;
- Question Work.

Audit values, ordering, sorting, filtering, selection and action semantics were not changed.

Manual visual verification passed.

### Slice 6 — #87 Dashboard lifecycle progress/responsiveness

Exam lifecycle persistence plus the authoritative Dashboard reload now run off the JavaFX application thread.

Final behaviour:

- visible compact busy/progress state during Mark Active / Mark Complete;
- duplicate lifecycle actions disabled while busy;
- busy state remains active across persistence and Dashboard reload;
- busy state clears on success and failure;
- JavaFX remains responsive under contention;
- lifecycle persistence semantics and completion-readiness rules are unchanged;
- Dashboard selection/context and retained active-booklet lifecycle state remain synchronised.

Focused responsiveness and failure-path regressions were green. Manual verification confirmed the progress indicator is visible.

### Slice 7 — #85 + #78 Working Subject consistency

#### #85 Mapping Review single-syllabus state

Curriculum → Review Mappings now:

- uses the authoritative Working Subject;
- checks persisted syllabus-version count before constructing the review dialog;
- does not open Mapping Review when fewer than two syllabus versions exist;
- shows a clear unavailable/not-applicable message instead.

Focused workflow coverage and manual verification passed.

#### #78 Revision export Working Subject scope

Revision HTML and SCORM export now inherit the authoritative Working Subject.

Final behaviour:

- no competing Subject selector in either export dialog;
- the Working Subject is displayed as read-only context;
- Unit selection, grouping and destination semantics are preserved;
- the application rejects export cleanly before dialog construction when the Working Subject has no single current syllabus;
- no-current-syllabus handling no longer falls through to an uncaught revision-corpus construction failure.

Dialog, application-level and end-to-end export regressions were green. Manual visual verification passed.

## 3. Additional Sprint 13 work

### #97 Curriculum workbook onboarding

Blank-slate onboarding exposed a managed-file usability problem: a curriculum Excel workbook previously needed to be placed under application data manually.

Sprint 13 changed this so that:

- the user may select a curriculum workbook from anywhere;
- the application retains a managed copy beneath the configured curriculum data area;
- import uses the managed copy;
- the user does not need to pre-position the file manually.

This was intentionally a narrow transitional change. The broader Subject-first managed-data redesign was deferred to Sprint 14.

Automated tests and manual blank-slate onboarding verification passed.

### #98 Automatic restart after data-root change or restore

A shared restart capability was added for operations that deliberately invalidate the running application's in-memory data context.

The implementation reuses the existing shutdown/resource-close coordination rather than creating a second shutdown path.

#### Options

After a different data root is validated and saved:

- the user is offered `Restart Now` or `Exit`;
- Restart Now performs the normal shutdown path and relaunches the packaged application;
- Exit performs the normal shutdown path without relaunch;
- no restart prompt is shown when the data root is unchanged.

#### Restore

After a successful restore:

- restored database/managed data is published and validated first;
- the same restart capability may relaunch the packaged application;
- restore failure/rollback paths do not blindly restart;
- shutdown safety remains authoritative.

Development/Eclipse execution fails safely when there is no supported packaged launcher rather than guessing an IDE command line.

Focused automated tests passed. Manual verification passed in both:

- a generated jpackage application image;
- an installed MSI build at version 0.2.

Both data-root change and database restore successfully restarted the packaged application.

## 4. Final architecture rules confirmed by Sprint 13

### Dashboard vs Search

```text
Dashboard
    operational work:
    what is incomplete/problematic and where to fix it

Search
    retrieval work:
    which Questions do I want to inspect or use
```

Dashboard may supply immutable initial Search narrowing, but Search remains the retrieval surface.

### Working Subject

The Corpus Dashboard owns the authoritative Working Subject.

Exam/Assets, capture, Search, curriculum workflows and Revision export inherit it. Secondary dialogs must not establish a competing application-level Subject context.

### JavaFX responsiveness

Persistence, refresh and slow PDF work stay off the JavaFX application thread.

Asynchronous results retain stale-generation/lifecycle protection, and UI busy state covers the whole user-visible operation rather than only one internal phase.

### Managed files

SQLite remains authoritative.

Managed curriculum workbooks, syllabus PDFs and Exam sources are source assets referenced by the authoritative database. Sprint 13 #97 improved workbook intake without attempting the broader filesystem redesign.

### Restart semantics

Data-root change and successful restore use the same packaged-application restart boundary and the normal shutdown/resource-close path.

## 5. Headless UI regression restructuring

Sprint 13 closeout exposed a test-infrastructure problem on a memory-constrained local machine.

A monolithic `UITests` headless run accumulated enough JavaFX/TestFX/PDF state to exhaust the heap even after increasing the maximum heap. Running every test class in its own fresh JVM eliminated accumulation but introduced excessive startup cost.

The retained solution mirrors the existing CI logical partitions and runs the headless regression through four bounded suite wrappers:

```text
FastUITests
WorkflowApplicationTests
WorkflowCaptureTests
WorkflowStateEditingTests
```

The headless profile:

- uses `-Xmx4g`;
- selects the four suite wrappers by default;
- uses one fork at a time;
- does not reuse a fork between top-level suite wrappers.

This gives each suite group a fresh JVM without paying a JVM startup cost for every TestFX class.

Two workflow classes that had been tagged `workflow-ui` but were not included in the three workflow wrapper patterns were also brought into explicit groups:

- `CorpusDashboardWorkflowTest` → application group;
- `LegacyClipboardCaptureWorkflowIntegrationTest` → capture group.

The normal full local command remains:

```text
.\mvnw.cmd -Pheadless-ui-tests test
```

## 6. Closeout verification

Final local split headless UI regression:

```text
Tests run: 360
Failures: 0
Errors: 0
Skipped: 0
Total time: 07:00
```

The suite groups completed successfully as independent JVM boundaries:

- `FastUITests`;
- `WorkflowApplicationTests`;
- `WorkflowCaptureTests`;
- `WorkflowStateEditingTests`.

This eliminated the out-of-memory failure mode observed with the monolithic local UI run.

Strict Javadoc was green after the final `QuestionSearchNarrowing` public-API documentation correction.

The non-UI suite was green.

GitHub Actions CI for the Sprint 13 branch/PR was green before merge.

PR #107 review then raised two CodeQL `Inner class could be static` findings in `CurriculumMappingReviewDialog`. Both suggestions were valid and were applied before merge.

No additional visual inspection remained outstanding at closeout.

## 7. Final issue disposition

PR #107 deliberately closed:

| Issue | Final disposition |
|---|---|
| #93 | UI refactoring complete |
| #89 | Search Save/refresh defect fixed |
| #82 | Search layout complete |
| #92 | Dashboard Inspect Questions complete |
| #86 | Dashboard alignment complete |
| #87 | lifecycle progress/responsiveness complete |
| #85 | single-syllabus Mapping Review gating complete |
| #78 | Revision export Working Subject scope complete |
| #97 | curriculum workbook onboarding complete |
| #98 | packaged restart workflow complete |

All ten issues closed automatically when PR #107 merged.

Issue #99 remains open as future Dashboard missing-Descriptor workflow work and was not part of Sprint 13.

## 8. Deferred / superseded planning

### #83 — already complete under Sprint 11

Managed syllabus-PDF support already existed. It was not Sprint 13 work.

### #94 + #27 — later Search/performance work

Implement richer retrieval filters first, then benchmark the resulting real workload.

Do not optimise against an incomplete Search workload.

### #99 — future missing-Descriptor Dashboard workflow

This is deliberately separate from Sprint 13 and should reuse the classification/Search infrastructure rather than create a parallel editor.

### #38 — no longer Sprint 14

The earlier Sprint 13 planning assumption that printable/vector-preserving output would be Sprint 14 is superseded.

Sprint 14 is now the Subject-first managed-data restructure under #100–#106.

Printable/vector-preserving output remains future product work.

### #95 — evidence-driven

Capture productivity assistance remains deferred until real-corpus use demonstrates repeated cost.

## 9. Historical closeout

Sprint 13 merged through PR #107 on 7 October 2026.

Merge commit:

`f497f1844c1eebf47f25ebe9e49454b965459f61`

Preserve this document as the detailed Sprint 13 implementation and verification record.
