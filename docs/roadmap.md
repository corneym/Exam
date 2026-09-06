# Exam Question Bank — Roadmap

> Consolidated remaining work from the known state at 6 September 2026.
>
> Completed Sprints 01–05 are deliberately excluded from future milestones except where hardening remains. `DEVELOPMENT_ROADMAP.md` contains the more detailed development sequence; `design/backlog.md` is the authoritative deferred-work list.

## Already complete — do not reopen as roadmap milestones

**IMPLEMENTED / CURRENT**

- Java 25 / JavaFX / Maven application foundation.
- Managed source-PDF handling and safe relative paths.
- PDF viewer and managed Exam Import.
- Normalized multi-region question capture and preview.
- Answer domain, answer-region capture and persistence.
- Versioned curriculum and Excel curriculum import.
- SQLite runtime, foreign keys and migrations.
- Syllabus-version selection.
- Descriptor and Subtopic historical-to-current mapping/review.
- Sprint 01 legacy metadata import.
- Sprint 02 directional curriculum applicability.
- Sprint 03 hierarchical retrieval, asynchronous search and stored-question preview.
- Sprint 04 backup, restore and data safety.
- Sprint 05 deterministic revision corpus and static hierarchical HTML export.

## Milestone 1 — Sprint 04: Backup, Restore and Data Safety

**IMPLEMENTED / COMPLETE**

### Goal

Protect manual mapping, capture and metadata work before the corpus grows further.

### Deliverables

- versioned backup package;
- SQLite-consistent snapshot;
- manual full backup containing database + managed PDFs + curriculum files;
- automatic lightweight DB backup on normal close;
- safe staging and validation;
- restore validation before replacement;
- pre-restore safety protection;
- explicit failure/recovery behaviour;
- retention;
- fresh-instance round-trip restore tests.

### Acceptance boundary

A ZIP is not a successful backup unless the restored application can reopen the data and reconstruct representative questions/mappings/regions.

## Milestone 2 — Corpus-completion and mapping reconciliation

**PROPOSED / PARALLEL HIGH PRIORITY**

### Capture work queue

Build a dedicated queue for imported questions requiring:

- question regions;
- answer regions;
- both;
- possible preamble/shared-context capture.

Filters should include Subject, provider, year, booklet and completion state.

### Curriculum mapping data work

The 5 September `Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` adds a useful reviewed-analysis artifact but is not established as reconciled with SQLite.

Required work:

- manually review all Low/no-match (`YES`) rows;
- check Medium (`CHECK`) rows;
- confirm genuinely new/removed content;
- decide whether richer mapping-type/notes metadata needs persistence;
- reconcile/import confirmed pairs into the application's mapping records without overwriting historical question classifications;
- report coverage after reconciliation.

### Import/corpus audit

- report created/identical/conflicting imports;
- identify missing PDFs/booklets;
- identify unresolved classifications;
- validate additional workbook variants;
- retain exact historical metadata on reruns.

## Milestone 3 — Resolve classification and shared-context gaps

**PROPOSED / DESIGN DECISIONS REQUIRED**

### 3.1 Multiple original classifications

The current model stores one best-fit original classification, but the early Neap exercise demonstrated a real question fitting three descriptors.

Decide explicitly whether:

- one best-fit classification is permanent policy; or
- `Question` needs multiple original Subtopic/Descriptor classifications.

If multiple classifications are adopted, update:

- domain invariants;
- SQLite schema/repositories;
- capture/import UI;
- current-applicability derivation;
- retrieval duplicate handling;
- export placement/provenance;
- tests.

Do not confuse multiple original classifications with one historical classification mapping to several current nodes.

### 3.2 Out-of-scope source-question disposition

Decide whether source-exam ingestion needs an explicit reviewed “out of scope / do not bank” disposition so coverage audits can distinguish deliberately excluded questions from unprocessed questions.

### 3.3 Multipart/shared context

Resolve a generalized model for questions such as `21a`, `21b`, `21c` that may share stems, tables, graphs or depend on prior parts.

Candidate concepts include:

- shared source regions;
- ordered content blocks;
- part/dependency relationships;
- keep-together output behaviour;
- repeated ordinary regions versus a separately persisted shared source section.

A capture-time “pin/reuse region” interaction may follow, but should not dictate persistence semantics.

## Milestone 4 — Sprint 05: Hierarchical Revision Corpus and Static HTML Export

**IMPLEMENTED / COMPLETE**

### Goal

Turn the implemented current-curriculum retrieval semantics into a deterministic student-facing revision corpus.

### Corpus rules

- traverse the current syllabus deterministically;
- include directly current and confirmed-mapped historical questions;
- preserve original provenance;
- preserve question-region order;
- avoid duplicate placement within the same current node;
- keep Subtopic-level evidence at Subtopic precision;
- respect any later decision on multiple original classifications;
- distinguish known questions from exportable questions;
- expose completeness statistics.

### Numbering and attribution

The early descriptor-batch prototype established a useful output convention:

- generated sequential numbering within the exported resource;
- retained source exam/provider/year/booklet/question identity;
- matching question/solution numbering where solution resources are generated.

### Static asset generation

Use build/export-time PDFBox rendering for web assets:

```text
managed PDF + regions
        -> rendered PNG/web assets
        -> ordinary static HTML
```

Requirements:

- rendered images are derived artefacts;
- deterministic asset names;
- source attribution;
- answer/marking rendering where available;
- explicit behaviour for missing answers;
- sensible page/resource boundaries;
- repeatable regeneration.

**Do not make browser-side PDF.js the primary delivery architecture.**

The generated HTML/assets layer should work independently and become Sprint 06's content layer.

### Implemented outcome

The completed export provides:

- deterministic current-curriculum corpus construction;
- generated question and answer assets;
- Subject -> Unit -> Topic navigation;
- selectable Subtopic pages;
- omission of empty Descriptor sections;
- direct Subtopic question placement;
- answer disclosure and missing-answer presentation;
- source attribution;
- completeness statistics;
- staging and validation before publication;
- JavaFX export selection and background execution;
- real rendering progress;
- successful real Chemistry browser acceptance.

This static content layer is now ready for Sprint 06 packaging.

## Milestone 5 — Sprint 06: SCORM Package Generation and QLearn Validation

**NEXT / PROPOSED**

### Goal

Generate the final QLearn-importable SCORM ZIP directly from the application.

### Deliverables

- `imsmanifest.xml` at package root;
- chosen schema/support files if required;
- hierarchical static HTML;
- generated question assets;
- answer/marking assets;
- CSS/JavaScript where needed;
- manifest resources matching actual package contents;
- deterministic valid resource identifiers;
- portable relative paths;
- validation before success;
- final ZIP.

### Content rule

Full authoritative source exam PDFs should not normally be placed inside the SCORM package. The package should use derived portable web assets unless a later QLearn requirement proves otherwise.

### External confirmation

Before implementation is locked down:

- confirm the QLearn-supported SCORM version/profile;
- obtain authoritative requirements or a known-good package where practical;
- test the real import/render path;
- encode QLearn-specific restrictions as exporter rules.

## Milestone 6 — Retrieval, mapping and UI hardening

**PROPOSED / BACKLOG**

### JavaFX/TestFX

- isolate focus-sensitive UI tests;
- add stale search/preview/disposal regressions;
- keep infrastructure flakiness separate from real feature defects.

### Retrieval/domain

- strengthen result invariants;
- broaden SQLite integration cases;
- verify same-Subject isolation/restart reconstruction;
- benchmark broad searches before optimizing.

### Mapping

- decide whether DB triggers/constraints are warranted for mapping invariants;
- preserve explicit human confirmation;
- improve coverage reporting/suggestion review as needed.

### UI polish

Maintain a separate polish list for spacing/alignment/visual consistency unless a problem blocks workflow, corrupts data or makes an essential control unusable.

## Milestone 7 — Rich Question Bank Browser and Administration

**PROPOSED / LATER**

Add richer filters, metadata correction, source inspection, completeness indicators, capture queues and administrative reconciliation once the revision-export critical path is stable.

This is also a natural place to expose deliberate out-of-scope dispositions if that requirement is adopted.

## Milestone 8 — Exam Builder

**PROPOSED / LATER**

- select questions into assessments;
- order questions/sections;
- total marks;
- saved drafts;
- answer/marking inclusion choices;
- reproducible question ordering.

Exam Builder should consume the bank; it must not become a dependency of revision/SCORM export.

## Milestone 9 — Printable assessment and solution output

**PROPOSED / LATER**

### Goals

- generated sequential numbering;
- original source attribution where appropriate;
- matching question/solution numbering;
- page-break/layout controls;
- school headers/instructions;
- marking versions.

### Rendering direction

Prefer vector-preserving inclusion of original PDF content where practical. The recorded design is direct source-PDF page/viewport clipping through LaTeX `graphicx` or an equivalent approach, rather than using raster web images as the print master.

## Milestone 10 — Clipboard/image attachment questions

**PROPOSED / LATER**

Support questions whose durable source is not an exam PDF, for example a Windows Snipping Tool capture from an ephemeral webpage/document.

### Design work

- question content should support text plus zero or more images/attachments;
- clipboard paste (`Ctrl+V` / JavaFX Clipboard);
- optional drag/drop PNG/JPEG;
- choose BLOB versus managed-file persistence;
- include attachments in backup/restore;
- define HTML/PDF/SCORM rendering;
- define provenance/source metadata for non-PDF content;
- keep OCR optional.

Do not weaken PDF-region provenance for normal exam questions to implement this extension.

## Milestone 11 — Packaging and deployment

**PROPOSED / LATER**

### Desktop packaging

- self-contained installer/executable, likely via `jpackage`;
- Maven-driven packaging;
- writable DB/data root outside the installed application directory;
- migration/backup safety across upgrades.

### Faculty sharing

- do not use a concurrently edited SharePoint-synchronised SQLite file;
- SharePoint can hold backups, exports, source-document distributions and published resources;
- consider controlled import/export/merge for coordinators;
- consider IT-supported central DB only when multi-user requirements justify it.

## Milestone 12 — Assisted classification and automation

**PROPOSED / FUTURE**

Possible tools:

- extract/search text from PDF regions;
- rank descriptor suggestions;
- detect headings such as `QUESTION 12` and suggest region boundaries;
- detect part labels/dependency phrases as suggestions;
- OCR pasted image questions;
- duplicate detection;
- curriculum coverage analytics;
- difficulty metadata;
- assessment-blueprint suggestions.

### Governance rule

Automation suggests; teachers confirm. Do not silently create authoritative curriculum classifications or confirmed mappings.

## Recommended order

```text
Sprint 04 — Backup / Restore / Data Safety
        |
        +------ parallel capture completion + mapping review/reconciliation
        |
        +------ resolve multi-classification/shared-context decisions as needed
        |
        v
Sprint 05 — Revision Corpus + static HTML/assets
        |
        v
Sprint 06 — SCORM ZIP + QLearn validation
        |
        +------ measured retrieval/UI hardening
        |
        v
Richer bank management
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
