# Exam Question Bank Documentation

This directory contains the high-level project documentation reconstructed from project chats, repository state, sprint documents and implementation evidence.

## Core documents

- [`project-history.md`](project-history.md) — chronological history from the legacy faculty science Exam Builder through the current sprint-based application. Includes important prototypes, implementation milestones, tests, branches, transitions and abandoned designs.
- [`current-status.md`](current-status.md) — authoritative statement of what the application currently does, known limitations, active data work and explicitly non-current capabilities.
- [`design/architecture-evolution.md`](design/architecture-evolution.md) — explains how and why the architecture changed. This is the primary home for superseded/rejected designs and unresolved transitions.
- [`roadmap.md`](roadmap.md) — concise forward plan excluding completed Sprints 01–03.
- [`DEVELOPMENT_ROADMAP.md`](DEVELOPMENT_ROADMAP.md) — detailed development roadmap. Version 4 incorporates the additional project-chat evidence reviewed on 6 September 2026.
- [`design/backlog.md`](design/backlog.md) — authoritative deferred-work list, including technical debt, unresolved design requirements and future feature inputs.
- `design/sprint-*.md` — sprint-specific design/history records. Completed sprint documents should remain historical records rather than absorbing later backlog items.

## Status vocabulary

- **IMPLEMENTED** — coded and evidenced as working, tested or persisted.
- **DECIDED** — adopted architectural/design rule.
- **PROPOSED** — discussed/planned but not established as implemented.
- **SUPERSEDED** — earlier implementation/design replaced or deliberately not adopted.
- **CURRENT** — newest known design or implementation.

## Important evidence rule

Do not promote a chat prototype, standalone workbook or proposal into current application functionality without repository/persistence/test evidence.

Example: the 5 September `Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` is a completed project data artifact, but its import/reconciliation with current SQLite mapping records is not established and is therefore recorded as pending data work.

## Maintenance rules

1. Update `current-status.md` whenever a sprint/material feature changes actual implementation.
2. Append major transitions and concrete milestones to `project-history.md`.
3. Record replaced architectural choices in `design/architecture-evolution.md`.
4. Remove completed milestones from `roadmap.md`/`DEVELOPMENT_ROADMAP.md` instead of leaving them described as future work.
5. Preserve completed sprint files as historical records.
6. Move deliberately deferred work into `design/backlog.md`.
7. Keep standalone data artifacts distinct from application integration.
8. Preserve explicit unresolved gaps. In particular, the current one-best-fit `Question` classification does not erase the earlier real-world evidence that some questions map to multiple descriptors.
