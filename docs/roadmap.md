# Exam Question Bank — Roadmap

> Consolidated forward plan at 11 September 2026.
>
> Completed Sprints 01–07 are not future milestones. `DEVELOPMENT_ROADMAP.md`
> contains the detailed sequence and `design/backlog.md` is the authoritative
> deferred-work list.

## Completed foundation

**IMPLEMENTED / CURRENT**

- Java 25 / JavaFX / Maven desktop application.
- SQLite runtime with foreign keys and migrations through schema v6.
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

## Immediate closeout

Sprint 07 is implemented on `feature/preamble-capture`. Documentation
consolidation and the final branch-versus-`main` merge-readiness review are
complete, with no known merge blocker remaining after final regression
verification.

Canonical Sprint 07 record:

`docs/design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

## Milestone 1 — Corpus completion and mapping reconciliation

**HIGH PRIORITY / NEXT PRODUCT VALUE**

Continue real-data completion without redesigning the implemented core model:

- capture remaining Question regions;
- capture remaining Answer/marking regions;
- resolve missing source documents;
- verify historical classifications;
- review and reconcile the 5 September Chemistry 2019 -> 2025 mapping workbook
  with SQLite mapping records;
- establish coverage statistics after reconciliation;
- exercise larger real datasets.

A broad filtered capture/audit queue is useful follow-on work rather than a
prerequisite for data capture.

## Milestone 2 — Bank-management and retrieval hardening

**HIGH/NORMAL PRIORITY**

Focus on the workflows needed to maintain a growing real question bank:

- broad capture/audit queue and completeness filters;
- exam-specific metadata correction;
- import audit/reconciliation reporting;
- additional real-world legacy-workbook validation;
- remaining asynchronous search/preview lifecycle tests;
- retrieval-domain and SQLite integration hardening;
- measurement-driven search/preview performance work.

Do not let cosmetic UI work displace data-correctness or corpus-management work.

## Milestone 3 — Resolve outstanding question semantics

**DESIGN DECISIONS REQUIRED**

### Multiple original classifications

Decide whether one-best-fit original classification remains permanent policy or
whether a Question may carry multiple original classifications.

Any change must review schema, repositories, capture/editing, applicability,
retrieval duplicate semantics, provenance and output placement.

### Question-level response type

Replace booklet-name inference with persisted Question-level response type such
as `MULTIPLE_CHOICE`, `WRITTEN_RESPONSE` and `UNKNOWN`.

Use it to drive Answer UI behaviour and support mixed-response booklets.

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

- multi-page automatic shared-preamble capture;
- Answer-pane layout stability;
- Full-width-selection state change clearing a pending selection;
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
Corpus completion + Chemistry mapping reconciliation
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
