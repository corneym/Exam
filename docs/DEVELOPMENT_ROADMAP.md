# Exam Question Bank — Development Roadmap

> **Reference date:** 17 September 2026
> **Version:** 13
> **Repository location:** `docs/DEVELOPMENT_ROADMAP.md`
>
> This is the single canonical project roadmap. The former `docs/roadmap.md`
> content has been merged here and that duplicate roadmap is retired.

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves
authoritative examination content, historical curriculum provenance and
reusable marking material, then turns that bank into useful outputs for teachers
and students.

Chemistry remains the first full development dataset, while core model,
repository, service, persistence and output logic remain subject-neutral.

Long-term purposes are:

1. maintain a durable curriculum-aware bank of Questions and marking material;
2. generate student revision resources;
3. assemble teacher assessments and printable marking resources;
4. later support broader deployment, collaboration and assisted workflows.

## 2. Current strategic path

```text
Completed data safety
        ↓
Completed deterministic revision HTML
        ↓
Completed SCORM 1.2 / QLearn validation
        ↓
Completed Sprint 07 shared-context/capture/edit/output work
        ↓
Completed Sprint 08 curriculum/corpus tooling and hardening
        ↓
Sprint 09 capture workflow + corpus correction
        ↓
Complete real Question data + application-authoritative mapping review
        ↓
Revision-output presentation refinement
        ↓
Resolve remaining Question semantics / richer bank management
        ↓
Exam Builder + printable output
        ↓
Packaging / deployment / assisted automation
```

The immediate priority is not speculative Exam Builder work. Sustained use of
the real application has exposed concrete capture, correction, ordering and
metadata-management friction that should be fixed while the corpus is being
completed.

Application SQLite review state remains authoritative for curriculum mapping;
external mapping workbooks remain reference artefacts only.

## 3. Current architecture constraints

### 3.1 Subject-neutral core

Subject, syllabus version and curriculum nodes are data. Reusable code must not
hard-code Chemistry or one historical hierarchy shape.

### 3.2 SQLite runtime

Excel is import/exchange only. SQLite is the live datastore with referential
integrity and sequential migrations. The current latest schema version is 8.

Schema v8 persists Question response type as `MULTIPLE_CHOICE`,
`WRITTEN_RESPONSE` or `UNKNOWN`.

### 3.3 Managed source PDFs are authoritative

Persist portable relationships and relative paths beneath `data.root`. Rendered
images are derived assets, not source-of-truth Question content.

### 3.4 Ordered normalised regions

Question, Answer and SharedQuestionContext source material may use ordered
normalised PDF regions. Region order matters and coordinates remain independent
of render DPI.

### 3.5 SourceQuestion and SharedQuestionContext are distinct

`SourceQuestion` represents original multipart identity.
`SharedQuestionContext` represents reusable source material.

Recognised multipart question codes may conservatively derive/create
SourceQuestion identity during capture or backfill. Shared-context identity or
content must not be inferred from question-code patterns or legacy flags alone.

### 3.6 Historical provenance is preserved

Historical classification is not rewritten to current classification. Confirmed
historical -> current mappings derive applicability.

A Question currently stores one best-fit original classification. Whether direct
multiple original classifications are required remains an explicit future design
decision.

### 3.7 Retrieval hierarchy is already defined

Subject/Unit/Topic/Subtopic/Descriptor retrieval semantics are established.
Presentation grouping occurs only after retrieval has established the final
current-curriculum output bucket.

### 3.8 Data safety is first-class

Manual mapping review and region capture are expensive. Backup/restore must
continue to protect later schema versions and managed source files.

### 3.9 Response type belongs to Question

Mixed-response booklets are supported. Runtime Answer behaviour must not depend
on booklet-name inference.

```text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> response type unresolved; ordinary Answer capture blocked
```

### 3.10 Static web delivery and SCORM

HTML/SCORM export renders required PDF regions to derived web assets. Source PDFs
are not normally packaged into SCORM. SCORM 1.2 remains a packaging layer over
the static revision site rather than a second presentation model.

### 3.11 Print delivery should preserve vector source content where practical

Later printable output should evaluate direct page/viewport clipping from
original PDFs rather than reusing raster web assets as the print master.

### 3.12 Long-running UI work must not block JavaFX

Repository persistence/refresh work and PDF transitions that can be slow belong
off the JavaFX application thread. Asynchronous work requires stale-request and
lifecycle protection.

## 4. Completed foundation

### Sprint 01 — Legacy Metadata Import

Complete:

- legacy workbook metadata import;
- Exam/ExamBooklet reconstruction;
- text Question codes and marks;
- historical classification preservation;
- metadata-only zero-region Questions;
- MCQ answer letters where supplied;
- managed source-document handling;
- idempotent/conflict-aware import;
- legacy preamble evidence retained without invented shared context.

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
- direct-current and confirmed-mapped historical results;
- explicit hierarchy expansion;
- duplicate prevention;
- SQLite repository/service retrieval;
- asynchronous search UI and stored Question preview;
- lifecycle/stale-result protection.

### Sprint 04 — Backup, Restore and Data Safety

Complete:

- versioned backup archives;
- SQLite-consistent snapshots;
- automatic database backup on normal close;
- manual full backup;
- bounded retention;
- validated database-only and full restore;
- pre-restore protection and rollback;
- migration compatibility validation;
- restart boundary after successful/destructive restore.

### Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

Complete:

- deterministic transient revision corpus;
- derived Question and Answer-region assets;
- hierarchical Subject/Unit/Topic/Subtopic navigation;
- Descriptor presentation where applicable;
- generated numbering, marks and provenance;
- answer disclosure and missing-answer handling;
- staged export/reference validation;
- JavaFX export workflow and progress reporting;
- real Chemistry browser acceptance.

### Sprint 06 — SCORM Package Generation and QLearn Validation

Complete:

- SCORM 1.2 single-SCO packaging;
- deterministic manifest and ZIP;
- complete learning-content inventory;
- schema support and package validation;
- JavaFX SCORM workflow;
- successful real QLearn import and launch.

### Sprint 07 — Preamble-aware Question Capture and UI Redesign

Complete and merged.

Delivered persisted SourceQuestion/shared-context semantics, ordered shared
context regions, preamble-aware normal/imported capture, correction workflows,
selection ownership, syllabus-sensitive classification, Answer correction,
multipart/shared-context revision presentation, background Question/Answer saves
and asynchronous Answer-PDF handling.

Canonical record:

`docs/design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

### Sprint 08 — Curriculum and Corpus Completion

Complete, independently reviewed, revalidated and merged to `main` on
17 September 2026.

Delivered:

- subject-neutral curriculum authoring;
- explicit expert-authored hierarchy and correction-safe numbering;
- resumable SQLite persistence with stable persistent identities;
- managed syllabus-PDF provenance;
- `IN_PROGRESS` / `FINAL` lifecycle;
- Descriptor/Subtopic mapping coverage and deliberate review states;
- legacy metadata correction and safe preamble conversion;
- persisted Question response type;
- mixed-response booklet support;
- response-type-aware Answer completeness;
- corpus audit and filtered work queues;
- asynchronous Question Search hardening;
- Full-width pending-selection hardening;
- destination shared-context consistency checks for multipart metadata
  correction;
- closeout regressions for rollback/retry and revision presentation after reload.

Canonical record:

`docs/design/sprint-08-Curriculum-and-Corpus-Completion.md`

The temporary `docs/design/sprint-08-slice-7-documentation-changes.md` is no
longer needed. Its enduring decisions are incorporated into canonical project
and sprint documentation.

## 5. Sprint 09 — Capture Workflow and Corpus Correction

**PLANNED / NEXT SPRINT**

Canonical design:

`docs/design/sprint-09-capture-workflow-and-corpus-correction.md`

Sprint 09 is driven by sustained use of the application while completing the
real corpus. It focuses on correcting workflow friction rather than introducing
a new parallel architecture.

Primary scope:

- responsive Answer-pane layout and stable button labels;
- useful Answer status placement;
- source/natural ordering for Questions awaiting Answer capture;
- correct enable/disable state for Add Region and Clear controls;
- Question/Answer capture ownership reflected in visible controls;
- responsive Question-pane and metadata-dialog labels;
- response-type radio buttons in Question capture;
- smaller Question-number entry field;
- investigation/fix of slow Imported Questions mode activation;
- first-class shared-preamble replacement/recapture;
- supported exam-level metadata correction;
- safe metadata reuse when opening a PDF already known to the database;
- dedicated conversion of a legacy single Question into multipart parts;
- Search Questions all-bank versus syllabus filtering;
- deterministic Corpus Audit ordering by provider/authority, year, booklet and
  natural Question number;
- review of redundant `Questions -> Capture New Questions` navigation.

The revision HTML presentation changes discovered during the same usage review
are deliberately retained in the backlog rather than silently expanding Sprint
09.

## 6. Real corpus and mapping completion

**ONGOING DATA WORK / HIGH PRODUCT VALUE**

Sprint 08 implemented the tooling; it did not make the real corpus magically
complete.

Continue systematic work through:

- mapping coverage and explicit no-match decisions;
- response-type resolution;
- Question source capture;
- shared-context resolution;
- Answer completion;
- metadata correction;
- later Sprint 09 split/preamble/exam-correction workflows once implemented.

The standalone Chemistry 2019 -> 2025 mapping workbook remains reference
material. SQLite review state is authoritative.

## 7. Revision-output presentation refinement

**FUTURE SPRINT INPUT / HIGH VALUE AFTER CORPUS WORK**

Real use has identified a coherent revision-output design pass:

- allow output grouping at Descriptor or Subtopic level when Descriptors exist;
- when Subtopic grouping is selected, flatten Descriptor-classified Questions
  into the parent Subtopic while retaining deterministic Descriptor order;
- remove repeated per-part `Source part ...` decoration for multipart Questions;
- present source/original-classification provenance once after the multipart
  Question rather than after every part;
- place multiple-choice Questions before written-response Questions;
- decide whether each Subtopic should optionally contain separate MCQ and Written
  Response sections.

These are presentation semantics, not capture/correction fixes, and should be
designed together.

## 8. Remaining Question-model decisions

### 8.1 Multiple original classifications

Decide whether one-best-fit remains permanent policy or the model becomes
many-to-many. Any change must review schema/repositories, capture/edit UI,
applicability, retrieval duplicate semantics, provenance and output placement.

### 8.2 Question-level applicability exceptions

A curriculum mapping can be valid while a particular historical Question tests
only content that did not carry forward. Support an explicit Question-level
exclusion from the relevant mapped target without rewriting either the original
classification or the curriculum mapping. Prefer node-specific applicability
where one historical node maps to several current targets.

### 8.3 Out-of-scope source Questions

Decide whether ingestion/audit needs explicit reviewed states distinguishing
not-yet-reviewed, captured/classified and deliberately out-of-scope material.

## 9. Bank-management and retrieval hardening

### Retrieval/domain

- strengthen result invariants;
- broaden realistic SQLite integration cases;
- verify same-Subject isolation and database reopen reconstruction;
- benchmark broad searches before optimising.

### Mapping

- decide whether DB-level enforcement is warranted for mapping invariants;
- preserve explicit human confirmation;
- improve review tooling only where justified by real workflow evidence.

### Import/reconciliation

- add audit reports for created/identical/conflicting/missing records;
- validate additional real-world workbook variants;
- keep dry-run/reconciliation concepts separate from silent repair.

## 10. Remaining capture/content refinements

Deferred beyond the immediate Sprint 09 plan unless pulled in deliberately:

- multi-page automatic shared-preamble capture;
- optional MCQ explanation regions;
- assisted PDF question-boundary suggestions;
- assisted multipart/dependency suggestions;
- curriculum PDF region/text/maths/image authoring assistance.

## 11. Rich Question Bank administration — later

Possible later capabilities:

- broader filtering and source inspection;
- richer completeness/status indicators;
- import/mapping reconciliation reports;
- out-of-scope disposition;
- applicability review/overrides;
- multiple-classification editing if adopted.

## 12. Exam Builder — later

Capabilities:

- select Questions;
- order Questions/sections;
- calculate total marks;
- save reproducible drafts;
- choose Answer/marking inclusion;
- retain source provenance.

Exam Builder consumes the bank and must not become a dependency of revision or
SCORM output.

## 13. Printable assessment / solution output — later

Potential requirements:

- sequential generated numbering;
- matching solution numbering;
- page breaks and layout control;
- school headers/instructions;
- source attribution;
- vector-preserving source clipping.

## 14. Non-PDF / image content — later

Support a separate content-source extension for transient documents/web pages:

- clipboard paste from Windows Snipping Tool;
- optional drag/drop PNG/JPEG;
- text plus image attachments;
- BLOB versus managed-image-file persistence decision;
- backup/export integration;
- provenance metadata;
- optional OCR later.

Do not replace normal PDF-region provenance with this model.

## 15. Packaging and deployment — later

### Desktop

Investigate `jpackage` for self-contained deployment and keep writable data
outside installed application files.

### Faculty sharing

Do not use a live SharePoint-synchronised SQLite database for concurrent
editing. If simultaneous multi-user editing becomes necessary, evaluate
controlled import/merge or an IT-supported central database.

## 16. Assisted automation — future

Possible assistance:

- PDF text extraction;
- descriptor ranking;
- Question-boundary suggestions;
- part/dependency suggestions;
- OCR for image attachments;
- duplicate detection;
- coverage analytics;
- difficulty/blueprint suggestions.

Automation proposes; teachers confirm authoritative classifications and mappings.

## 17. Backlog authority

The authoritative inventory of scheduled, deferred and unresolved work is:

`docs/design/backlog.md`

Items scheduled into a sprint remain visible there with a `Scheduled` marker
until completed. At sprint closeout, completed entries move to the
Completed/Not Backlog record or are removed where the canonical sprint record
already provides sufficient history.

## 18. Development discipline

For self-contained changes:

1. start from current repository state;
2. design substantial changes before implementation;
3. make one focused change;
4. add/update behaviour-focused tests;
5. add SQLite/repository/service integration tests where boundaries justify it;
6. update API documentation where the contract changes;
7. run focused tests;
8. run the complete standard suite at major checkpoints;
9. run headless TestFX/manual checks for UI changes;
10. keep long-running UI work off the JavaFX thread;
11. commit logical checkpoints;
12. push before repository-wide review;
13. perform merge-readiness review for substantial branches;
14. update current status, roadmap, backlog and sprint history when state
    materially changes.
