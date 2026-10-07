# Exam Question Bank Documentation

This directory contains the canonical project documentation for the Exam Question Bank application. Repository code, tests, GitHub Issues and pull-request/CI state remain authoritative when documentation and remembered chat context disagree.

## Canonical documents

- [`development-roadmap.md`](development-roadmap.md) — durable project history, architectural evolution and forward roadmap.
- [`design/backlog.md`](design/backlog.md) — deliberately deferred or unresolved work that is not active sprint scope.
- [`design/sprint-*.md`](design/) — detailed sprint design, implementation decisions and verification evidence.
- [`release-build.md`](release-build.md) — repeatable Windows release, versioning, packaging and installation-verification procedure.
- [`Working-Instructions.md`](Working-Instructions.md) — repository-guided development rules. This operational file is maintained separately and is not rewritten as part of sprint-history consolidation.

The former `project-history.md`, `current-status.md` and `design/architecture-evolution.md` remain retired after consolidation into the roadmap/sprint-document model.

## Current development position

Sprints 01–13 are complete and merged.

Sprint 11 — Release 0.1 — merged to protected `main` through pull request #39 on 27 September 2026. Merge commit: `1ccbb350a693568231bd14582ffbecc7684fb79a`.

Sprint 12 — Release 0.2 — merged to protected `main` through PR #88 on 5 October 2026. Merge commit: `8ba714e2ef7ff07eebb2375021e1e751d4876e47`. Release 0.2 remains the current published release and application version.

Sprint 13 — UI Refactoring, Search and Dashboard Refinement — merged to protected `main` through PR #107 on 7 October 2026. Merge commit: `f497f1844c1eebf47f25ebe9e49454b965459f61`.

Its detailed final implementation record is:

`design/sprint-13-ui-refinement.md`

Sprint 13 delivered:

- behaviour-preserving UI refactoring in `QuestionBankApplication`, `CorpusDashboardPane` and `CurriculumMappingReviewDialog`;
- Search classification-save refresh behaviour that keeps the visible Search state stable and refreshes the Dashboard only after persisted changes;
- improved Question Search spacing and action layout;
- Dashboard `Inspect Questions` entry points at Exam and booklet scope using reusable immutable Search narrowing;
- centred Dashboard table data;
- asynchronous Exam lifecycle persistence/refresh with visible busy feedback and duplicate-action suppression;
- Curriculum Mapping Review single-syllabus gating;
- Revision HTML and SCORM export scoped to the authoritative Working Subject;
- curriculum workbook onboarding from any source location with a retained managed copy;
- automatic packaged-application restart after data-root change and successful restore;
- headless UI regression partitioning into four bounded JVM suites to prevent heap exhaustion while retaining complete UI coverage.

Sprint 13 closeout verification included a complete split headless UI run of **360 tests**, with zero failures and zero errors, strict Javadoc success, and green GitHub Actions CI. The final PR review raised two CodeQL static-inner-class findings in `CurriculumMappingReviewDialog`; both were corrected before merge.

The latest supported SQLite schema on `main` remains **19**.

Sprint 14 is active on `feature/sprint-14` and is documented in:

`design/sprint-14.md`

Its current scope is the Subject-first managed-data layout under umbrella issue #100 and child issues #101–#106. This supersedes the earlier roadmap assumption that printable/vector-preserving output (#38) would necessarily be Sprint 14. Issue #38 remains future product work.

## Recent issue evidence

GitHub Issues remain part of the durable evidence for completed sprint work.

### Sprint 13

PR #107 closed:

- #93 — behaviour-preserving UI refactoring;
- #89 — Search classification Save/refresh defects;
- #82 — Question Search spacing/action layout;
- #92 — Dashboard `Inspect Questions` / scoped Search entry;
- #86 — Dashboard column alignment;
- #87 — Dashboard lifecycle progress/responsiveness;
- #85 — Mapping Review single-syllabus unavailable state;
- #78 — Revision HTML/SCORM scope to authoritative Working Subject;
- #97 — curriculum workbook onboarding/managed-copy behaviour;
- #98 — automatic restart after data-root change or restore.

Issue #99 remains open as future Dashboard work for assigning missing Question descriptors and was deliberately excluded from Sprint 13.

### Sprint 12

Important Sprint 12 evidence includes:

- #43–#50 — Exam/Assets, planning metadata, hashing, replacement and lifecycle foundations;
- #51–#54, #59, #70, #71, #79 and #84 — capture workflow and UI closeout;
- #55–#57 and #72 — AnswerFile explanation capability and capture/retrofit foundations;
- #58, #60–#62, #65–#67, #76, #77 and #81 — Corpus Dashboard, Subject ownership, audit/reporting and completion rules;
- #68 — Dashboard-owned legacy import with authoritative Exam/Assets preflight;
- #69 and #74 — Exam metadata correction and reusable suggestion maintenance;
- #75 — asynchronous Working Subject refresh;
- #80 — destructive Exam asset deletion;
- #63 — maintained Help;
- #64 — Release 0.2 gate and release closeout.

## Status vocabulary

- **IMPLEMENTED** — coded and supported by repository/test evidence.
- **VERIFIED** — implemented and exercised by relevant automated or explicit manual verification.
- **DECIDED** — adopted design or architectural rule.
- **PLANNED** — agreed work not yet implemented.
- **PROPOSED** — discussed future work not yet adopted as active scope.
- **SUPERSEDED** — earlier implementation/design replaced.
- **REJECTED** — deliberately not to be implemented as a forward direction.
- **CURRENT** — newest known implementation or design position.

## Evidence rule

Do not promote a chat prototype, standalone workbook, planned feature or desired behaviour into current application functionality without repository, persistence, test or accepted manual-verification evidence.

The standalone Chemistry 2019 → 2025 mapping workbook remains reference material rather than authoritative application state. Mapping decisions come from the application’s human-reviewed SQLite workflow.

Short-lived working defects are tracked locally or through active GitHub work. A requirement belongs in `design/backlog.md` only when deliberately deferred beyond current closeout work.

## Automated tests and release builds

Useful local development commands are:

```text
.\mvnw.cmd test
.\mvnw.cmd -Pheadless-ui-tests test
.\mvnw.cmd -Pui-tests test
.\mvnw.cmd javadoc:javadoc
.\mvnw.cmd spotless:check
```

Use the platform-appropriate `./mvnw` form on Unix-like systems where applicable.

The `headless-ui-tests` profile now runs the UI regression in four bounded suite JVMs:

- `FastUITests`;
- `WorkflowApplicationTests`;
- `WorkflowCaptureTests`;
- `WorkflowStateEditingTests`.

This is the normal full local headless UI regression path. The split prevents the JavaFX/TestFX/PDF workload from accumulating across one monolithic JVM while preserving the complete UI suite.

The Windows release gate remains:

```powershell
.\scripts\build-release.ps1
```

A formal release requires a clean working tree. The release script owns version advancement, formatting verification, non-UI tests, headless UI tests, strict Javadoc and MSI packaging. See [`release-build.md`](release-build.md).

## Documentation lifecycle

1. Keep the active sprint document current while implementation is changing.
2. At closeout, preserve the sprint document as the detailed implementation/evidence record.
3. Fold durable architecture/history into `development-roadmap.md`.
4. Keep deliberately deferred work in `design/backlog.md`.
5. Keep release mechanics in `release-build.md`.
6. Do not duplicate active closeout work in the backlog.
7. Preserve explicit rejected/superseded decisions where they prevent stale ideas returning.
8. Remove temporary design sketches after their useful content has been consolidated into the sprint document.
