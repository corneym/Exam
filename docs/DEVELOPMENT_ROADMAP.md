# Exam Question Bank — Development Roadmap

> **Reference date:** 6 September 2026  
> **Version:** 5
> **Repository location:** `docs/DEVELOPMENT_ROADMAP.md`

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves authoritative examination content, historical curriculum provenance and reusable marking material, then turns that bank into useful outputs for teachers and students.

Chemistry is the first full development dataset, but core model/service/persistence code must remain subject-neutral.

Long-term purposes:

1. maintain a durable curriculum-aware bank of questions and marking material;
2. generate student revision resources;
3. later assemble teacher assessments and printable marking resources.

The immediate product priority remains student revision rather than Exam Builder.

## 2. Current strategic path

```text
Protect irreplaceable data
        ↓
Complete/reconcile enough question + mapping data
        ↓
Build deterministic current-curriculum corpus
        ↓
Generate static HTML/assets
        ↓
Generate/validate SCORM ZIP
        ↓
Import into QLearn
        ↓
Iterate as corpus grows
```

Capture completion and mapping review continue in parallel.

## 3. Current architecture constraints

### 3.1 Subject-neutral

Subject, syllabus version and curriculum nodes are data. Do not hard-code Chemistry or the 2019 Unit.Topic.Subtopic shape into reusable core logic.

### 3.2 SQLite runtime

Excel is import/exchange only. SQLite is the live datastore with migrations and referential integrity.

### 3.3 Managed source PDFs are authoritative

Persist portable relationships/relative paths beneath `data.root`. Rendered images are derived.

### 3.4 Ordered normalized regions

Questions and written-response answer material may use one or many source regions. Region order matters; coordinates remain independent of render DPI.

### 3.5 Historical provenance is preserved

Historical classification is not rewritten to current classification. Confirmed historical -> current mappings derive applicability.

### 3.6 Retrieval hierarchy is already defined

Sprint 03 established Subject/Unit/Topic/Subtopic/Descriptor search semantics. Export should reuse them rather than invent a second curriculum interpretation.

### 3.7 Data safety is first-class

Mapping review and region capture are expensive manual work; Sprint 04 precedes large-scale export/corpus expansion.

### 3.8 Static web delivery

For HTML/SCORM, render required PDF regions to web assets at export time. Do not make browser-side PDF rendering the primary architecture. Full source PDFs should not normally be packaged into SCORM.

### 3.9 Print delivery should preserve vector source content where practical

Later printable PDF generation should evaluate direct page/viewport clipping from the original PDFs rather than reusing raster web assets as the print master.

### 3.10 One-best-fit classification is current implementation, not a proven final requirement

The current `Question` stores one original Subtopic/Descriptor classification. Early real classification work demonstrated multi-descriptor questions. This discrepancy must be resolved explicitly before richer authoring/export assumptions harden around it.

## 4. Completed sprints

### Sprint 01 — Legacy Metadata Import

Complete:

- legacy workbook metadata import;
- Exam/ExamBooklet reconstruction;
- text question codes;
- marks;
- historical classification preservation;
- metadata-only zero-region questions;
- MCQ text answers;
- managed source-document handling;
- idempotent/conflict-aware import.

### Sprint 02 — Directional Curriculum Applicability

Complete:

- historical -> current direction;
- Descriptor and Subtopic mappings;
- one-to-many mappings;
- confirmed/suggested/no-match review semantics;
- provenance-preserving derived applicability.

### Sprint 03 — Question Retrieval

Complete:

- Subject/Unit/Topic/Subtopic/Descriptor current-curriculum retrieval;
- direct-current + confirmed-mapped historical results;
- explicit hierarchy expansion;
- duplicate prevention;
- SQLite repository/service retrieval;
- asynchronous search UI;
- stored question preview;
- lifecycle/stale-result protection.

## 5. Current data asset added after Roadmap v3

On 5 September 2026 a standalone `Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` was produced from supplied source workbooks.

It records normalized one-to-many pairs and coverage information for 98 2019 descriptors and 120 2025 descriptors.

The application's mapping subsystem already exists, but this new workbook is not established as reconciled/imported into SQLite. Treat review/reconciliation as parallel high-priority data work, not as a completed application feature.

## 6. Sprint 04 — Backup, Restore and Data Safety

**Status: complete — merged into `main` on 6 September 2026.**

Detailed design: `docs/design/sprint-04-backup-restore-data-safety.md`.

Core deliverables:

- backup format v1;
- SQLite-consistent snapshot;
- automatic DB backup on normal close;
- manual full backup;
- validation/staging;
- bounded retention;
- validated safe restore;
- pre-restore protection;
- restart boundary after successful restore;
- round-trip fresh-instance tests.

Sprint 04 is complete. Its detailed sprint design remains the historical record of the implemented data-safety work.

## 7. Parallel corpus-completion work

While Sprint 04–06 development proceeds:

- attach question regions to imported metadata-only questions;
- attach answer/marking regions;
- resolve missing source documents;
- verify historical classification;
- review `YES` and `CHECK` rows in the 5 September mapping workbook;
- reconcile confirmed mapping pairs with SQLite;
- validate larger real datasets;
- build capture-completion/audit workflows.

## 8. Design decisions to resolve before they become export constraints

### 8.1 Multiple original classifications

The early Neap prototype proved that one question may be meaningful against several descriptors. The current domain stores one best-fit classification.

Before a rich browser/editor or final export model assumes one classification forever, decide whether to retain the simplification or implement many-to-many original classification.

### 8.2 Shared context and multipart dependency

Current part codes (`21a`, `21b`, etc.) are valid question identities, and each question can hold multiple regions. That does not yet model all shared stems, tables, diagrams or semantic dependencies.

Resolve persistence/output semantics before adding complex authoring automation.

### 8.3 Out-of-scope source questions

Decide whether ingestion/audit needs an explicit disposition so deliberately excluded questions are distinguishable from unprocessed source questions.

These decisions can be documented/backlogged without blocking Sprint 04. If Sprint 05 export can operate correctly with the current one-best-fit/simple-region model, implementation may be scheduled later; but the limitation must remain explicit.

## 9. Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

**Status: complete — implemented and real-data accepted on 6 September 2026.**

Detailed design: `docs/design/sprint-05-hierarchical-revision-corpus-static-html-output.md`.

### Goal

Turn Sprint 03 retrieval into a deterministic student-facing corpus under the current curriculum.

### Corpus requirements

- traverse the single current syllabus deterministically;
- reuse existing retrieval/applicability semantics;
- preserve historical provenance;
- preserve region order;
- avoid duplicate placement within one current node;
- preserve Subtopic precision;
- expose known/exportable/incomplete counts;
- define behaviour for missing answers.

### Output numbering/provenance

Retain the pattern proven in the earliest descriptor-batch prototype:

- generated sequential numbering for the new resource;
- original exam/provider/year/booklet/question source shown as provenance;
- compatible numbering between question and solution resources.

### Static rendering rule

For web delivery:

```text
source PDF + stored regions
        -> PDFBox rendering at export time
        -> deterministic web image assets
        -> static HTML
```

Rendered assets are derived and can be rebuilt. The source PDFs remain managed application data rather than ordinary SCORM/web assets.

### HTML responsibilities

- curriculum navigation;
- question display;
- answer/marking display when available;
- source attribution;
- deterministic asset naming;
- repeatable regeneration;
- sensible resource/page boundaries.

### Implemented outcome

Sprint 05 delivered:

- deterministic transient revision-corpus construction;
- derived question and answer-region assets;
- Subject and Unit navigation pages;
- Topic pages;
- selectable Subtopic pages where the curriculum uses Subtopics;
- omission of empty Descriptor sections;
- question cards with generated numbering, marks and provenance;
- native answer disclosure and missing-answer handling;
- staged export and reference validation;
- JavaFX export workflow;
- genuine rendering progress feedback;
- real Chemistry browser acceptance.

The static content layer is now the input boundary for Sprint 06.

## 10. Sprint 06 — SCORM Package Generation and QLearn Validation

**Status: next development sprint.**

### Goal

Generate the final SCORM ZIP directly from the application and prove QLearn compatibility.

Package should include:

```text
imsmanifest.xml
+ required profile/schema support
+ static HTML
+ derived question/answer assets
+ CSS/JavaScript
```

Do not normally include full authoritative source exam PDFs.

Required work:

- confirm supported QLearn SCORM profile/version;
- generate deterministic manifest resources/identifiers;
- use portable relative paths;
- validate package contents/references;
- create final ZIP;
- perform real QLearn import/render exercise;
- record any QLearn-specific rule explicitly.

## 11. After SCORM — rich bank management

Later question-bank management may add:

- broader filtering;
- metadata correction;
- multiple-classification editing if adopted;
- source inspection;
- completeness indicators;
- out-of-scope disposition if adopted;
- capture queues;
- mapping/import reconciliation reports.

## 12. Exam Builder — later

Later capabilities:

- select questions;
- order questions/sections;
- calculate total marks;
- save drafts;
- include/exclude marking material;
- reproducible assessment generation.

Do not make Exam Builder a dependency of revision/SCORM output.

## 13. Printable assessment / solution output — later

Build on the same bank/provenance rather than recreating a second question model.

Requirements may include:

- sequential generated question numbering;
- matching solution numbering;
- page breaks/layout;
- school headers/instructions;
- source attribution;
- vector-preserving source clipping.

The recorded preferred implementation direction is direct original-PDF clipping through LaTeX `graphicx` or equivalent rather than raster intermediates.

## 14. Non-PDF/ephemeral image content — later

Support a separate content-source extension for transient documents/web pages:

- clipboard paste from Windows Snipping Tool;
- optional drag/drop PNG/JPEG;
- question text plus image attachments;
- BLOB vs managed-image-file storage decision;
- backup/export integration;
- provenance metadata;
- optional OCR later.

This feature extends the content model; it should not replace source-PDF regions for normal exam material.

## 15. Packaging/deployment — later

### Desktop

Investigate `jpackage` for a self-contained application. Keep writable DB/data outside installed files.

### Faculty sharing

SharePoint may hold backups, source distributions, exports and published packages.

Do not use a live SharePoint-synchronised SQLite database for concurrent editing.

If real simultaneous multi-user requirements emerge, evaluate controlled merge/import or an IT-supported server database.

## 16. Automation — future

Possible assistance:

- PDF text extraction for search/classification;
- descriptor ranking;
- question heading/boundary suggestions;
- part/dependency suggestions;
- OCR for image attachments;
- duplicate detection;
- coverage analytics;
- difficulty/blueprint suggestions.

Automation may propose; a teacher confirms authoritative classifications/mappings.

## 17. Backlog and technical debt

Authoritative file: `docs/design/backlog.md`.

Important current backlog themes:

- TestFX stability;
- async search regressions;
- retrieval-domain/integration hardening;
- capture-required queue;
- 5 September mapping reconciliation;
- multiple original classification decision;
- shared-context/multipart semantics;
- out-of-scope disposition;
- import audit;
- packaging/deployment;
- future clipboard image content.

## 18. Development discipline

For self-contained changes:

1. start from up-to-date branch state;
2. make one focused change;
3. add/update unit tests;
4. add integration tests across SQLite/repository/service boundaries;
5. update public API documentation when appropriate;
6. run focused tests;
7. run complete clean suite at major checkpoints;
8. run TestFX/manual checks for UI changes;
9. commit logical checkpoints;
10. push before repository review;
11. perform merge-readiness review for substantial branches;
12. update sprint/roadmap/current-status documentation when boundaries change.

Codex is best reserved for repository-wide review, tests and large mechanical refactors; project chats remain the primary place for design/schema decisions unless the working method changes.

## 19. Recommended development order

```text
Sprint 04 — Data safety
        |
        +------ capture completion + mapping reconciliation
        |
        +------ document/resolve classification/shared-context gaps
        |
        v
Sprint 05 — current-curriculum corpus + static HTML/assets
        |
        v
Sprint 06 — SCORM + QLearn validation
        |
        v
Rich bank management
        |
        v
Exam Builder + printable assessment/solution output
        |
        +------ clipboard/image-content extension as needed
        |
        v
Packaging/deployment/multi-user decisions
        |
        v
Assisted automation/analytics
```
