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
  architectural decisions, including superseded and adopted directions.
- [`design/backlog.md`](design/backlog.md) — authoritative deferred/unresolved work
  that is not part of completed sprint scope.
- `design/sprint-*.md` — canonical sprint-specific design and final-state records.

## Current sprint-document state

Sprints 01 through 09 are complete historical records and are merged to `main`.

Sprint 09 — Capture Workflow and Corpus Correction — merged to `main` on
20 September 2026 in merge commit `2533586`. Protected-main workflow was then
exercised through pull request #1, leaving `main` at `5a1e5a9` at Sprint 10
start.

Sprint 10 — Capture Hardening and Revision Output Refinement — has completed
implementation and verification on:

`feature/capture-output`

Sprint 10 implementation is complete and verified on the feature branch. 
Sprint 10 merged to main via pull request #2.
Merge commit: a3dfda7e
Post-merge GitHub Actions run 64: successful.

Git/GitHub is authoritative for the current feature-branch head and CI state;
this documentation does not duplicate a moving closeout commit identifier.

This head includes the final native Search-window dirty-close guard and public
API Javadoc closeout. GitHub Actions CI run 61 completed successfully for that
head. Sprint 10 has not yet been merged to `main`; documentation therefore
records implementation complete / verified on the feature branch, with protected
main merge closeout still pending.

The canonical Sprint 10 final-state record is:

`design/sprint-10-capture-output.md`

The latest supported SQLite schema on the Sprint 10 branch is version 13.

## Status vocabulary

- **IMPLEMENTED** — coded and supported by repository/test evidence.
- **VERIFIED** — implemented and exercised by relevant automated or explicit
  manual verification.
- **DECIDED** — adopted design or architectural rule.
- **PLANNED** — agreed work not yet implemented.
- **PROPOSED** — discussed future work not yet adopted as active scope.
- **SUPERSEDED** — earlier implementation/design replaced or deliberately not
  adopted.
- **CURRENT** — newest known implementation or design position.

## Evidence rule

Do not promote a chat prototype, standalone workbook, planned feature or
user-observed desired behaviour into current application functionality without
repository, persistence or test evidence.

The standalone Chemistry 2019 -> 2025 mapping workbook remains reference
material, not authoritative application state. Mapping decisions come from the
application's human-reviewed SQLite workflow.

Short-lived working defects are tracked as Eclipse task markers while they are
being investigated or implemented. A requirement or defect belongs in
`design/backlog.md` only when it is deliberately deferred beyond the current
work.

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
current feature-branch checks must be green before protected-main merge.

## Maintenance rules

1. Update `current-status.md` only for implemented state, known current defects
   and clearly labelled near-term work.
2. Append durable completed transitions to `project-history.md`.
3. Record major replaced or adopted architectural directions in
   `design/architecture-evolution.md`.
4. Keep `DEVELOPMENT_ROADMAP.md` aligned with the current development position
   and remove stale "next" steps after implementation or merge closeout.
5. Preserve one canonical sprint record after each sprint closes.
6. Move deliberately deferred work into `design/backlog.md`; do not leave
   completed sprint items duplicated there as future work.
7. Keep standalone data artefacts distinct from application integration.
8. Preserve explicit unresolved gaps rather than silently assuming them away.
9. Use Eclipse task markers for short-lived working issues; deliberately deferred
   work belongs in the sprint design or backlog.
10. Re-run regression, Javadoc and whitespace checks after final documentation
    edits before protected-main merge.
