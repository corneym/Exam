# Exam Question Bank Documentation

This directory contains the canonical project documentation for the Exam
Question Bank application. GitHub repository state, implemented code and
tests remain the source of truth when documentation and remembered chat
context disagree.

## Canonical documents

-   [`development-roadmap.md`](development-roadmap.md) --- durable
    project history, architectural evolution and forward roadmap.
-   [`design/backlog.md`](design/backlog.md) --- deliberately
    deferred/unresolved work that is not active sprint scope or
    committed next-sprint scope.
-   [`design/sprint-*.md`](design/) --- detailed sprint design and
    evidence. The active sprint document also carries current status
    during that sprint.
-   [`Working-Instructions.md`](Working-Instructions.md) --- working
    rules for repository-guided development.

The former `project-history.md`, `current-status.md` and
`design/architecture-evolution.md` are retired after consolidation into
the roadmap and active-sprint model. They should be removed from the
repository once these replacement documents are adopted.

## Current development position

Sprints 01--10 are complete.

Sprint 10 merged to protected `main` through pull request #2. Merge
commit: `a3dfda7e`. Post-merge GitHub Actions run 64 was successful.

Sprint 11 --- Release 0.1 --- is the active sprint on:

`feature/sprint-11-release-0.1`

Its canonical design/current-status record is:

`design/sprint-11-release-0.1.md`

Sprint 11 sequence:

1.  composed integration regressions;
2.  clipboard/Snipping Tool Question capture;
3.  Question Search Dialog redesign;
4.  application tooltips;
5.  documented Help system;
6.  Help -\> About and authoritative version infrastructure;
7.  deployment/package build;
8.  release 0.1.

Sprint 12 is reserved for Corpus Audit -\> Corpus Dashboard redesign and
richer Question filtering.

The latest supported SQLite schema at Sprint 11 start is version 13.

## Status vocabulary

-   **IMPLEMENTED** --- coded and supported by repository/test evidence.
-   **VERIFIED** --- implemented and exercised by relevant automated or
    explicit manual verification.
-   **DECIDED** --- adopted design or architectural rule.
-   **PLANNED** --- agreed work not yet implemented.
-   **PROPOSED** --- discussed future work not yet adopted as active
    scope.
-   **SUPERSEDED** --- earlier implementation/design replaced.
-   **REJECTED** --- deliberately not to be implemented as a forward
    direction.
-   **CURRENT** --- newest known implementation or design position.

## Evidence rule

Do not promote a chat prototype, standalone workbook, planned feature or
desired behaviour into current application functionality without
repository, persistence or test evidence.

The standalone Chemistry 2019 -\> 2025 mapping workbook remains
reference material, not authoritative application state. Mapping
decisions come from the application's human-reviewed SQLite workflow.

Short-lived working defects are tracked as Eclipse task markers. A
requirement or defect belongs in `design/backlog.md` only when
deliberately deferred beyond active/committed sprint work.

## Automated tests and CI

Useful local commands are:

``` text
.\mvnw.cmd test
.\mvnw.cmd -Pheadless-ui-tests test
.\mvnw.cmd -Pui-tests test
.\mvnw.cmd javadoc:javadoc
```

Use the platform-appropriate `./mvnw` form on Unix-like systems.

GitHub Actions runs separate non-UI, remaining-UI and workflow-UI jobs.
The current feature-branch checks must be green before protected-main
merge.

## Documentation lifecycle

1.  Keep the active sprint document current as work is designed,
    implemented and verified.
2.  At sprint closeout, preserve that sprint document as the detailed
    immutable record.
3.  Fold durable history, architecture changes and forward sequencing
    into `development-roadmap.md`.
4.  Make the next sprint document the current-status record.
5.  Keep deliberately deferred work in `design/backlog.md`.
6.  Do not duplicate active or committed next-sprint scope in the
    backlog.
7.  Preserve explicit rejected/superseded decisions where they prevent
    stale ideas returning as future work.
8.  Keep standalone data artefacts distinct from application
    integration.
9.  Use Eclipse task markers for short-lived working issues.
10. Re-run appropriate regression, Javadoc and whitespace checks after
    final documentation edits before protected-main merge.
