# Exam Question Bank — Development Roadmap

> **Reference date:** 10 September 2026  
> **Version:** 8  
> **Repository location:** `docs/DEVELOPMENT_ROADMAP.md`

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

The immediate priority after Sprint 07 is real corpus/data completion and
question-bank management correctness rather than speculative Exam Builder work.

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
Complete and reconcile Question + mapping data
        ↓
Harden bank management / retrieval / audit workflows
        ↓
Resolve remaining Question semantics
        ↓
Exam Builder / printable output
```

Curriculum mapping reconciliation and corpus completion remain compatible with
the implemented Sprint 07 model and can proceed without reopening its core
architecture.

## 3. Current architecture constraints

### 3.1 Subject-neutral core

Subject, syllabus version and curriculum nodes are data. Reusable code must not
hard-code Chemistry or one historical hierarchy shape.

### 3.2 SQLite runtime

Excel is import/exchange only. SQLite is the live datastore with referential
integrity and sequential migrations. The current latest schema version is 6.

### 3.3 Managed source PDFs are authoritative

Persist portable relationships and relative paths beneath `data.root`. Rendered
images are derived assets, not source-of-truth Question content.

### 3.4 Ordered normalised regions

Question, Answer and SharedQuestionContext source material may use ordered
normalised PDF regions. Region order matters and coordinates remain independent
of render DPI.

### 3.5 SourceQuestion and SharedQuestionContext are distinct

`SourceQuestion` represents original multipart identity.

`SharedQuestionContext` represents reusable source material. Questions may share
context without being members of the same SourceQuestion.

Do not infer either relationship silently from naming patterns or legacy flags.

### 3.6 Historical provenance is preserved

Historical classification is not rewritten to current classification. Confirmed
historical -> current mappings derive applicability.

The current Question still stores one best-fit original classification. Whether
multiple original classifications are required remains a deliberate later design
decision.

### 3.7 Retrieval hierarchy is already defined

Sprint 03 established Subject/Unit/Topic/Subtopic/Descriptor retrieval
semantics. Sprint 07 presentation grouping operates after retrieval has
established the final current-curriculum output bucket.

### 3.8 Data safety is first-class

Manual mapping review and region capture are expensive. Backup/restore must
continue to protect all later schema versions and managed source files.

### 3.9 Static web delivery

HTML/SCORM export renders required PDF regions to derived web assets. Source PDFs
are not normally packaged into SCORM.

### 3.10 SCORM is a packaging layer

SCORM 1.2 packages the static revision website. It does not own a second
multipart/shared-context presentation model.

### 3.11 Print delivery should preserve vector source content where practical

Later printable output should evaluate direct page/viewport clipping from
original PDFs rather than reusing raster web assets as the print master.

### 3.12 Long-running UI work must not block JavaFX

Repository persistence/refresh work and post-save Answer PDF transitions that
can be slow belong off the JavaFX application thread. Asynchronous document
loads require stale-request protection so an old result cannot overwrite newer
user intent.

## 4. Completed sprints

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
- asynchronous search UI;
- stored Question preview;
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

Implemented on `feature/preamble-capture` and in closeout.

Delivered:

- schema v5 SourceQuestion/shared-context persistence;
- schema v6 SourceQuestion `PreambleStatus`;
- explicit source-question and shared-context relationships;
- ordered shared-context regions;
- conservative legacy preamble resolution without guessed context;
- normal and imported preamble-aware capture;
- same-page anchored large-region capture;
- shared-context reuse;
- explicit selection ownership and guarded transitions;
- resizable capture workspace;
- syllabus-sensitive classification and Descriptor support;
- existing Question correction;
- existing Answer correction through Question Search;
- Answer marks display and MCQ letter entry;
- multipart/shared-context presentation planning;
- amended revision HTML and inherited SCORM output;
- background Question and Answer saves;
- asynchronous post-save Answer PDF loading;
- registered Answer PDF reuse and conditional chooser visibility;
- legacy Answer-PDF registration when all required Question booklets already
  exist.

Canonical design/final implementation record:

`docs/design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

The temporary Sprint 07 status/backlog and cleanup-review documents should be
retired after consolidation.

## 5. Current data work

The standalone 5 September
`Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` remains useful reviewed-analysis
data but is not established as reconciled/imported into SQLite.

Parallel work includes:

- manual review of Low/no-match rows;
- review of Medium/check rows;
- confirmation of genuinely new/removed content;
- reconciliation with SQLite mappings;
- coverage reporting after reconciliation;
- completion of Question and Answer region capture;
- larger real-data retrieval/export checks.

## 6. Immediate development sequence after Sprint 07

### 6.1 Final Sprint 07 checkpoint

Before merge:

- ensure the standard test suite is green;
- ensure the headless UI suite is green;
- ensure Javadoc/doclint is green after final API changes;
- perform final branch-versus-`main` review;
- ensure `current-status.md`, roadmap, backlog and Sprint 07 design record agree;
- merge only after explicit approval.

### 6.2 Corpus completion and mapping reconciliation

Use the implemented capture/edit workflows to complete real data rather than
adding new architecture first.

Priorities:

- remaining Question regions;
- remaining Answer regions;
- missing source documents;
- historical classification verification;
- Chemistry 2019 -> 2025 mapping reconciliation;
- coverage reporting.

### 6.3 Broad capture/audit queue

Once real corpus maintenance volume justifies it, add a filtered administrative
queue for:

- Question regions missing;
- Answer regions missing;
- unresolved shared context;
- other adopted completion states.

Do not rewrite imported historical metadata merely to mark work complete.

### 6.4 Search Questions shared-context preview

Extend Search Questions preview so a linked SharedQuestionContext is reconstructed
before the selected Question's ordinary regions without automatically displaying
unrelated sibling parts.

This is presentation only; retrieval applicability remains unchanged.

## 7. Remaining Question-model decisions

### 7.1 Multiple original classifications

Decide explicitly whether one-best-fit remains permanent policy or the model
becomes many-to-many.

If changed, review schema/repositories, capture/edit UI, applicability,
retrieval duplicate semantics, provenance and output placement.

### 7.2 Question-level response type

Persist response type rather than inferring MCQ behaviour from booklet names.

Candidate values:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
UNKNOWN
```

Use this to drive Answer UI behaviour and support mixed-response booklets.

### 7.3 Question-level applicability exceptions

A curriculum mapping can be valid while a particular historical Question tests
only content that did not carry forward. Support an explicit Question-level
exclusion from the relevant mapped target without changing the original
classification or the curriculum mapping itself.

Prefer node-specific applicability where one historical classification maps to
several target nodes.

### 7.4 Out-of-scope source Questions

Decide whether ingestion/audit needs explicit reviewed states distinguishing
not-yet-reviewed, captured/classified and deliberately out-of-scope material.

## 8. Capture/UI follow-on work

Keep bounded follow-on issues in the backlog rather than reopening Sprint 07:

- multi-page automatic shared-preamble capture;
- Answer-pane spacing/control-size annoyances;
- Save-button ready-state recalculation after clearing a new pending selection;
- Full-width-selection state change clearing a pending selection;
- optional MCQ explanation regions;
- broader exam-metadata correction.

## 9. Retrieval, mapping and reliability hardening

### JavaFX/TestFX

- continue isolation of focus-sensitive tests;
- add remaining stale search/preview/disposal regressions;
- keep infrastructure flakiness separate from application defects.

### Retrieval/domain

- strengthen result invariants;
- broaden realistic SQLite integration cases;
- verify same-Subject isolation and reopen reconstruction;
- benchmark broad searches before optimising.

### Mapping

- decide whether DB-level enforcement is warranted for mapping invariants;
- preserve explicit human confirmation;
- improve coverage reporting and review tooling where justified.

### Import/reconciliation

- add audit reports for created/identical/conflicting/missing records;
- validate additional real-world workbook variants;
- keep dry-run/reconciliation concepts separate from silent data repair.

## 10. Rich bank management — later

Possible later capabilities:

- broad filtering and source inspection;
- completeness/status indicators;
- full capture/audit queues;
- metadata correction;
- mapping/import reconciliation reports;
- applicability review;
- out-of-scope disposition;
- multiple-classification editing if adopted.

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

## 13. Non-PDF/ephemeral image content — later

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

### Desktop

Investigate `jpackage` for self-contained deployment and keep writable data
outside installed application files.

### Faculty sharing

Do not use a live SharePoint-synchronised SQLite database for concurrent
editing. If simultaneous multi-user editing becomes necessary, evaluate
controlled import/merge or an IT-supported central database.

## 15. Assisted automation — future

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

## 16. Backlog authority

Authoritative deferred-work file:

`docs/design/backlog.md`

Do not duplicate active backlog items into temporary sprint-status notes. When
work is scheduled into a sprint, reference the authoritative backlog item and
then remove or mark it there according to the documentation maintenance rules.

## 17. Development discipline

For self-contained changes:

1. start from the current repository branch state;
2. make one focused change;
3. add/update behaviour-focused tests;
4. add SQLite/repository/service integration tests where boundaries justify it;
5. update API documentation where the contract changes;
6. run focused tests;
7. run the complete standard suite at major checkpoints;
8. run headless TestFX/manual checks for UI changes;
9. keep long-running UI work off the JavaFX thread;
10. commit logical checkpoints;
11. push before repository-wide review;
12. perform merge-readiness review for substantial branches;
13. update current status, roadmap, backlog and sprint history when state
    materially changes.
