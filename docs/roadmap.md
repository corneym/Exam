# Exam Question Bank — Roadmap

> Consolidated forward plan at 17 September 2026.
>
> Completed Sprints 01–08 are not future milestones.  `DEVELOPMENT_ROADMAP.md`
> contains the detailed sequence and `design/backlog.md` is the authoritative
> deferred-work list.

## Completed foundation

**IMPLEMENTED / CURRENT**

- Java 25 / JavaFX / Maven desktop application.
- SQLite runtime with foreign keys and migrations through schema v8.
- Managed source PDFs and portable relative paths.
- PDF viewer and managed Exam import.
- Multi-region Question and Answer capture.
- Versioned curriculum and Excel curriculum import.
- Historical-to-current curriculum mapping and review.
- Sprint 01 legacy metadata import.
- Sprint 02 directional curriculum applicability.
- Sprint 03 hierarchical retrieval, asynchronous search and preview.
- Sprint 04 backup, restore and data safety.
- Sprint 05 deterministic revision corpus and hierarchical HTML export.
- Sprint 06 SCORM 1.2 packaging with successful QLearn acceptance.
- Sprint 07 persisted SourceQuestion/shared-context semantics, preamble-aware
  capture, Question/Answer correction, syllabus-sensitive classification,
  multipart/shared-context revision output and capture responsiveness work.
  - Sprint 08 subject-neutral curriculum authoring, application-authoritative
  mapping coverage, legacy metadata correction, persisted Question response type,
  corpus audit/completeness tooling and capture/search hardening.

## Current development position

Sprint 07 is complete and merged.

Sprint 08 — Curriculum and Corpus Completion has completed implementation and
branch-level validation on `feature/data-completion`. Final independent review
and merge remain.

Canonical Sprint 08 design and implementation record:

`docs/design/sprint-08-Curriculum-and-Corpus-Completion.md`

## Milestone 1 — Curriculum and corpus completion

**HIGH PRIORITY / CURRENT PRODUCT VALUE**

The application now provides the core tooling required for curriculum and corpus
completion:

- expert-authored curriculum from authoritative syllabus PDFs without automatic
  hierarchy inference;
- first-class `Unit -> Topic -> Descriptor` structures with optional Subtopic;
- resumable/finalisable curriculum persistence and managed syllabus provenance;
- application-authoritative Descriptor/Subtopic mapping coverage;
- legacy metadata correction;
- persisted per-Question response type;
- response-type-aware Answer completeness;
- a corpus audit queue with Subject/provider/year/booklet/completion/problem
  filters and summary totals;
- routing from corpus problems into the existing metadata, Question and Answer
  workflows.

Sprint 08 regression/capture hardening and closeout validation are complete on
the feature branch. Real bank population remains an ongoing data task rather
than application implementation work.

Real bank population remains an ongoing data task: the tools can identify and
route incomplete Questions, but implementation of the queue does not imply that
the real question corpus itself is complete.

The separate Chemistry 2019 -> 2025 mapping workbook remains reference material.
Application SQLite review state is authoritative.

## Milestone 2 — Bank-management and retrieval hardening

**HIGH/NORMAL PRIORITY**

Focus on the workflows needed to maintain a growing real question bank:

- exam-specific metadata correction;
- import and data-integrity audit reporting;
- additional real-world legacy-workbook validation;
- retrieval-domain and SQLite integration hardening beyond the completed
  Sprint 08 asynchronous Question Search regression pass;
- measurement-driven search/preview performance work.

Do not let cosmetic UI work displace data-correctness or corpus-management work.

## Milestone 3 — Resolve outstanding question semantics

**DESIGN DECISIONS REQUIRED**

### Multiple original classifications

Decide whether one-best-fit original classification remains permanent policy or
whether a Question may carry multiple original classifications.

Any change must review schema, repositories, capture/editing, applicability,
retrieval duplicate semantics, provenance and output placement.

### Question-level applicability exceptions

Allow a historically classified Question to be excluded from a target mapped
current node when the Question tests only content that did not carry forward,
without rewriting either the original classification or the curriculum mapping.

### Out-of-scope ingestion disposition

Decide whether ingestion/audit requires explicit states such as:

```text
not yet reviewed
captured/classified
explicitly out of scope
```

## Milestone 4 — Complete remaining capture refinements

**BACKLOG / SMALLER WORK**

- optional MCQ explanation-region capture.

These are bounded follow-on improvements, not reasons to reopen Sprint 07.

## Milestone 5 — Rich Question Bank Browser and Administration

**LATER**

Add richer:

- filtering and source inspection;
- completeness/status indicators;
- capture/audit queues;
- metadata correction;
- mapping/import reconciliation reports;
- out-of-scope disposition if adopted;
- applicability review/overrides;
- multiple-classification editing if adopted.

## Milestone 6 — Exam Builder

**LATER**

- select Questions into assessments;
- order Questions and sections;
- calculate total marks;
- save reproducible drafts;
- choose inclusion of Answer/marking material;
- preserve stable source/provenance information.

Exam Builder consumes the question bank. It must not become a dependency of
revision HTML/SCORM export.

## Milestone 7 — Printable assessment and solution output

**LATER**

Goals include:

- generated sequential numbering;
- matching Question/solution numbering;
- source attribution where appropriate;
- page-break/layout controls;
- school headers/instructions;
- marking versions.

Prefer vector-preserving inclusion/clipping of authoritative source-PDF content
where practical rather than using raster web assets as the print master.

## Milestone 8 — Clipboard/image attachment content

**LATER**

Support durable Question content originating from transient documents or web
sources, for example Windows Snipping Tool clipboard captures.

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

**LATER**

### Desktop packaging

- self-contained installer/executable, likely `jpackage`;
- Maven-driven reproducible packaging;
- writable database/data root outside installed application files;
- migration and backup safety across upgrades.

### Faculty sharing

- do not use a concurrently edited SharePoint-synchronised SQLite file;
- use SharePoint for backups, exports, source distribution and published
  resources;
- consider controlled import/export/merge;
- move to an IT-supported central database only when simultaneous multi-user
  editing requirements justify it.

## Milestone 10 — Assisted classification and automation

**FUTURE**

Possible assistance:

- PDF text extraction for search/classification;
- descriptor ranking;
- question-heading/boundary suggestions;
- multipart/dependency suggestions;
- OCR for image attachments;
- duplicate detection;
- coverage analytics;
- difficulty metadata;
- assessment-blueprint suggestions.

Automation may suggest. Teachers confirm authoritative classifications and
mappings.

## Recommended order

```text
Merge Sprint 07
        |
        v
Corpus completion + application-authoritative Chemistry mapping review/coverage
        |
        v
Bank-management + retrieval/testing hardening
        |
        v
Resolve remaining Question semantics
        |
        v
Rich Question Bank administration
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
