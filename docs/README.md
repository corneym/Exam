# Exam Question Bank Documentation

This directory contains the high-level project documentation reconstructed from
project chats, repository state, sprint documents and implementation evidence.

## Core documents

- [`project-history.md`](project-history.md) — chronological project history and
  major implementation transitions.
- [`current-status.md`](current-status.md) — authoritative statement of current
  application capability and current limitations.
- [`design/architecture-evolution.md`](design/architecture-evolution.md) — why
  major architectural choices changed, including superseded designs.
- [`roadmap.md`](roadmap.md) — concise forward plan. Completed Sprints 01–07 are
  treated as foundation, not future milestones.
- [`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md) — detailed post-Sprint-07
  development sequence and architectural constraints.
- [`design/backlog.md`](design/backlog.md) — authoritative deferred-work list,
  including technical debt, unresolved design questions and future inputs.
- `design/sprint-*.md` — canonical sprint-specific design and final-state
  records.

## Sprint 07 consolidation

The canonical Sprint 07 record is:

`design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

It now incorporates the final implementation/status information that had been
spread across temporary working notes.

The temporary Sprint 07 status/backlog and cleanup-review documents were retired
after their final evidence was consolidated into the canonical Sprint 07 record,
current status, project history and backlog.

Deferred Sprint 07 follow-on work belongs in `design/backlog.md`, not in a
parallel sprint-status file.

## Status vocabulary

- **IMPLEMENTED** — coded and evidenced as working, tested or persisted.
- **DECIDED** — adopted architectural/design rule.
- **PROPOSED** — discussed/planned but not established as implemented.
- **SUPERSEDED** — earlier implementation/design replaced or deliberately not
  adopted.
- **CURRENT** — newest known design or implementation.

## Important evidence rule

Do not promote a chat prototype, standalone workbook or proposal into current
application functionality without repository/persistence/test evidence.

For example, the standalone 5 September Chemistry 2019 -> 2025 mapping workbook
is reference material, not authoritative application state. There is no workbook
reconciliation pass: mapping decisions come from the application's human-reviewed
historical-to-current workflow. Pair-specific coverage reporting remains Sprint
08 work; absent review must not be interpreted as an explicit no-match decision.

## Automated tests

Run `.\mvnw.cmd test` (or `.\mvnw.cmd clean test`) for `NonUITests`, which excludes
the `ui` tag. Run `.\mvnw.cmd -Pheadless-ui-tests test` for `UITests` using
headless JavaFX, or `.\mvnw.cmd -Pui-tests test` with a display. Both suites are
needed for complete automated regression verification. Tests must be named
`*Test.java` under `au.edu.eq.questionbank`; JavaFX tests use `@Tag("ui")`.

## Maintenance rules

1. Update `current-status.md` whenever a sprint or material feature changes
   actual implementation.
2. Append major transitions and concrete milestones to `project-history.md`.
3. Record replaced architectural choices in `design/architecture-evolution.md`.
4. Remove completed milestones from `roadmap.md` and
   `DEVELOPMENT_ROADMAP.md` instead of leaving them described as future work.
5. Preserve one canonical sprint record after a sprint closes; retire temporary
   sprint status/cleanup notes once their evidence is incorporated.
6. Move deliberately deferred work into `design/backlog.md`.
7. Keep standalone data artefacts distinct from application integration.
8. Preserve explicit unresolved gaps rather than silently assuming them away.
