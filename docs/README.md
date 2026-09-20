# Exam Question Bank Documentation

This directory contains the high-level project documentation reconstructed from
project chats, repository state, canonical sprint documents and implementation
evidence.

## Core documents

- [`project-history.md`](project-history.md) — chronological project history and
  major implementation transitions. It is historical evidence rather than the
  authoritative source for the current feature-branch state.
- [`current-status.md`](current-status.md) — authoritative statement of current
  application capability and current limitations.
- [`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md) — single canonical forward
  roadmap. The former `docs/roadmap.md` is retired.
- [`design/architecture-evolution.md`](design/architecture-evolution.md) — why
  major architectural choices changed, including superseded designs.
- [`design/backlog.md`](design/backlog.md) — authoritative deferred/unresolved
  work list.
- `design/sprint-*.md` — canonical sprint-specific design and final-state records.

## Current sprint-document state

Sprints 01 through 08 are completed historical records.

Sprint 09 — Capture Workflow and Corpus Correction — has completed implementation
on `feature/capture-workflow`; its canonical record is:

`design/sprint-09-capture-workflow-and-corpus-correction.md`

At documentation closeout, the feature-branch head is `11acb24`.

Temporary working notes should not replace the canonical sprint record. Deferred
items discovered during Sprint 09 belong in `design/backlog.md`.

## Status vocabulary

- **IMPLEMENTED** — coded and supported by repository/test evidence.
- **DECIDED** — adopted architectural/design rule.
- **PROPOSED** — discussed/planned but not established as implemented.
- **SUPERSEDED** — earlier implementation/design replaced or deliberately not
  adopted.
- **CURRENT** — newest known design or implementation.

## Evidence rule

Do not promote a chat prototype, standalone workbook or proposal into current
application functionality without repository/persistence/test evidence.

The standalone Chemistry 2019 -> 2025 mapping workbook remains reference
material, not authoritative application state. Mapping decisions come from the
application's human-reviewed SQLite workflow.

Likewise, a user-observed issue recorded in `issues.txt` is not automatically a
current capability or backlog item: canonical status/backlog documents should
state whether it is implemented, deferred, rejected or still unresolved.

## Automated tests and CI

Useful local commands are:

```text
.\mvnw.cmd test
.\mvnw.cmd -Pheadless-ui-tests test
.\mvnw.cmd -Pui-tests test
.\mvnw.cmd javadoc:javadoc
```

Use the platform-appropriate `./mvnw` form on Unix-like systems.

The GitHub Actions CI workflow currently runs:

- non-UI tests;
- remaining UI tests under a virtual display;
- workflow UI test suites split through a matrix:
  - capture;
  - state-editing;
  - application.

At Sprint 09 implementation head `11acb24`, all configured jobs passed.

After Sprint 09 merge, `main` branch protection/ruleset configuration should make
the intended CI checks required rather than relying only on development
convention.

## Maintenance rules

1. Update `current-status.md` whenever a sprint or material feature changes
   actual implementation.
2. Append major durable transitions to `project-history.md` when historical
   consolidation is performed; do not use it as a substitute for current status.
3. Record replaced architectural choices in `design/architecture-evolution.md`.
4. Remove completed milestones from future sections of
   `DEVELOPMENT_ROADMAP.md`.
5. Preserve one canonical sprint record after a sprint closes.
6. Move deliberately deferred work into `design/backlog.md`.
7. Keep standalone data artefacts distinct from application integration.
8. Preserve explicit unresolved gaps rather than silently assuming them away.
9. Distinguish product-sprint completion from repository-governance tasks such as
   branch protection.
10. Re-run regression/Javadoc/whitespace checks after final documentation edits
    before merge.
