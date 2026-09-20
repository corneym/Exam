# Exam Question Bank — Development Roadmap

> **Reference date:** 20 September 2026  
> **Version:** 15  
> **Repository location:** `docs/DEVELOPMENT_ROADMAP.md`
>
> This is the single canonical project roadmap.

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves
authoritative examination content, historical curriculum provenance and reusable
marking material, then turns that bank into useful resources for students and
teachers.

Chemistry remains the first full development dataset. Core model, repository,
service, persistence and output logic remain subject-neutral.

Long-term purposes are:

1. maintain a durable curriculum-aware bank of Questions and marking material;
2. generate student revision resources;
3. assemble teacher assessments and printable marking resources;
4. later support broader deployment, collaboration and assisted workflows.

## 2. Current strategic path

```text
Completed data safety
        ↓
Completed deterministic revision HTML foundation
        ↓
Completed SCORM 1.2 / QLearn validation
        ↓
Completed shared-context/capture/edit foundations
        ↓
Completed curriculum/corpus tooling
        ↓
Completed Sprint 09 sustained-use capture/correction work
        ↓
Completed protected-main / CI-governance validation
        ↓
Sprint 10 capture hardening + revision-output refinement
        ↓
Continue real corpus and mapping completion
        ↓
Resolve remaining Question semantics / richer bank management
        ↓
Exam Builder + printable output
        ↓
Packaging / deployment / assisted automation
```

Current implementation work is on `feature/capture-output`.

## 3. Current architecture constraints

### Subject-neutral core

Subject, syllabus version and curriculum nodes are data. Reusable code must not
hard-code Chemistry or one historical hierarchy shape.

### SQLite runtime

Excel is import/exchange only. SQLite is the live datastore with referential
integrity and sequential migrations. The current latest schema version is 8.

### Managed source PDFs are authoritative

Persist portable relationships and relative paths beneath `data.root`. Rendered
images are derived assets, not source-of-truth Question content.

### Ordered normalised regions

Question, Answer and SharedQuestionContext source material may use ordered
normalised PDF regions. Region order matters and coordinates remain independent
of render DPI.

### SourceQuestion and SharedQuestionContext are distinct

`SourceQuestion` represents original multipart identity.
`SharedQuestionContext` represents reusable source material.

Shared context does not imply multipart identity and is not restricted to one
response type or one curriculum classification. Sprint 10 will expose this
existing domain capability for otherwise independent Questions, including MCQs.

### Historical provenance is preserved

Historical classification is not rewritten to current classification. Confirmed
historical -> current mappings derive applicability.

A Question currently stores one best-fit original classification. Whether direct
multiple original classifications are required remains a future decision.

### Response type belongs to Question

Mixed-response booklets are supported. Runtime Answer behaviour must not depend
on booklet-name inference.

```text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> response type unresolved; ordinary Answer capture blocked
```

Sprint 10 response-type inference is a capture convenience, not a new persisted
semantic rule.

### Presentation choices do not rewrite curriculum data

Revision-output grouping depth, response-type ordering, selected Units, page
numbering and generated-site status are presentation decisions. They must not
rewrite Question classification, applicability mappings, SourceQuestion identity
or SharedQuestionContext identity.

### Long-running UI work must not block JavaFX

Repository persistence/refresh work and PDF transitions that can be slow belong
off the JavaFX application thread. Asynchronous work requires stale-request and
lifecycle protection.

## 4. Completed foundation

### Sprint 01 — Legacy Metadata Import

Complete: legacy workbook import, Exam/Booklet reconstruction, metadata-only
Questions, historical classification, MCQ answer letters where available,
managed source documents and conservative legacy preamble evidence.

### Sprint 02 — Directional Curriculum Applicability

Complete: historical -> current mappings, Descriptor/Subtopic support,
one-to-many mapping, reviewed-state semantics and provenance-preserving derived
applicability.

### Sprint 03 — Question Retrieval

Complete: current-curriculum retrieval across hierarchy scopes, confirmed mapped
historical results, SQLite retrieval, asynchronous Search and stored Question
preview.

### Sprint 04 — Backup, Restore and Data Safety

Complete: versioned backups, SQLite-consistent snapshots, automatic/manual
backup, validated restore, pre-restore protection, rollback and migration
compatibility checks.

### Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

Complete: deterministic revision corpus, derived assets, hierarchical
navigation, generated numbering/marks/provenance, answer disclosure and staged
export validation.

### Sprint 06 — SCORM Package Generation and QLearn Validation

Complete: deterministic SCORM 1.2 single-SCO packaging, manifest/package
validation and successful real QLearn import/launch.

### Sprint 07 — Preamble-aware Question Capture and UI Redesign

Complete: persisted SourceQuestion/shared-context semantics, preamble-aware
capture, correction, syllabus-sensitive classification, Question/Answer editing,
multipart/shared-context revision presentation and asynchronous persistence/PDF
transitions.

### Sprint 08 — Curriculum and Corpus Completion

Complete and merged 17 September 2026: subject-neutral curriculum authoring,
mapping coverage/review, legacy-metadata correction, Question response type,
mixed-response booklet support, response-type-aware completeness and Corpus
Audit.

### Sprint 09 — Capture Workflow and Corpus Correction

Complete and merged to `main` on 20 September 2026. Delivered sustained-use
capture controls, deterministic source ordering, shared-preamble recapture, Exam
correction with managed-file relocation, known-PDF reuse, legacy Question split,
Search scope, Corpus Audit ordering, MCQ Answer-PDF visibility, UI package
refactoring and CI hardening.

Merge commit: `2533586`.

Protected-main workflow was subsequently exercised by pull request #1; Sprint 10
starts from `main` commit `5a1e5a9`.

## 5. Sprint 10 — Capture Hardening and Revision Output Refinement

**ACTIVE DESIGN / IMPLEMENTATION BRANCH:** `feature/capture-output`

Canonical design:

`docs/design/sprint-10-capture-output.md`

Planned slices:

1. repair stale pending PDF-selection state;
2. repair curriculum-code / hierarchy ComboBox synchronisation;
3. add a capture-workspace Working Subject filter;
4. add Written Response capture defaults from part-letter and marks evidence;
5. expose shared-context capture/reuse for independently classified Questions,
   including MCQs;
6. choose revision HTML grouping at Subtopic or Descriptor level;
7. order MCQ presentations before written-response presentations;
8. restart displayed Question numbering at 1 on every generated question page;
9. suppress empty navigation branches and support all-non-empty versus selected
   Unit export;
10. replace internal capture-count status on the student site with useful export
    metadata such as subject, syllabus, generation timestamp and generated
    revision-question count.

Sprint 10 does not change historical classification merely for output, and does
not invent SourceQuestion relationships to share a preamble.

## 6. Real corpus and mapping completion

**ONGOING DATA WORK / HIGH PRODUCT VALUE**

Continue systematic work through mapping review, response-type resolution,
Question source capture, shared-context resolution, Answer completion, metadata
correction and legacy split correction.

Application SQLite review state remains authoritative. The standalone Chemistry
2019 -> 2025 mapping workbook remains reference material.

## 7. Remaining capture/content refinements after Sprint 10

Deferred capture work includes:

- Search Questions dialog position persistence across correction hide/show cycles;
- cleanup of empty managed provider/year directories after successful Exam relocation;
- multi-page automatic shared-preamble capture;
- optional MCQ explanation regions;
- assisted Question-boundary suggestions;
- assisted multipart/dependency suggestions;
- curriculum PDF text/maths/image authoring assistance.

## 8. Remaining revision-output refinements after Sprint 10

Deferred output work includes:

- multipart provenance/decorative simplification;
- deciding whether explicit `Multiple Choice` and `Written Response` subheadings
  add value after ordering is implemented;
- SCORM-dialog parity for any Sprint 10 export option that is initially exposed
  only through Revision HTML;
- later print-oriented/vector-preserving output.

## 9. Remaining Question-model decisions

### Multiple original classifications

Decide whether one-best-fit remains permanent policy or the model becomes
many-to-many. Any change must review schema/repositories, capture/edit UI,
applicability, retrieval duplicate semantics, provenance and output placement.

### Question-level applicability exceptions

A mapping can be valid while a particular historical Question tests content that
did not carry forward. Future work may support explicit Question-level
exclusions without rewriting provenance or the mapping itself.

### Out-of-scope source Questions

Decide whether ingestion/audit needs explicit reviewed states distinguishing
not-yet-reviewed, captured/classified and deliberately out-of-scope material.

## 10. Bank-management and retrieval hardening

Potential later work:

- strengthen retrieval-domain invariants;
- broaden realistic SQLite integration cases;
- benchmark broad searches before optimising;
- add import audit/reconciliation reporting;
- validate additional real-world workbook variants;
- decide whether DB-level mapping enforcement is warranted.

## 11. Exam Builder — later

Capabilities:

- select Questions;
- order Questions/sections;
- calculate total marks;
- save reproducible drafts;
- choose Answer/marking inclusion;
- retain source provenance.

Exam Builder consumes the bank and must not become a dependency of revision or
SCORM output.

## 12. Printable assessment / solution output — later

Potential requirements:

- sequential generated numbering;
- matching solution numbering;
- page breaks and layout control;
- school headers/instructions;
- source attribution;
- vector-preserving source clipping.

## 13. Non-PDF / image content — later

Support a separate content-source extension for transient documents/web pages:

- clipboard paste from Windows Snipping Tool;
- optional drag/drop PNG/JPEG;
- text plus image attachments;
- BLOB versus managed-image-file persistence decision;
- backup/export integration;
- provenance metadata;
- optional OCR later.

Do not replace normal PDF-region provenance with this model.

## 14. Packaging and deployment — later

Investigate `jpackage` for self-contained deployment and keep writable data
outside installed application files.

Do not use a live SharePoint-synchronised SQLite database for concurrent editing.
If simultaneous multi-user editing becomes necessary, evaluate controlled
import/merge or an IT-supported central database.

## 15. Assisted automation — future

Possible assistance includes PDF text extraction, descriptor ranking, Question
boundary suggestions, part/dependency suggestions, OCR, duplicate detection,
coverage analytics and assessment-blueprint suggestions.

Automation proposes; teachers confirm authoritative classifications and mappings.

## 16. Backlog authority

The authoritative inventory of deferred and unresolved work not in Sprint 10 is:

`docs/design/backlog.md`

## 17. Development discipline

For self-contained changes:

1. start from current repository state;
2. design substantial changes before implementation;
3. work in small behaviour-focused slices;
4. add SQLite/repository/service integration tests where boundaries justify it;
5. add UI regressions for actual user-observed UI failures;
6. update API documentation where contracts change;
7. run focused tests during implementation and broader suites at checkpoints;
8. keep slow work off the JavaFX thread;
9. use stale/lifecycle protection for asynchronous UI work;
10. keep commits and the Sprint 10 feature branch coherent;
11. require CI before protected-main merge;
12. update current status, roadmap, backlog and the canonical sprint record when
    implementation state materially changes.
