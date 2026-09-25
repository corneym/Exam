# Exam Question Bank — Development Roadmap

> **Reference date:** 25 September 2026  
> **Version:** 17  
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
Completed Sprint 10 capture hardening + revision-output refinement
        ↓
Protected-main merge closeout for Sprint 10
        ↓
Continue real corpus and mapping completion
        ↓
Resolve remaining Question semantics / richer bank management
        ↓
Exam Builder + printable output
        ↓
Packaging / deployment / assisted automation
```

Sprint 10 implementation is complete on `feature/capture-output`. `main` remains
`5a1e5a9` until protected-main merge closeout. Git/GitHub is authoritative for
the current feature-branch head and CI state.

## 3. Current architecture constraints

### Subject-neutral core

Subject, syllabus version and curriculum nodes are data. Reusable code must not
hard-code Chemistry or one historical hierarchy shape.

### SQLite runtime

Excel is import/exchange only. SQLite is the live datastore with referential
integrity and sequential migrations. The latest supported schema version is 13.

Schema v12 stores Question-specific revision-output exclusions. Schema v13
aligns live physical column names with Shared Context terminology while
historical migrations preserve historical names.

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
response type or one curriculum classification. Independent MCQs may share one
context through sequence-aware, booklet-scoped continuation without acquiring
SourceQuestion identity.

### Historical provenance is preserved

Historical classification is not rewritten to current classification. Confirmed
historical -> current mappings derive applicability.

A Question currently stores one best-fit original classification. Whether direct
multiple original classifications are required remains a future decision.

### Question-specific output applicability is an exception layer

A Question is normally included at every current placement derived from
classification/mapping. A persisted exclusion suppresses only one
Question/current-node placement.

This exception does not rewrite the Question's historical classification or the
curriculum mapping. Absence of an exclusion means normal derived applicability.

### Response type belongs to Question

Mixed-response booklets are supported. Runtime Answer behaviour must not depend
on booklet-name inference.

```text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> response type unresolved; ordinary Answer capture blocked
```

Booklet format constrains/defaults genuinely new capture but does not rewrite
existing Question response type.

### Presentation choices do not rewrite curriculum data

Revision-output grouping depth, response-type ordering, selected Units, page
numbering, generated timestamp and Question-level output exclusions are separate
from persisted curriculum classification/mapping semantics.

### Long-running UI work must not block JavaFX

Repository persistence/refresh work and PDF transitions that can be slow belong
off the JavaFX application thread. Asynchronous work requires stale-request and
lifecycle protection.

## 4. Completed foundation

### Sprint 01 — Legacy Metadata Import

Complete: legacy workbook import, Exam/Booklet reconstruction, metadata-only
Questions, historical classification, MCQ answer letters where available,
managed source documents and conservative legacy shared-context evidence.

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

### Sprint 07 — Shared-context-aware Question Capture and UI Redesign

Complete: persisted SourceQuestion/shared-context semantics,
shared-context-aware capture, correction, syllabus-sensitive classification,
Question/Answer editing, multipart/shared-context revision presentation and
asynchronous persistence/PDF transitions.

### Sprint 08 — Curriculum and Corpus Completion

Complete and merged 17 September 2026: subject-neutral curriculum authoring,
mapping coverage/review, legacy-metadata correction, Question response type,
mixed-response booklet support, response-type-aware completeness and Corpus
Audit.

### Sprint 09 — Capture Workflow and Corpus Correction

Complete and merged to `main` on 20 September 2026. Delivered sustained-use
capture controls, deterministic source ordering, shared-context recapture, Exam
correction with managed-file relocation, known-PDF reuse, legacy Question split,
Search scope, Corpus Audit ordering, MCQ Answer-PDF visibility, UI package
refactoring and CI hardening.

Merge commit: `2533586`.

Protected-main workflow was subsequently exercised by pull request #1; Sprint 10
started from `main` commit `5a1e5a9`.

### Sprint 10 — Capture Hardening and Revision Output Refinement

Implementation complete and verified on `feature/capture-output`.

Delivered:

- pending-selection and booklet-transition state hardening;
- curriculum selector synchronisation;
- Working Subject filtering;
- persisted booklet Question format;
- booklet-specific AnswerFile assignment;
- sequence-aware independent-MCQ Shared Context continuation;
- student-facing revision grouping/order/numbering/navigation/status, including
  safe Descriptor-grouping availability and automatic Subtopic fallback;
- selected-Unit export for both Revision HTML and SCORM;
- persistent breadcrumb navigation on generated Unit, Topic and Subtopic pages;
- Search classification refinement, native-window dirty-close protection and
  stored-region edit navigation;
- Question-specific revision-output exclusions;
- live schema terminology migration to Shared Context;
- deterministic generated-site timestamp metadata;
- public API Javadoc completion across the Sprint 09/10 production boundaries.

- deterministic generated-site timestamp metadata;
- public API Javadoc completion across Sprint 09/10 production boundaries.

Protected-main merge remains the only Sprint 10 repository closeout step. The
final feature-branch GitHub Actions checks must be green before merge.

The full Maven suite and strict Javadoc generation were green at final local
validation. GitHub Actions CI run 61 completed successfully for final
feature-branch head `810601c8`. Protected-main merge remains the only Sprint 10
repository closeout step.

## 5. Immediate next development position

After Sprint 10 merge closeout, return to real corpus and mapping completion
rather than opening another large infrastructure sprint by default.

High-value operational work remains:

- systematic mapping review;
- response-type resolution;
- Question source capture;
- shared-context resolution;
- Answer completion;
- metadata correction;
- legacy split correction;
- exercise of the revised HTML/SCORM output against real Chemistry data.

Application SQLite review state remains authoritative. The standalone Chemistry
2019 -> 2025 mapping workbook remains reference material.

## 6. Remaining capture/content refinements

Deferred capture work includes:

- cleanup of empty managed provider/year directories after successful Exam
  relocation;
- multi-page automatic shared-context capture;
- optional MCQ explanation regions;
- assisted Question-boundary suggestions;
- assisted multipart/dependency suggestions;
- curriculum PDF text/maths/image authoring assistance;
- further keyboard/efficiency improvements.

## 7. Remaining revision-output refinements

Deferred output work is now narrow:

- later print-oriented/vector-preserving output;
- further visual/presentation polish only when real generated resources show a
  concrete need.

Grouping, response-type sections, page-local numbering, empty-branch pruning,
selected-Unit scope, Question-specific exclusions, student-facing counts,
timestamps and HTML/SCORM option parity are implemented and are not backlog.

## 8. Remaining Question-model decisions

### Multiple original classifications

Decide whether one-best-fit remains permanent policy or the model becomes
many-to-many. Any change must review schema/repositories, capture/edit UI,
applicability, retrieval duplicate semantics, provenance and output placement.

### Out-of-scope source Questions

Decide whether ingestion/audit needs explicit reviewed states distinguishing
not-yet-reviewed, captured/classified and deliberately out-of-scope material.

Question-specific output exclusions are already implemented and should not be
confused with an out-of-scope ingestion state.

## 9. Bank-management and retrieval hardening

Potential later work:

- strengthen retrieval-domain invariants;
- broaden realistic SQLite integration cases;
- benchmark broad searches before optimising;
- add import audit/reconciliation reporting;
- validate additional real-world workbook variants;
- decide whether DB-level mapping enforcement is warranted.

## 10. Exam Builder — later

Capabilities:

- select Questions;
- order Questions/sections;
- calculate total marks;
- save reproducible drafts;
- choose Answer/marking inclusion;
- retain source provenance.

Exam Builder consumes the bank and must not become a dependency of revision or
SCORM output.

## 11. Printable assessment / solution output — later

Potential requirements:

- sequential generated numbering;
- matching solution numbering;
- page breaks and layout control;
- school headers/instructions;
- source attribution;
- vector-preserving source clipping.

## 12. Non-PDF / image content — later

Support a separate content-source extension for transient documents/web pages:

- clipboard paste from Windows Snipping Tool;
- optional drag/drop PNG/JPEG;
- text plus image attachments;
- BLOB versus managed-image-file persistence decision;
- backup/export integration;
- provenance metadata;
- optional OCR later.

Do not replace normal PDF-region provenance with this model.

## 13. Packaging and deployment — later

Investigate `jpackage` for self-contained deployment and keep writable data
outside installed application files.

Do not use a live SharePoint-synchronised SQLite database for concurrent editing.
If simultaneous multi-user editing becomes necessary, evaluate controlled
import/merge or an IT-supported central database.

## 14. Assisted automation — future

Possible assistance includes PDF text extraction, descriptor ranking, Question
boundary suggestions, part/dependency suggestions, OCR, duplicate detection,
coverage analytics and assessment-blueprint suggestions.

Automation proposes; teachers confirm authoritative classifications and mappings.

## 15. Backlog authority

The authoritative inventory of deferred and unresolved work is:

`docs/design/backlog.md`

## 16. Development discipline

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
10. keep feature branches coherent;
11. require CI before protected-main merge;
12. update current status, roadmap, backlog and the canonical sprint record when
    implementation state materially changes.
