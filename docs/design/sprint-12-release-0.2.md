# Sprint 12 — Exam Intake, Capture Workflow and Corpus Dashboard

> **Status:** COMPLETED / VERIFIED / MERGED\
> **Branch:** `feature/sprint-12`  
> **Closeout reference:** 5 October 2026  
> **Target release:** 0.2  
> **Pull request:** [#88](https://github.com/corneym/Exam/pull/88)\
> **Maven/application/MSI version:** 0.2\
> **Latest schema:** 19
>
> This document is the consolidated Sprint 12 design, implementation and verification record. It supersedes the temporary Sprint 12 screen-design `.txt` files whose useful layouts are incorporated below. Repository code/tests and final GitHub issue acceptance comments remain authoritative where earlier design wording differs.

## 1. Sprint objective and final outcome

Sprint 12 turns the post-0.1 application into an operational real-corpus collection and maintenance tool. The main changes are:

1. authoritative Exam setup/assets and planning metadata before capture;
2. safer source-document identity, replacement and deletion;
3. streamlined sustained-use Question/Answer capture;
4. AnswerFile-declared MCQ explanation capture and completion requirements;
5. Exam/booklet/Question audit promoted into the Corpus Dashboard;
6. Corpus Dashboard promoted to the application home and owner of the Working Subject;
7. legacy-import intake reconciled through the same authoritative Exam/Assets model;
8. packaged Help updated to the final workflows.

Sprint 12 is complete and merged to protected `main` through PR #88. The automated Release 0.2 gate and manual installed-MSI verification passed before merge. PR #88 merged on 5 October 2026 as `8ba714e2ef7ff07eebb2375021e1e751d4876e47`; post-merge CI run #164 and CodeQL run #15 both passed on that exact `main` commit. Issue #64 was then closed as completed. Release 0.2 is the current published release.

## 2. Evidence model and issue record

GitHub Issues are a durable part of Sprint 12 evidence. Final acceptance comments supersede early issue wording where implementation/live review refined the design.

### Exam/Assets and source management

- #43 — main-window Exam/Assets workspace;
- #44 — Question-booklet inspection and expected top-level Question count;
- #45 — persisted Exam/booklet planning metadata;
- #46 — managed-document SHA-256 hashing;
- #47 — safe Question booklet PDF replacement;
- #48 — safe Answer PDF replacement/reassignment;
- #49 — ACTIVE/COMPLETE lifecycle and structural locking;
- #50 — capture workspace organised around explicit Exam/booklet context;
- #55 and #72 — persisted AnswerFile explanation metadata;
- #69 — Exam metadata editing moved into Exam/Assets;
- #74 — removable Provider/Assessment suggestions without mutating persisted Exams;
- #80 — destructive Question/Answer asset deletion with transactional cleanup.

### Capture workflow and hardening

- #51 — explicit new-Question capture transition;
- #52 — simplified Question-content actions;
- #53 — imported/incomplete workflow shown only when needed;
- #54 — multipart/Shared Context reuse;
- #59 — image content counts as authoritative Question content;
- #70 — QuestionCapturePane behaviour-neutral refactor;
- #71 — Question Capture UI/workflow closeout;
- #75 — asynchronous Working Subject refresh;
- #79 — AnswerCapturePane behaviour-neutral refactor;
- #84 — bounded/scrollable accepted-content preview with immediate expansion.

### MCQ explanation workflow

- #56 — MCQ Answers may retain A–D plus explanation regions;
- #57 — retrofit workflow for already-answered MCQs;
- #65 — final operational semantics: a flagged AnswerFile makes explanation regions required for Exam completion while ordinary A–D Answer completeness remains separate.

### Dashboard, Subject ownership and reconciliation

- #58 — Exam/booklet audit model;
- #60 — Corpus Audit → operational Corpus Dashboard;
- #61 — direct routing into correction workflows;
- #62 — curriculum mapping-review status as a separate Subject dimension;
- #65 — Dashboard as application home;
- #66 — first-class Subject creation;
- #67 — useful empty-Subject onboarding presentation;
- #76 — curriculum workflows inherit Working Subject;
- #77 — audit/Dashboard scope inherits Working Subject;
- #81 — per-booklet `No descriptor` reporting and completion gate;
- #68 — Dashboard-owned legacy import with Exam/Assets structural preflight;
- #22 — superseded umbrella, fully covered by #58/#65/#68/#81.

### Help/release/closeout

- #63 — maintained Sprint 12 Help, automated and manual verification complete;
- #64 — Release 0.2 gate, manual MSI verification, protected-main merge and post-merge CI completed; closed;
- #73 — per-response-type planning for mixed booklets closed as not planned;
- #34 — already covered by an existing restore/migration regression and closed.

## 3. Final architectural decisions

### 3.1 Working Subject is application-level context

The Corpus Dashboard owns the authoritative Working Subject. Search, capture, Exam/Assets and curriculum workflows inherit it rather than offering competing Subject selection.

A compact `+` action creates a Subject independently. Subject refresh is application-coordinated and asynchronous; stale generations cannot overwrite a newer selection.

### 3.2 A Subject needs curriculum before Exam creation

A Subject can exist without curriculum or Exams. The Dashboard presents an explicit empty state. Curriculum may be added from the Dashboard. `Add Exam` remains unavailable until curriculum exists.

This final rule supersedes the earlier #67 acceptance note that temporarily allowed Add Exam before curriculum.

### 3.3 Exam structure is explicit planning, not invented domain data

Exam-level planning records expected Question-booklet and Answer-file counts. Each Question booklet may record an expected number of top-level Questions.

Multipart parts share top-level source identity for counting. `21a`, `21b`, `21c` count as one expected source Question.

Expected values never create placeholder booklets, Questions or Answers.

### 3.4 Exam lifecycle is explicit but `Mark Complete` is readiness-gated

Lifecycle is persisted as `ACTIVE` or `COMPLETE`. It changes only through an explicit user action and is never silently recalculated.

However the final Sprint 12 design does not permit `Mark Complete` merely because the user requests it. Readiness requires all authoritative structural/content prerequisites to be satisfied.

A COMPLETE Exam must be reactivated before structural management. Existing permitted correction/enrichment work may still be completed through the Dashboard/capture routes.

### 3.5 Final completion-readiness rule

`ExamCorpusStatus.isReadyForCompletion()` requires:

- expected Question-booklet count recorded and equal to available Question booklets;
- expected Answer-file count recorded and equal to available Answer files;
- every booklet Question PDF available;
- every booklet expected top-level Question count recorded and equal to encountered top-level source Questions;
- zero ordinary incomplete Questions (Question content, Answer, response type, Shared Context);
- zero Questions below Descriptor classification level;
- zero missing required MCQ explanations in every explanation-capable booklet.

### 3.6 MCQ explanations are a separate completion dimension

A/B/C/D remains the authoritative MCQ Answer. Explanation regions do not change ordinary Answer completeness.

When an assigned AnswerFile is marked `Contains answer explanations`, the flag declares that explanation regions are expected for each eligible answered MCQ. Missing regions therefore block Exam completion and appear as separate Dashboard work.

This final requirement supersedes early issue/design wording that described explanation regions as globally optional.

### 3.7 Source coordinates are source-document-specific

PDF replacement never silently remaps old regions. Question/Shared Context source capture tied to an old Question booklet PDF is invalidated and explicitly returned to capture. Answer regions tied to an old Answer PDF are removed according to the safe replacement/reassignment workflow while independent A–D choices remain.

### 3.8 Managed-document hashes are evidence, not identity constraints

SHA-256 is stored on managed source documents and used to recognise known/duplicate/conflicting source material. Existing rows may be backfilled when bytes are inspected. The hash is not UNIQUE because the application owns reconciliation policy.

### 3.9 Asset deletion is deliberate and destructive

Question-booklet and AnswerFile deletion requires an ACTIVE Exam and explicit confirmation. Persistence cleanup is transactional. Managed PDFs are removed only when the source document is no longer referenced. Planning expectations are retained so deletion produces a visible structural shortfall rather than rewriting what the Exam was expected to contain.

### 3.10 Legacy import does not own a second Exam model

The Dashboard is the sole Legacy Import launcher. Working Subject is inherited. Historical syllabus/workbook selection remains explicit. Preflight reuses existing Exam/booklet assets. Missing structure is resolved through Exam/Assets, then the existing atomic metadata importer runs.

## 4. Persistence changes

Sprint 12 moves the schema from v14 to v19.

| Schema | Durable change |
|---|---|
| v15 | `Exam.capture_state`; `ExamBooklet.expected_question_count` |
| v16 | `SourceDocument.content_sha256` |
| v17 | `Question.source_capture_required` |
| v18 | Exam expected Question-booklet and Answer-file counts |
| v19 | `AnswerFile.contains_answer_explanations` |

Migration deliberately leaves historical unknown values as NULL/false where source data cannot be inferred safely.

## 5. Exam/Assets final workflow

Exam/Assets is the authoritative structural workspace. It reuses the main PDF pane and the Dashboard Working Subject.

It supports:

- selecting persisted Exams for the Working Subject;
- creating a new Exam;
- Provider/Year/Assessment correction through the authoritative correction service;
- expected Question-booklet and Answer-file planning counts;
- Question booklet source PDF, name, type, expected top-level Questions and assigned AnswerFile;
- AnswerFile source PDF/name and `Contains answer explanations` metadata;
- PDF inspection/viewing;
- safe Question/Answer source replacement;
- destructive Question/Answer asset deletion;
- capture activation for a selected Question booklet;
- Dashboard return after the structural transaction is clean.

Structural edits are unavailable for COMPLETE Exams until reactivation from the Dashboard.

## 6. Question and Answer capture final workflow

Question capture no longer starts implicitly when a booklet is opened. New capture begins through an explicit action and remains active sequentially after a successful new Question save.

Accepted Question content is an ordered list of PDF regions and clipboard images. Question content creation uses `Add Region` and `Add From Clipboard`; accepted parts can be reordered/removed individually. Clipboard image availability is advisory and robust to Windows clipboard decode failures.

Shared Context is shown before Question-specific content in the accepted-content preview without duplicating persisted content. Multipart source decisions are reused from persistence.

The accepted-content preview is bounded and locally scrollable; it expands immediately after content is accepted rather than waiting for a later layout pulse.

Answer capture remains booklet-aware. Written Answers use regions. MCQs retain A–D as authoritative Answer data. Explanation capture reuses the same Answer edit/persistence path.

During an MCQ explanation session:

- the exact Dashboard-selected Question can open directly;
- saving removes that candidate and advances to the next missing explanation;
- the current marking-PDF page is preserved while advancing within the same source;
- clean `Return to Corpus Dashboard` does not falsely warn about unfinished work;
- saving the final required explanation ends the session automatically.

## 7. Corpus Dashboard final workflow

The Dashboard is the application home after startup/Subject selection. It is both a status surface and a routing surface; it does not duplicate persistence rules already owned by audit/capture/Exam services.

### Subject level

The Dashboard contains:

- authoritative Working Subject + Subject creation;
- Curriculum section with Add Curriculum and mapping-review status/action;
- Provider/year/Exam-state scope filters;
- Subject-level summary actions for Questions, ordinary needs-attention dimensions and missing MCQ explanations;
- legacy import entry.

### Exam level

The Exam table reports Year, Provider, Assessment, declared State, Question Booklets present/expected, Answer Booklets present/expected and Work.

Selecting an Exam exposes `Manage Exam / Assets` and the lifecycle action (`Mark Complete` or `Mark Active` as appropriate).

### Booklet level

The booklet table reports:

- Booklet;
- Format;
- Question PDF availability;
- Answer file;
- Expected top-level Questions;
- Found top-level Questions;
- Question Parts;
- No descriptor;
- MCQ explanations;
- Problems.

Explanation coverage is `captured / eligible` for a flagged AnswerFile and `—` for a booklet without explanation capability.

### Question Work

Question Work identifies Provider, Year, Exam, Booklet, Question, Type, Content, Answer, Shared Context and Problem. The Dashboard routes directly to the relevant existing workflow rather than opening a generic ambiguous “resolve” action.

## 8. Curriculum and Subject scoping

Curriculum Mapping and Add Curriculum inherit the Dashboard Working Subject. Add Curriculum provides one entry point for PDF authoring or Excel import. Returning refreshes the Dashboard.

Mapping-review coverage remains independent from Exam structural completeness and ordinary Question completeness.

## 9. Consolidated screen designs

The following diagrams consolidate and supersede the temporary Sprint 12 `.txt` screen sketches. They are documentation diagrams, not pixel-perfect UI specifications.

### 9.1 Corpus Dashboard — final concept

```text
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ CORPUS DASHBOARD                                                                             │
│ Working Subject [ Chemistry ▼ ] [ + ]                                                        │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ CURRICULUM                                      [ + ] [ Map Curriculum ]                     │
│ Mapping review: 2019 → 2025 ...                                                           │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ Provider [ All ▼ ]   Year [ All ▼ ]   Exam state [ All ▼ ]   [ Clear ] [ Import Legacy ]   │
│ [ Questions ] [ Need attention ] [ Missing content ] [ Missing answers ] [ Unknown type ]    │
│ [ Shared Context ] [ Missing MCQ explanations ]                                               │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ EXAMS                                                                                        │
│ Year Provider Assessment State Question Booklets Answer Booklets Work                         │
│ ...                                                                                          │
│ Selected Exam ... [ Manage Exam / Assets ] [ Mark Complete | Mark Active ]                  │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ QUESTION BOOKLETS                                                                            │
│ Booklet | Format | Question PDF | Answer file | Expected | Found | Parts | No descriptor     │
│         | MCQ explanations | Problems                                                        │
│ ...                                                                                          │
│ [ Capture Questions ] [ Capture Answers ] [ Capture MCQ Explanations ]                       │
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ QUESTION WORK                                                                                │
│ Show [ Needs attention ▼ ]                                                                   │
│ Provider | Year | Exam | Booklet | Question | Type | Content | Answer | Shared Context       │
│          | Problem                                                                           │
│ ...                                                                                          │
│ [ Complete Selected Question / Capture Explanation ] [ Complete Selected Answer ]             │
│ [ Select all unknown ] [ Set selected: Multiple choice ] [ Set selected: Written ]            │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

Empty Subject state is explicit. Without curriculum the Dashboard says to add curriculum before adding an Exam; Add Exam remains disabled until curriculum exists.

### 9.2 Exam / Assets mode — final concept

```text
┌──────────────────────────────────────────────┬───────────────────────────────────────────────┐
│ Working Subject: Chemistry                   │                                               │
│ [ Return to Corpus Dashboard ]               │                                               │
│                                              │                                               │
│ ┌─ EXAM ───────────────────────────────────┐ │                                               │
│ │ [ 2025 QCAA External Assessment ▼ ]      │ │                                               │
│ │ State: ACTIVE                            │ │                                               │
│ │ Provider [QCAA▼]  Year [2025▼]           │ │                                               │
│ │ Assessment [External Assessment▼]        │ │                 PDF PANE                      │
│ │ Expected Question booklets [2]           │ │                                               │
│ │ Expected Answer booklets   [1]           │ │ Shows inspected Question/Answer source       │
│ │ [Use Selected Booklet for Capture]       │ │                                               │
│ │                         [Edit][Cancel][Save]│                                               │
│ └──────────────────────────────────────────┘ │                                               │
│                                              │                                               │
│ ┌─ QUESTION BOOKLETS ─ [Add Question Booklet]──────────────────────────────────────────────┐ │
│ │ (●) Paper 1                                  [View] [Delete]                              │ │
│ │ source: paper1.pdf                                                                        │ │
│ │ Name [Paper 1▼]  Type (●)MCQ ( )Written ( )Both                                          │ │
│ │ Expected Questions [20]   Answer [Marking Guide▼]                                         │ │
│ │                                      [Edit][Cancel][Save]                                  │ │
│ └────────────────────────────────────────────────────────────────────────────────────────────┘ │
│                                              │                                               │
│ ┌─ ANSWER BOOKLETS ─ [Add Answer Booklet]──────────────────────────────────────────────────┐ │
│ │ Marking Guide                                  [View] [Delete]                           │ │
│ │ source: marking-guide.pdf                                                                   │ │
│ │ ☑ Contains answer explanations                                                           │ │
│ └────────────────────────────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────┴───────────────────────────────────────────────┘
```

Lifecycle change itself belongs to the Dashboard. COMPLETE Exams must be marked ACTIVE before structural Exam/Assets work.

### 9.3 New Exam transaction

```text
┌──────────────────────────────────────────────┬───────────────────────────────────────────────┐
│ Working Subject: Chemistry                   │                                               │
│                                              │                                               │
│ ┌─ EXAM ───────────────────────────────────┐ │                                               │
│ │ NEW EXAM                                │ │                                               │
│ │ Provider   [                 ▼]          │ │                 PDF PANE                      │
│ │ Year       [                 ▼]          │ │                                               │
│ │ Assessment [                 ▼]          │ │                                               │
│ │ Expected Question booklets [ ]           │ │                                               │
│ │ Expected Answer booklets   [ ]           │ │                                               │
│ │ [Clear]                 [Cancel][Save Exam]│                                               │
│ └──────────────────────────────────────────┘ │                                               │
│                                              │                                               │
│ Question/Answer booklet creation waits for a persisted Exam identity.                         │
└──────────────────────────────────────────────┴───────────────────────────────────────────────┘
```

### 9.4 Question and Answer booklet rows

```text
QUESTION BOOKLET
┌──────────────────────────────────────────────────────────────┐
│ (●) Paper 1                                   [View][Delete] │
│ paper1.pdf                                                   │
│ Name       [ Paper 1                       ▼]                │
│ Type       (●) MCQ  ( ) Written Response  ( ) Both           │
│ Expected Questions [ 20 ]                                   │
│ Answer     [ Marking Guide                ▼]                 │
│                                      [Edit][Cancel][Save]    │
└──────────────────────────────────────────────────────────────┘

Answer choices include an explicit No Answer Booklet plus the Exam's persisted AnswerFiles.

ANSWER BOOKLET
┌──────────────────────────────────────────────────────────────┐
│ Marking Guide                                  [View][Delete]│
│ marking-guide.pdf                                            │
│ ☑ Contains answer explanations                              │
└──────────────────────────────────────────────────────────────┘
```

### 9.5 Capture mode

```text
┌──────────────────────────────────────────────┬───────────────────────────────────────────────┐
│ Working Subject: Chemistry                   │                                               │
│ Active Exam / Booklet context                │                                               │
│ [ Change Exam ]                              │                                               │
│                                              │                                               │
│ ┌─ CLASSIFICATION ─────────────────────────┐ │                                               │
│ │ Question code / syllabus hierarchy      │ │                                               │
│ └──────────────────────────────────────────┘ │                 PDF PANE                      │
│                                              │                                               │
│ ┌─ QUESTION ───────────────────────────────┐ │                                               │
│ │ explicit Start New Question Capture     │ │                                               │
│ │ Add Region / Add From Clipboard         │ │                                               │
│ │ bounded Accepted Question content       │ │                                               │
│ └──────────────────────────────────────────┘ │                                               │
│                                              │                                               │
│ ┌─ ANSWER ─────────────────────────────────┐ │                                               │
│ │ ordinary Answer / MCQ explanation work │ │                                               │
│ └──────────────────────────────────────────┘ │                                               │
│ [ Return to Corpus Dashboard ] when Dashboard-owned workflow is active                        │
└──────────────────────────────────────────────┴───────────────────────────────────────────────┘
```

### 9.6 Main mode transitions

```text
CORPUS DASHBOARD
    │
    ├─ Manage Exam / Assets ───────────────► EXAM / ASSETS
    │                                          │
    │                                          └─ Return to Corpus Dashboard
    │
    ├─ Capture Questions ───────────────────► CAPTURE WORKSPACE
    │
    ├─ Capture Answers ─────────────────────► CAPTURE WORKSPACE
    │
    ├─ Capture MCQ Explanations ────────────► CAPTURE WORKSPACE
    │
    └─ selected Question action ────────────► exact correction/capture target

EXAM / ASSETS
    └─ Use Selected Booklet for Capture ────► generic CAPTURE WORKSPACE
```

## 10. Legacy import final design

The accepted flow is:

```text
Corpus Dashboard / Import Legacy
        ↓
choose historical syllabus + workbook
        ↓
preflight against Working Subject persisted Exams/booklets
        ↓
all structure resolved? ── yes ──► atomic legacy metadata import
        │
        no
        ↓
Exam / Assets structural requirements
        ↓
Recheck and Import
        ↓
Dashboard refresh
```

Ordinary Exam/Assets no longer exposes a duplicate Import Legacy launcher. Pending preflight controls remain there only when Dashboard-started intake needs missing structure to be resolved.

## 11. Verification and acceptance

Sprint 12 was implemented through small targeted regressions plus repeated full-suite/CI checkpoints. Issue comments record the accepted manual checks for the high-risk UI workflows.

Verified behaviours include:

- Exam/Assets creation/edit/inspection and booklet/Answer assignment;
- SHA-256 source matching and safe PDF replacement;
- ACTIVE/COMPLETE structural locking;
- asynchronous Working Subject refresh and stale-result suppression;
- explicit/sequential Question capture and imported/incomplete queues;
- Shared Context preview/reuse and bounded accepted-content layout;
- ordinary Answer capture and retrofit MCQ explanation editing;
- Dashboard filters, hierarchy, routing and refresh;
- curriculum mapping status and Working Subject scoping;
- descriptor reporting/completion gating;
- destructive asset deletion;
- Dashboard-owned legacy import/preflight;
- packaged Help resources and visual navigation.

The final MCQ explanation live-data acceptance confirmed:

- exact selected MCQ opens;
- save/advance preserves the marking-PDF page;
- clean Dashboard return produces no false unfinished-work warning;
- refreshed Dashboard counts update;
- saving the final candidate automatically ends the explanation session and removes stale A–D controls.

The maintained Help update (#63) passed `HelpResourcesTest` and manual WebView/navigation inspection.

Strict Javadoc is a release gate; public `CorpusDashboardPane` API Javadocs were completed after the final warning report.

### 11.1 Final pre-PR review and verification

The final branch review found and corrected several merge-readiness issues without changing the accepted Sprint 12 workflow semantics:

- duplicate MCQ-explanation Dashboard handler registration was removed;
- ordinary TestFX control activation was normalised to semantic firing where pointer behaviour was not under test;
- asynchronous Dashboard scene-graph lookup was serialised on the JavaFX thread to remove a combined-suite race;
- Answer-capture workflow waits now observe persisted/UI completion rather than relying only on an initially-false `isSaveInProgress()` flag; and
- destructive Exam asset deletion gained focused SQLite regression coverage for dependent cleanup, shared-source preservation and COMPLETE-Exam rejection.

Final verification passed:

- **1,031 non-UI tests** — 0 failures, 0 errors, 3 expected skips;
- **342 headless UI tests** — 0 failures, 0 errors;
- **1,373 combined AllTests** — 0 failures, 0 errors, 3 expected skips;
- Maven `clean verify`;
- strict Javadoc with doclint/`failOnWarnings`;
- Spotless; and
- `git diff --check`.

These counts record the pre-PR implementation checkpoint. The subsequent formal Release 0.2 gate and installed-MSI verification also passed, as recorded in section 14. During the formal gate, two release-independent test defects were corrected: `ApplicationVersionTest` and the About-dialog test in `ApplicationLifecycleWorkflowTest` had hard-coded Release 0.1 expectations.

## 12. Deliberately superseded or rejected Sprint 12 ideas

- Subject-level total `MCQ explanations x / y` headline was removed; the actionable missing-explanations summary plus booklet coverage is sufficient.
- Generic `Resolve Selected` wording was replaced by task-specific capture/correction actions.
- Exam lifecycle actions do not depend on an active capture booklet; they belong to selected Dashboard Exam state.
- Legacy import is not launched independently from Exam/Assets; Dashboard owns intake.
- Per-response-type expected counts for mixed booklets (#73) are not planned.
- Broad #22 import-audit/reconciliation is superseded by concrete Dashboard/audit/import issues.
- Multi-page Shared Context remains rejected.

## 13. Deferred beyond Sprint 12

Not Sprint 12 blockers:

- #78 — scope Revision exports to Working Subject;
- #82 — Question Search spacing/action layout polish;
- #83 — managed syllabus documents;
- #85 — do not open Mapping Review with only one syllabus;
- #86 — Dashboard column alignment;
- #87 — lifecycle progress feedback;
- richer retrieval-oriented Question Search filters;
- distributed collection packages;
- Exam Builder and printable/vector-preserving output.

See `docs/design/backlog.md`.

## 14. Release 0.2 closeout — issue #64

Release 0.2 closeout is complete:

- PR [#88](https://github.com/corneym/Exam/pull/88) passed its final pre-merge CI checkpoint and merged to protected `main` on 5 October 2026.
- Merge commit: `8ba714e2ef7ff07eebb2375021e1e751d4876e47`.
- Post-merge CI run **#164** completed successfully on that exact `main` commit.
- Post-merge CodeQL run **#15** also completed successfully on the merge commit.
- `scripts/build-release.ps1` completed successfully, including formatting, non-UI tests, headless UI tests, strict Javadoc and installer packaging.
- The authoritative Maven/application/MSI version is **0.2**.
- `target\installer\Exam Question Bank-0.2.msi` was produced successfully.
- Manual installed-MSI verification outside Eclipse passed: Dashboard startup, existing persisted data readable, Help opens, About reports 0.2, normal shutdown, successful uninstall, and preservation of configuration/data.
- During the formal gate, hard-coded Release 0.1 expectations in `ApplicationVersionTest` and the About-dialog `ApplicationLifecycleWorkflowTest` were corrected so release validation is version-independent.
- Issue #64 was closed as completed after the merge and post-merge CI evidence were recorded.

Sprint 12 is merged and Release 0.2 is the current published release.