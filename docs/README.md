# Exam Question Bank Documentation

This directory contains the canonical project documentation for the Exam Question Bank application. Repository code, tests, GitHub Issues and pull-request/CI state remain authoritative when documentation and remembered chat context disagree.

## Canonical documents

- [`development-roadmap.md`](development-roadmap.md) — durable project history, architectural evolution and forward roadmap.
- [`design/backlog.md`](design/backlog.md) — deliberately deferred or unresolved work that is not active closeout scope.
- [`design/sprint-*.md`](design/) — detailed sprint design, implementation decisions and verification evidence.
- [`release-build.md`](release-build.md) — repeatable Windows release, versioning, packaging and installation-verification procedure.
- [`Working-Instructions.md`](Working-Instructions.md) — repository-guided development rules. This operational file is maintained separately and is not rewritten as part of sprint-history consolidation.

The former `project-history.md`, `current-status.md` and `design/architecture-evolution.md` remain retired after consolidation into the roadmap/sprint-document model.

## Current development position

Sprints 01–11 are complete and merged.

Sprint 11 — Release 0.1 — merged to protected `main` through pull request #39 on 27 September 2026. Merge commit: `1ccbb350a693568231bd14582ffbecc7684fb79a`.

Sprint 12 implementation is complete on `feature/sprint-12` and is **READY FOR PR** following final branch review and verification. Its detailed final implementation record is:

`design/sprint-12-release-0.2.md`

Sprint 12 delivered the application-level Working Subject and Corpus Dashboard home, Exam/Assets management, Exam lifecycle and completion gating, managed-document hashing and safe source correction, streamlined Question/Answer capture, required MCQ explanation completion where declared by the AnswerFile, legacy-import reconciliation, maintained Help and extensive regression hardening.

Final pre-PR verification passed **1,031 non-UI tests** (3 expected skips), **342 UI tests**, and the combined **1,373-test AllTests** run (3 expected skips), all with zero failures/errors. Maven `clean verify`, strict Javadoc, Spotless and `git diff --check` also passed. The final review additionally removed duplicate Dashboard MCQ-explanation handler wiring, hardened TestFX scene-graph lookups/observable save waits, and added direct SQLite regression coverage for destructive Exam asset deletion.

The formal Release 0.2 process is still pending issue #64. The Maven version intentionally remains **0.1** until the normal release script advances the release candidate. Do not describe 0.2 as released until the release gate, MSI smoke test, protected-main merge and closeout evidence are complete.

The latest supported SQLite schema on `feature/sprint-12` is **19**.

## Sprint 12 issue evidence

GitHub Issues are part of the durable evidence for Sprint 12. The consolidated sprint document records the final accepted behaviour, including places where later acceptance comments superseded earlier issue wording.

Important examples include:

- #43–#50 — Exam/Assets, planning metadata, hashing, replacement and lifecycle foundations;
- #51–#54, #59, #70, #71, #79 and #84 — capture workflow and UI closeout;
- #55–#57 and #72 — AnswerFile explanation capability and capture/retrofit foundations;
- #58, #60–#62, #65–#67, #76, #77 and #81 — Corpus Dashboard, Subject ownership, audit/reporting and completion rules;
- #68 — Dashboard-owned legacy import with authoritative Exam/Assets preflight;
- #69 and #74 — Exam metadata correction and reusable suggestion maintenance;
- #75 — asynchronous Working Subject refresh;
- #80 — destructive Exam asset deletion;
- #63 — maintained Help;
- #22 — superseded by the implemented reporting/reconciliation issues above;
- #73 — closed as not planned; mixed booklets retain one total expected top-level Question count;
- #34 — already covered by the existing pre-v13 restore/migration regression and closed;
- #64 — Release 0.2 closeout, still pending.

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

The Windows release gate is:

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
