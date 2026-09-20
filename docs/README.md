# Exam Question Bank Documentation

This directory contains the canonical project documentation for the Exam Question
Bank application. GitHub repository state, implemented code and tests remain the
source of truth when documentation and remembered chat context disagree.

## Core documents

- [`project-history.md`](project-history.md) — chronological development history
  and major implementation transitions.
- [`current-status.md`](current-status.md) — authoritative statement of current
  implemented capability, known defects and current development position.
- [`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md) — canonical forward roadmap.
- [`design/architecture-evolution.md`](design/architecture-evolution.md) — major
  architectural decisions, including superseded and planned directions.
- [`design/backlog.md`](design/backlog.md) — authoritative deferred/unresolved work
  that is not in the active sprint.
- `design/sprint-*.md` — canonical sprint-specific design and final-state records.
- [`issues.txt`](issues.txt) — short-lived working defect notes. It is not the
  authoritative long-term backlog.

## Current sprint-document state

Sprints 01 through 09 are complete historical records.

Sprint 09 — Capture Workflow and Corpus Correction — was merged to `main` on
20 September 2026 in merge commit `2533586`. The subsequent protected-main
workflow test was merged through pull request #1; current `main` at Sprint 10
start is `5a1e5a9` and GitHub reports `main` as protected.

Sprint 10 — Capture Hardening and Revision Output Refinement — is the active
sprint. Its implementation branch is:

`feature/capture-output`

Its canonical design record is:

`design/sprint-10-capture-output.md`

Sprint 10 begins with two user-observed capture regressions, then adds a
workspace Subject filter, response-type defaults, general shared-context capture
for independently classified Questions and a focused revision-HTML refinement
pass.

## Status vocabulary

- **IMPLEMENTED** — coded and supported by repository/test evidence.
- **DECIDED** — adopted design or architectural rule.
- **PLANNED** — agreed active-sprint work not yet implemented.
- **PROPOSED** — discussed future work not yet adopted as active scope.
- **SUPERSEDED** — earlier implementation/design replaced or deliberately not
  adopted.
- **CURRENT** — newest known implementation or design position.

## Evidence rule

Do not promote a chat prototype, standalone workbook, planned Sprint 10 feature
or user-observed desired behaviour into current application functionality without
repository/persistence/test evidence.

The standalone Chemistry 2019 -> 2025 mapping workbook remains reference
material, not authoritative application state. Mapping decisions come from the
application's human-reviewed SQLite workflow.

A user-observed defect belongs in `issues.txt` while actively being worked, and
in `design/backlog.md` only when deliberately deferred. Active Sprint 10 scope is
recorded in the Sprint 10 design document rather than duplicated as backlog.

## Automated tests and CI

Useful local commands are:

```text
.\mvnw.cmd test
.\mvnw.cmd -Pheadless-ui-tests test
.\mvnw.cmd -Pui-tests test
.\mvnw.cmd javadoc:javadoc
```

Use the platform-appropriate `./mvnw` form on Unix-like systems.

GitHub Actions runs separate non-UI, remaining-UI and workflow-UI jobs. The
Sprint 09 implementation completed with configured checks green, and protected
`main` was subsequently exercised through pull request #1.

## Maintenance rules

1. Update `current-status.md` only for implemented state, known current defects
   and clearly labelled active-sprint plans.
2. Append durable completed transitions to `project-history.md`.
3. Record major replaced or adopted architectural directions in
   `design/architecture-evolution.md`.
4. Keep `DEVELOPMENT_ROADMAP.md` aligned with the active sprint and remove stale
   "next" steps after merge/governance closeout.
5. Preserve one canonical sprint record after each sprint closes.
6. Move deliberately deferred work into `design/backlog.md`; do not leave active
   Sprint 10 items duplicated there as future work.
7. Keep standalone data artefacts distinct from application integration.
8. Preserve explicit unresolved gaps rather than silently assuming them away.
9. Keep `issues.txt` concise; long-term work belongs in the sprint design or
   backlog. Eclipse Task List may replace this working file later without
   changing backlog authority.
10. Re-run regression, Javadoc and whitespace checks after final documentation
    edits before sprint merge.
