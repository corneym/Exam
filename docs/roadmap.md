# Exam Question Bank — Roadmap

> Consolidated remaining work from the known state at 7 September 2026.
>
> Completed Sprints 01–06 are deliberately excluded from future milestones except where hardening remains. `DEVELOPMENT_ROADMAP.md` contains the detailed development sequence; `design/backlog.md` is the authoritative deferred-work list.

## Already complete — do not reopen as roadmap milestones

**IMPLEMENTED / CURRENT**

- Java 25 / JavaFX / Maven application foundation.
- Managed source-PDF handling and safe relative paths.
- PDF viewer and managed Exam Import.
- Normalized multi-region question capture and preview.
- Answer domain, answer-region capture and persistence.
- Versioned curriculum and Excel curriculum import.
- SQLite runtime, foreign keys and migrations through schema v4.
- Syllabus-version selection.
- Descriptor and Subtopic historical-to-current mapping/review.
- Sprint 01 legacy metadata import.
- Sprint 02 directional curriculum applicability.
- Sprint 03 hierarchical retrieval, asynchronous search and stored-question preview.
- Sprint 04 backup, restore and data safety.
- Sprint 05 deterministic revision corpus and static hierarchical HTML export.
- Sprint 06 SCORM 1.2 package generation and successful QLearn import/launch acceptance.

## Milestone 1 — Sprint 07: Preamble-aware Question Capture and UI Redesign

**DESIGN COMPLETE / NEXT IMPLEMENTATION**

Detailed design:

`docs/design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

Feature branch:

`feature/preamble-capture`

### Goal

Represent shared introductory material and multipart source-question identity explicitly, improve capture/editing, and make revision HTML/SCORM present those relationships correctly.

### Domain and persistence

Implement schema v5 with:

- persisted `SourceQuestion` identity;
- persisted `SharedQuestionContext`;
- ordered shared-context regions;
- optional Question -> SourceQuestion link;
- optional Question -> SharedQuestionContext link;
- preservation of the legacy `preamble_capture_required` evidence flag.

Migration/import must not guess relationships from question codes or legacy preamble flags.

### Capture workflow

- capture shared context separately from ordinary question regions;
- link/reuse existing shared context;
- retain ordinary multi-region questions as a separate concept;
- suggest but do not silently persist source-question identity;
- show unresolved legacy preamble requirements explicitly;
- allow correction of persisted question metadata, classification, regions and relationships;
- display marks during answer capture;
- allow existing answer correction;
- prevent silent loss of pending selections;
- fix selection ownership so Question controls cannot clear Answer-owned selections;
- replace the fixed narrow capture column with a resizable workspace;
- make the Question classification panel syllabus-sensitive;
- show a Descriptor selector where Descriptor exists in the selected syllabus branch;
- allow classification to stop at Subtopic even when Descriptor children exist;
- require Descriptor when a selected Topic has direct Descriptor children.

### Revision output

Within each final current-curriculum output bucket:

- group parts sharing one `SourceQuestion`;
- render shared context once where appropriate;
- derive grouped marks by summing included member-part marks;
- keep parts in different curriculum buckets separate;
- keep independent questions sharing context as independent questions.

HTML implements the presentation semantics.

SCORM continues to package the same static revision website and must not acquire a second grouping implementation.

### Explicitly unaffected

Sprint 07 does not redesign:

- curriculum import;
- curriculum hierarchy persistence;
- curriculum mapping semantics (the capture panel only consumes the existing syllabus hierarchy);
- curriculum mapping/review;
- applicability;
- Sprint 03 retrieval semantics;
- exam/provider/booklet persistence;
- managed-PDF/path rules;
- backup/restore architecture;
- SCORM 1.2 profile/manifest/ZIP mechanics;
- multiple original classifications.

## Milestone 2 — Corpus completion and mapping reconciliation

**PROPOSED / PARALLEL HIGH PRIORITY**

Continue manual corpus work in parallel with feature development where practical:

- attach remaining question regions;
- attach remaining answer regions;
- resolve missing source documents;
- verify historical classification;
- reconcile the 5 September Chemistry 2019 -> 2025 descriptor mapping workbook with SQLite;
- validate larger real datasets.

A broad filtered capture/audit queue remains deferred beyond the targeted Sprint 07 unresolved-context workflow.

## Milestone 3 — Resolve remaining classification and ingestion gaps

**PROPOSED / DESIGN DECISIONS REQUIRED**

### Multiple original classifications

The current model stores one best-fit original classification, but earlier real classification work showed that one question may relate to several descriptors.

Decide whether:

- one-best-fit remains permanent policy; or
- `Question` needs multiple original classifications.

If changed, review:

- schema/repositories;
- capture/import UI;
- applicability derivation;
- retrieval duplicate semantics;
- export placement/provenance;
- tests.

Do not confuse multiple original classifications with one historical node mapping to several current nodes.

### Out-of-scope source-question disposition

Decide whether ingestion/audit needs an explicit reviewed state distinguishing:

```text
not yet reviewed
captured/classified
explicitly out of scope
```

## Milestone 4 — Retrieval, mapping and UI hardening

**PROPOSED / BACKLOG**

### JavaFX/TestFX

- isolate focus-sensitive tests;
- add stale search/preview/disposal regressions;
- keep infrastructure flakiness separate from application defects.

### Retrieval/domain

- strengthen result invariants;
- broaden SQLite integration cases;
- verify same-Subject isolation and reopen reconstruction;
- benchmark broad searches before optimizing.

### Mapping

- decide whether DB triggers/constraints are warranted for mapping invariants;
- preserve explicit human confirmation;
- improve coverage reporting and review tooling where useful.

### UI polish

Keep cosmetic polish separate from workflow-critical defects.

## Milestone 5 — Rich Question Bank Browser and Administration

**PROPOSED / LATER**

Add richer:

- filters;
- metadata correction beyond the capture workflow;
- source inspection;
- completeness indicators;
- capture/audit queues;
- mapping/import reconciliation reports;
- out-of-scope disposition if adopted.

## Milestone 6 — Exam Builder

**PROPOSED / LATER**

- select questions into assessments;
- order questions/sections;
- total marks;
- saved drafts;
- answer/marking inclusion choices;
- reproducible question ordering.

Exam Builder consumes the bank. It must not become a dependency of revision/SCORM export.

## Milestone 7 — Printable assessment and solution output

**PROPOSED / LATER**

Goals:

- generated sequential numbering;
- matching question/solution numbering;
- source attribution where appropriate;
- page-break/layout controls;
- school headers/instructions;
- marking versions.

Prefer vector-preserving inclusion/clipping of original PDF content where practical rather than using raster web assets as the print master.

## Milestone 8 — Clipboard/image attachment questions

**PROPOSED / LATER**

Support durable question content originating from transient webpages/documents, for example Windows Snipping Tool clipboard captures.

Design work includes:

- text plus zero or more image attachments;
- clipboard paste and optional drag/drop;
- BLOB versus managed-file persistence;
- backup/restore integration;
- HTML/PDF/SCORM rendering;
- non-PDF provenance;
- optional OCR later.

Do not weaken normal PDF-region provenance.

## Milestone 9 — Packaging and deployment

**PROPOSED / LATER**

### Desktop packaging

- self-contained installer/executable, likely `jpackage`;
- Maven-driven packaging;
- writable DB/data root outside installed application files;
- migration/backup safety across upgrades.

### Faculty sharing

- do not use a concurrently edited SharePoint-synchronised SQLite file;
- use SharePoint for backups, exports, source distributions and published resources;
- consider controlled import/export/merge;
- consider a central DB only when simultaneous multi-user requirements justify it.

## Milestone 10 — Assisted classification and automation

**PROPOSED / FUTURE**

Possible tools:

- PDF text extraction for search/classification;
- descriptor ranking;
- question-heading/boundary suggestions;
- part/dependency suggestions;
- OCR pasted image questions;
- duplicate detection;
- coverage analytics;
- difficulty metadata;
- assessment-blueprint suggestions.

Automation suggests; teachers confirm.

## Recommended order

```text
Sprint 07 — preamble/shared-context + multipart capture/editing + output
        |
        +------ parallel corpus completion + mapping reconciliation
        |
        v
Remaining classification/ingestion decisions
        |
        v
Retrieval/UI hardening + richer bank management
        |
        v
Exam Builder + printable output
        |
        +------ clipboard/image content extension when needed
        |
        v
Packaging / deployment / multi-user design
        |
        v
Assisted automation and analytics
```
