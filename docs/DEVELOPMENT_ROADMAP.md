# Exam Question Bank — Development Roadmap

> **Reference date:** 4 September 2026  
> **Version: 3**  
> **Repository location:** `docs/DEVELOPMENT_ROADMAP.md`

---

## 1. Project goal

Build a desktop **Exam Question Bank** for school science subjects.

Chemistry is the first subject used for development, but the core design must remain subject-neutral. Physics, Biology and other science subjects should be supported through curriculum and question-bank data rather than subject-specific code.

The application has two major long-term purposes:

1. maintain a durable, curriculum-aware bank of examination questions and marking material;
2. turn that bank into useful outputs for teachers and students.

The immediate product priority is now student revision rather than assessment assembly.

The near-term outcome is:

> Produce a complete, meaningful, curriculum-hierarchical corpus of stored questions and export it directly from the application as a SCORM package that can be loaded into QLearn.

Assessment assembly / Exam Builder remains a later capability.

The original examination and marking PDFs remain the authoritative source. Stored regions, curriculum mappings, metadata and generated outputs derive from those managed source documents.

---

## 2. Current strategic priority

There are approximately seven weeks until final examinations.

Development should therefore prioritise features that convert the existing and rapidly growing question corpus into something students can use for revision.

The critical development path is:

```text
Protect the data
        ↓
Build a deterministic hierarchical question corpus
        ↓
Generate student-facing hierarchical HTML/assets
        ↓
Generate a valid SCORM ZIP directly in the application
        ↓
Import into QLearn
        ↓
Iterate as more question/answer regions are captured
```

Question-region completion, answer capture and mapping review continue in parallel as beta/data-completion work.

They improve the corpus but should not displace the export path as the main development sequence.

---

## 3. Core design principles

### 3.1 Science-subject neutral

- Core model and service logic must not contain Chemistry-specific assumptions.
- Subject, SyllabusVersion, Unit, Topic, Subtopic and Descriptor are data.
- Curriculum hierarchy rules must work across science subjects where the stored hierarchy is valid.
- Chemistry remains the development and first production dataset.

### 3.2 SQLite is the runtime database

- Excel is an import/exchange format, not the live application database.
- SQLite remains the persistent runtime store.
- Schema changes are handled through migrations.
- The application must remain realistic for a school environment without requiring a database server.

### 3.3 Managed source PDFs are durable application data

- Examination and marking PDFs live under the configured managed data root.
- The database stores portable relative paths rather than machine-specific absolute paths.
- Original PDFs remain authoritative.
- Cropped/generated question images are derived artefacts, not the database of record.

### 3.4 Questions may contain multiple ordered regions

A stored question may occupy one or many regions and pages. Region order is meaningful and must be preserved.

### 3.5 Historical classification is permanent provenance

Questions retain the syllabus classification under which they were originally created or imported.

Confirmed historical-to-current curriculum mappings derive current applicability without rewriting that provenance.

`SUGGESTED`, `NO_MATCH` and unreviewed mappings must never behave as confirmed applicability.

Subtopic-level applicability must never invent Descriptor precision.

### 3.6 Retrieval hierarchy is explicit

The completed retrieval boundary defines current-syllabus search scopes as:

```text
SUBJECT
    → all valid question-classification nodes in the single current syllabus

UNIT
    → all valid question-classification nodes beneath all Topics

TOPIC
    → Descriptor-mode OR Subtopic-mode descendants

SUBTOPIC
    → the Subtopic itself + Descriptor children

DESCRIPTOR
    → exact Descriptor only
```

Questions themselves remain classified only to `SUBTOPIC` or `DESCRIPTOR`.

This same hierarchy should become the basis of the later corpus/HTML/SCORM export rather than inventing a separate export classification model.

### 3.7 Data safety is now a first-class requirement

Curriculum mapping, PDF-region capture, answer-region capture and manual metadata correction are expensive, repetitive work.

The application must protect them through:

- automatic backups on normal application close;
- explicit manual backup;
- validated restore;
- versioned backup format;
- safe handling across future schema/application versions.

### 3.8 SCORM is an application output format

The application itself must produce the final SCORM ZIP.

SCORM generation is not an external post-processing step.

The exporter must:

- generate the student-facing HTML/assets;
- generate a standards-compliant `imsmanifest.xml`;
- place required package files in the correct locations;
- use portable relative resource paths;
- validate the package before completion;
- create the final ZIP ready for import into QLearn.

The exact SCORM version/profile accepted by QLearn must be confirmed before the SCORM implementation is locked down.

---

## 4. Completed development sprints

### Sprint 01 — Legacy Metadata Import

Document:

`docs/design/sprint-01-legacy-metadata-import.md`

Major outcomes:

- legacy Excel metadata import;
- source Exam/ExamBooklet reconstruction;
- preserved historical classification;
- metadata-only questions with zero regions as a valid capture-pending state;
- managed PDF import;
- idempotent/conflict-aware persistence;
- foundations for later question/answer region completion.

### Sprint 02 — Directional Curriculum Applicability

Document:

`docs/design/sprint-02-curriculum-applicability-sprint.md`

Major outcomes:

- explicit historical → current mapping direction;
- Descriptor and Subtopic mapping;
- one-to-many mappings;
- confirmed/suggested/no-match review semantics;
- preserved original classification;
- derived current applicability.

### Sprint 03 — Question Retrieval

Document:

`docs/design/sprint-03-question-retrieval-sprint.md`

Major outcomes:

- current curriculum retrieval for Subject, Unit, Topic, Subtopic and Descriptor;
- direct-current and confirmed historical-mapped retrieval;
- explicit hierarchy expansion;
- duplicate prevention;
- SQLite-backed retrieval;
- provenance-preserving results;
- asynchronous search UI;
- stored question preview;
- lifecycle/stale-result protection;
- merge-readiness review completed.

---

## 5. Roadmap at a glance

| Stage | Objective | Current position |
|---|---|---|
| Foundation | Toolchain, JavaFX, PDFBox, SQLite, migrations | Complete / ongoing maintenance |
| Capture | Question and answer region capture | Working; beta refinement continues |
| Curriculum | Versioned curriculum import, mapping and applicability | Working |
| Legacy migration | Import previous workbook metadata | Working; refinements remain |
| Retrieval | Current-curriculum hierarchical question retrieval | **Sprint 03 complete** |
| Data safety | Backup, restore and version resilience | **Sprint 04 — next** |
| Revision corpus | Hierarchical corpus + student HTML/assets | Sprint 05 |
| SCORM / QLearn | Application-generated SCORM ZIP and QLearn validation | Sprint 06 |
| Corpus completion | Capture missing regions/answers and resolve preambles | Parallel beta/data work |
| Full browser/edit | Rich question-bank management | Later |
| Exam Builder | Assemble selected questions into assessments | Later |
| Printable output | Assessment HTML/PDF and answer documents | Later |
| Deployment | Multi-user/school operational model | Later |
| Automation | Assisted classification, OCR and analytics | Future |

---

## 6. Sprint 04 — Backup, Restore and Data Safety

### Goal

Protect the question bank before significantly more irreplaceable manual data is accumulated.

The application should support:

- an explicit restorable full backup;
- a lightweight automatic backup on every normal application close;
- safe restore;
- a versioned backup format;
- validation before destructive operations.

### Backup scope

A full backup should be capable of preserving the complete managed dataset:

```text
database
+ managed source PDFs
+ managed curriculum source files
```

Machine-specific configuration should not be treated as portable application data.

Automatic close backups should favour frequent protection of the SQLite database without duplicating the entire PDF library on every exit.

### Key rule

Do not use a naive filesystem copy of a database that may be active.

The backup implementation must create a SQLite-consistent snapshot and prove that the snapshot can be reopened and read.

### Sprint document

`docs/design/sprint-04-backup-restore-data-safety.md`

---

## 7. Sprint 05 — Hierarchical Revision Corpus and HTML Export

### Goal

Turn retrieval into a deterministic student-facing corpus organised by the current curriculum.

The export hierarchy should be derived from the same semantics already established for retrieval.

A representative structure is:

```text
Subject
└── Unit
    └── Topic
        ├── Descriptor
        │   └── Questions
        │
        └── Subtopic
            ├── Questions classified/applicable at Subtopic level
            └── Descriptor
                └── Questions
```

A historical question may appear under a current node through confirmed mapping, but its original classification remains provenance.

A Subtopic-classified question must not be copied into every Descriptor beneath the Subtopic.

### Corpus responsibilities

- traverse the Subject's current syllabus deterministically;
- associate each exportable question with valid current applicability nodes;
- preserve ordering;
- preserve provenance;
- avoid duplicate placement within the same classification node;
- identify questions that are known but not yet exportable because question regions are missing;
- expose completeness statistics.

Example:

```text
Chemistry
438 applicable questions
371 exportable
67 awaiting question-region capture
```

### HTML responsibilities

Generate ordinary student-facing HTML and assets before SCORM packaging is introduced.

The HTML export should establish:

- curriculum navigation;
- question rendering;
- answer/marking-material rendering where available;
- behaviour for questions without answers;
- source attribution;
- deterministic asset naming;
- sensible page/resource boundaries;
- repeatable regeneration as the corpus grows.

The HTML output is both a useful intermediate product and the content layer that Sprint 06 will package into SCORM.

---

## 8. Sprint 06 — SCORM Package Generation and QLearn Validation

### Goal

Generate the final SCORM ZIP directly from the application and prove that QLearn accepts and renders it correctly.

The application should produce:

```text
SCORM ZIP
├── imsmanifest.xml
├── required SCORM schema/support files
├── curriculum navigation/content HTML
├── question assets
├── answer/marking assets
├── CSS/JavaScript where required
└── other package resources declared by the manifest
```

### Required principles

- `imsmanifest.xml` belongs at the package root.
- Manifest organisations/resources must match the actual package contents.
- All internal references must be portable relative paths.
- Resource identifiers must be deterministic and valid.
- Every required packaged file must be declared as required by the chosen SCORM profile.
- The package must be validated before the application reports successful export.
- The ZIP itself is the application deliverable.

### QLearn compatibility

Before implementation is finalised:

- establish the exact SCORM version/profile accepted by QLearn;
- obtain an authoritative QLearn requirement or a known-good package where practical;
- test import into the real QLearn environment;
- record any QLearn-specific restrictions as explicit exporter rules rather than ad hoc fixes.

### Regeneration workflow

The intended operational loop is:

```text
capture / classify / map more questions
        ↓
question bank improves
        ↓
regenerate hierarchical corpus
        ↓
generate new SCORM ZIP
        ↓
update QLearn resource
```

---

## 9. Parallel beta and corpus-completion work

Question and answer capture should continue while Sprints 04–06 are developed.

Important parallel work includes:

- attach question regions to legacy metadata-only questions;
- attach answer/marking regions;
- verify historical classifications;
- complete/verify 2019 → 2025 mappings;
- resolve missing source documents;
- determine and implement preamble semantics when required;
- test search and preview against real data.

This work increases the number of exportable questions but does not need to block the corpus/export architecture.

---

## 10. Question Bank Browser — later

The existing question-search UI is sufficient as the retrieval proof and basic inspection tool.

A richer browser remains valuable later for:

- broader filters;
- metadata editing;
- classification correction;
- source inspection;
- completeness indicators;
- region-capture work queues;
- administrative maintenance.

It is not the immediate priority while student revision export has a fixed near-term deadline.

---

## 11. Exam Builder — deliberately later

Assessment assembly remains a legitimate long-term capability, but it is no longer on the immediate critical path.

Later work may include:

- selecting questions into a new assessment;
- ordering;
- total marks;
- headings/sections;
- saved drafts;
- printable question papers;
- marking documents.

Do not make Exam Builder dependencies part of the SCORM/revision export path.

The corpus exporter should operate over the question bank and current curriculum directly.

---

## 12. Printable HTML/PDF assessment output — later

The application already contains early HTML/PDF output foundations.

Assessment-oriented rendering can be expanded after the student revision/SCORM pathway is working.

Later capabilities include:

- exam numbering;
- page breaks;
- printable layout;
- headers/instructions;
- answer/marking versions;
- school printer quality checks.

---

## 13. Legacy migration and capture follow-up

The legacy import foundation is working.

Remaining work is maintained in `docs/design/backlog.md`, including:

- capture-completion workflow refinements;
- preamble semantics;
- import reconciliation reporting;
- additional real-world workbook variants;
- optional answer/marking PDF import refinements.

Do not broaden the importer speculatively before real files demonstrate the need.

---

## 14. Data maintenance and operational deployment

After Sprint 04, backup and restore are no longer deferred deployment concerns; they are part of the single-user application's core safety model.

Later school deployment questions still include:

- shared SQLite versus local databases;
- SharePoint/network storage behaviour;
- simultaneous users;
- installation/packaging;
- source-document access control;
- school IT hosting options.

A robust single-user application remains the prerequisite.

---

## 15. Assisted classification and automation — future

Possible later capabilities include:

- curriculum classification suggestions;
- mapping suggestions;
- OCR/text-layer extraction;
- question-boundary suggestions;
- duplicate detection;
- coverage analytics;
- difficulty metadata;
- exam-blueprint suggestions.

Automation should suggest where uncertainty exists. It must not silently create authoritative curriculum classifications or confirmed mappings.

---

## 16. Backlog and technical debt

The authoritative deferred-work list is:

`docs/design/backlog.md`

Important backlog themes currently include:

- TestFX stability;
- additional asynchronous search regressions;
- retrieval-domain invariant hardening;
- broad-search performance benchmarking;
- additional SQLite integration coverage;
- mapping hardening;
- preamble design;
- legacy import audit/reporting.

These should be pulled into future sprints deliberately rather than extending completed sprints indefinitely.

---

## 17. Revised MVP / near-term release target

For the next release target, the project is useful when a teacher can:

- maintain the managed source PDFs;
- import/capture question metadata;
- capture one or more question regions;
- associate answer/marking material where available;
- preserve historical classification;
- derive current applicability;
- retrieve questions by current curriculum hierarchy;
- preview stored questions;
- back up and restore the question-bank data safely;
- generate a complete hierarchical revision corpus;
- generate student-facing HTML/assets;
- generate a valid SCORM ZIP directly from the application;
- load that package into QLearn successfully.

This is now a more important near-term milestone than building an Exam Builder.

---

## 18. Development discipline

For each reasonably self-contained change:

1. start from an up-to-date branch;
2. make one focused change;
3. add/update focused unit tests;
4. add integration tests when behaviour crosses SQLite/repository/service boundaries;
5. review public API Javadocs when introducing or materially changing public classes/methods;
6. test the focused change;
7. run the complete suite before major checkpoints/merge;
8. run TestFX/UI checks where the change affects UI behaviour;
9. inspect visual behaviour manually where appropriate;
10. commit logical checkpoints rather than individual files;
11. push before requesting review of current branch code;
12. perform a merge-readiness review for substantial feature branches;
13. merge only when the feature is stable;
14. update roadmap/sprint documentation when priorities or completed boundaries change materially.

---

## 19. Recommended development order from the current point

```text
Sprint 03 — Question Retrieval                 COMPLETE
        ↓
Sprint 04 — Backup, Restore and Data Safety    NEXT
        ↓
Sprint 05 — Hierarchical Corpus + HTML
        ↓
Sprint 06 — SCORM / QLearn Export
        ↓
QLearn beta testing + corpus completion
        ↓
Question Bank Browser expansion
        ↓
Assessment Assembly / Exam Builder
        ↓
Printable assessment output
```

In parallel:

```text
capture missing question regions
capture answer regions
verify curriculum mappings
resolve preamble cases
        ↓
larger and better revision corpus
```

The best near-term order is therefore:

**protect the work → organise the corpus → export it to QLearn → expand the corpus → build later teacher-facing assembly features.**
