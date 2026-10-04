# Exam Question Bank Documentation

This directory contains the canonical project documentation for the Exam
Question Bank application. GitHub repository state, implemented code and tests
remain the source of truth when documentation and remembered chat context
disagree.

## Canonical documents

- [`development-roadmap.md`](development-roadmap.md) --- durable project
  history, architectural evolution and forward roadmap.
- [`design/backlog.md`](design/backlog.md) --- deliberately deferred/unresolved
  work that is not active sprint scope or committed next-sprint scope.
- [`design/sprint-*.md`](design/) --- detailed sprint design and evidence. The
  active sprint document also carries current status during that sprint.
- [`release-build.md`](release-build.md) --- repeatable Windows release,
  versioning, packaging and installation-verification procedure.
- [`Working-Instructions.md`](Working-Instructions.md) --- working rules for
  repository-guided development.

The former `project-history.md`, `current-status.md` and
`design/architecture-evolution.md` are retired after consolidation into the
roadmap and active-sprint model.

## Current development position

Sprints 01--11 are complete and merged.

Sprint 11 --- Release 0.1 --- merged to protected `main` through pull request
#39 on 27 September 2026.

Merge commit: `1ccbb350a693568231bd14582ffbecc7684fb79a`.

Final feature-branch GitHub Actions CI run 81 completed successfully.

Its detailed final-state record is:

`design/sprint-11-release-0.1.md`

Sprint 11 delivered:

1. composed integration regressions;
2. clipboard/Snipping Tool Question capture with ordered mixed image/PDF
   content;
3. Question Search Dialog redesign;
4. application tooltips;
5. documented Help system;
6. Help -> About and authoritative version infrastructure;
7. self-contained Windows app-image/MSI packaging;
8. a gated Release 0.1 build and install/uninstall verification.

Sprint 11 closeout is complete. Release 0.1 implementation, local release
verification, feature-branch CI and protected-main merge are all complete.

Sprint 12 implementation is complete on `feature/sprint-12` and targets Release
0.2. Its scope covers Exam intake and asset management, streamlined capture,
optional MCQ explanation regions, and evolution of Corpus Audit into the
operational Corpus Dashboard. Richer retrieval-oriented Question Search
filtering has been deferred.

The current implemented SQLite schema is version **19**.

## Status vocabulary

- **IMPLEMENTED** --- coded and supported by repository/test evidence.
- **VERIFIED** --- implemented and exercised by relevant automated or explicit
  manual verification.
- **DECIDED** --- adopted design or architectural rule.
- **PLANNED** --- agreed work not yet implemented.
- **PROPOSED** --- discussed future work not yet adopted as active scope.
- **SUPERSEDED** --- earlier implementation/design replaced.
- **REJECTED** --- deliberately not to be implemented as a forward direction.
- **CURRENT** --- newest known implementation or design position.

## Evidence rule

Do not promote a chat prototype, standalone workbook, planned feature or desired
behaviour into current application functionality without repository,
persistence or test evidence.

The standalone Chemistry 2019 -> 2025 mapping workbook remains reference
material, not authoritative application state. Mapping decisions come from the
application's human-reviewed SQLite workflow.

Short-lived working defects are tracked as Eclipse task markers. A requirement
or defect belongs in `design/backlog.md` only when deliberately deferred beyond
active/committed sprint work.

## Automated tests and release builds

Useful local development commands are:

``` text
.\mvnw.cmd test
.\mvnw.cmd -Pheadless-ui-tests test
.\mvnw.cmd -Pui-tests test
.\mvnw.cmd javadoc:javadoc
.\mvnw.cmd spotless:check
```

Use the platform-appropriate `./mvnw` form on Unix-like systems where
applicable.

The Windows release gate is:

``` powershell
.\scripts\build-release.ps1
```

A real release requires a clean working tree. The script validates and advances
the `major.minor` release version, then runs formatting, non-UI tests, headless
UI tests, strict Javadoc and MSI packaging. Use an explicit `-Version` when a
specific release number is required. `-AllowDirty` is for release-script
development/validation rather than a formal release.

See [`release-build.md`](release-build.md) for prerequisites, versioning rules,
artifact locations and the post-build install/uninstall smoke test.

GitHub Actions runs separate non-UI, remaining-UI and workflow-UI jobs. The
current feature-branch checks must be green before protected-main merge.

## Documentation lifecycle

1. Keep the active sprint document current as work is designed, implemented and
   verified.
2. At sprint closeout, preserve that sprint document as the detailed immutable
   record.
3. Fold durable history, architecture changes and forward sequencing into
   `development-roadmap.md`.
4. Make the next sprint document the current-status record.
5. Keep deliberately deferred work in `design/backlog.md`.
6. Do not duplicate active or committed next-sprint scope in the backlog.
7. Preserve explicit rejected/superseded decisions where they prevent stale
   ideas returning as future work.
8. Keep standalone data artefacts distinct from application integration.
9. Use Eclipse task markers for short-lived working issues.
10. Keep `release-build.md` aligned with the actual release scripts and package
    prerequisites.
11. Re-run the release gate as appropriate after final code changes before
    protected-main merge.
