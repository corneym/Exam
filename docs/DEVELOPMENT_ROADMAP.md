# Exam Question Bank — Development Roadmap

> **Reference date:** 7 September 2026  
> **Version:** 7  
> **Repository location:** `docs/DEVELOPMENT_ROADMAP.md`

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves authoritative examination content, historical curriculum provenance and reusable marking material, then turns that bank into useful outputs for teachers and students.

Chemistry is the first full development dataset, but core model/service/persistence code must remain subject-neutral.

Long-term purposes:

1. maintain a durable curriculum-aware bank of questions and marking material;
2. generate student revision resources;
3. later assemble teacher assessments and printable marking resources.

The immediate product priority remains revision-resource quality and bank-management correctness rather than Exam Builder.

## 2. Current strategic path

```text
Completed data safety
        ↓
Completed deterministic revision HTML
        ↓
Completed SCORM 1.2 / QLearn validation
        ↓
Sprint 07: fix capture semantics + editing + multipart/shared-context output
        ↓
Complete/reconcile remaining question + mapping data
        ↓
Richer bank management
        ↓
Exam Builder / printable output
```

Curriculum mapping reconciliation and corpus completion can continue in parallel because Sprint 07 does not redesign curriculum import, mapping, applicability or retrieval.

## 3. Current architecture constraints

### 3.1 Subject-neutral

Subject, syllabus version and curriculum nodes are data. Do not hard-code Chemistry or the 2019 Unit.Topic.Subtopic shape into reusable core logic.

### 3.2 SQLite runtime

Excel is import/exchange only. SQLite is the live datastore with migrations and referential integrity.

### 3.3 Managed source PDFs are authoritative

Persist portable relationships/relative paths beneath `data.root`. Rendered images are derived.

### 3.4 Ordered normalized regions

Questions and written-response answer material may use one or many source regions. Region order matters; coordinates remain independent of render DPI.

Sprint 07 adds ordered shared-context regions without changing these coordinate rules.

### 3.5 Historical provenance is preserved

Historical classification is not rewritten to current classification. Confirmed historical -> current mappings derive applicability.

### 3.6 Retrieval hierarchy is already defined

Sprint 03 established Subject/Unit/Topic/Subtopic/Descriptor retrieval semantics.

Sprint 07 must reuse those semantics. Multipart/shared-context grouping happens after retrieval has established the final current-curriculum output bucket.

### 3.7 Data safety is first-class

Mapping review and region capture are expensive manual work. Backup/restore remains a first-class boundary and must continue to protect schema-v5 data.

### 3.8 Static web delivery

For HTML/SCORM, render required PDF regions to derived web assets at export time. Do not make browser-side PDF rendering the primary architecture. Full source PDFs should not normally be packaged into SCORM.

### 3.9 SCORM is a packaging layer

SCORM 1.2 packages the static revision website. It must not contain a separate multipart/shared-context presentation model.

### 3.10 Print delivery should preserve vector source content where practical

Later printable PDF generation should evaluate direct page/viewport clipping from original PDFs rather than reusing raster web assets as the print master.

### 3.11 One-best-fit classification remains the current implementation

The current `Question` stores one original Subtopic/Descriptor classification.

Earlier real classification work demonstrated that a question may relate to several descriptors. That issue remains unresolved and is not part of Sprint 07.

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
- idempotent/conflict-aware import;
- row-level `preambleCaptureRequired` evidence retained without inferred relationships.

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

### Sprint 04 — Backup, Restore and Data Safety

Complete and merged into `main` on 6 September 2026.

Delivered:

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

Detailed design:

`docs/design/sprint-04-backup-restore-data-safety.md`

### Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

Complete and real-data accepted on 6 September 2026.

Delivered:

- deterministic transient revision-corpus construction;
- derived question and answer-region assets;
- Subject and Unit navigation;
- Topic and Subtopic pages;
- omission of empty Descriptor sections;
- generated numbering, marks and provenance;
- answer disclosure and missing-answer handling;
- staged export and reference validation;
- JavaFX export workflow;
- rendering progress;
- real Chemistry browser acceptance.

Detailed design:

`docs/design/sprint-05-hierarchical-revision-corpus-static-html-output.md`

### Sprint 06 — SCORM Package Generation and QLearn Validation

Complete and real QLearn accepted on 6 September 2026.

Confirmed profile:

- SCORM 1.2;
- one organisation/item/SCO;
- launch `index.html`;
- static multi-page revision website;
- root `imsmanifest.xml`;
- four schema support files;
- no SCORM runtime/API JavaScript;
- no source PDFs.

Delivered:

- deterministic manifest generation;
- complete learning-content inventory;
- package validation;
- safe staging/cleanup;
- deterministic ZIP structure;
- JavaFX SCORM export workflow;
- automated SCORM coverage;
- successful QLearn import and launch.

Detailed design:

`docs/design/sprint-06-SCORM-output.md`

## 5. Current data work

The standalone 5 September `Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` remains useful reviewed-analysis data but is not established as reconciled/imported into SQLite.

Parallel work still includes:

- manual review of Low/no-match rows;
- review of Medium/check rows;
- confirmation of genuinely new/removed content;
- reconciliation with current SQLite mappings;
- coverage reporting after reconciliation.

This remains separate from Sprint 07.

## 6. Sprint 07 — Preamble-aware Question Capture and UI Redesign

**Status: design complete — implementation not started.**

Detailed design:

`docs/design/sprint-07-preamble-aware-question-capture-ui-redesign.md`

Feature branch:

`feature/preamble-capture`

### 6.1 Goal

Establish correct persisted semantics and end-to-end workflow for:

- multipart source-question identity;
- separately captured reusable shared context/preamble;
- legacy unresolved preamble evidence;
- question and answer correction;
- safe selection handling;
- a larger resizable capture workspace;
- syllabus-sensitive Question classification controls, including Descriptor selection where that level exists;
- hierarchy-aware classification validation;
- correct multipart/shared-context presentation in revision HTML and therefore SCORM.

### 6.2 Schema v5

Add:

- `source_questions`;
- `shared_question_contexts`;
- `shared_question_context_regions`;
- nullable `questions.source_question_id`;
- nullable `questions.shared_context_id`.

Keep:

- existing question identity `(booklet_id, question_code)`;
- existing `preamble_capture_required` evidence;
- existing question/answer region semantics.

Migration rules:

- preserve all v4 data;
- create no inferred source-question relationships;
- create no inferred shared-context relationships;
- leave new links null for existing data.

### 6.3 Source-question semantics

A `SourceQuestion` represents original source identity such as `21`.

Questions such as `21a`, `21b`, `21c` remain separately persisted/classified/marked questions.

Membership is explicit.

Question-code parsing may provide a UI suggestion only.

### 6.4 Shared-context semantics

A `SharedQuestionContext`:

- belongs to one booklet;
- contains one or more ordered normalized source regions;
- may be reused by many questions;
- is separate from source-question identity.

Sprint 07 supports zero or one shared-context link per question.

Ordinary multi-region questions remain a separate concept.

### 6.5 Legacy preamble semantics

Derived unresolved condition:

```text
preamble_capture_required
AND shared_context_id IS NULL
```

The flag remains historical evidence even after the shared-context requirement is resolved.

No legacy import relationship guessing is permitted.

### 6.6 Capture/editing workflow

Question capture must support:

- source-question suggestion/confirmation;
- new shared-context capture;
- link/reuse existing shared context;
- unresolved legacy requirement visibility;
- existing-question correction;
- transactional replacement of question regions.

Answer capture must support:

- marks display;
- existing-answer loading;
- answer text/region correction;
- transactional update.

No question/answer deletion is required.

### 6.7 Selection lifecycle

Introduce explicit ownership:

```text
QUESTION
SHARED_CONTEXT
ANSWER
```

Only the owner may clear the workspace selection.

Guard page/question/document/target transitions so an unaccepted selection cannot disappear silently.

### 6.8 Workspace redesign

Replace the fixed narrow capture column with a resizable horizontal JavaFX `SplitPane`.

Keep the capture side vertically scrollable.

Use additional width for clearer controls and larger useful previews.

### 6.9 Syllabus-sensitive Question classification

The built-in classification panel must reflect the actual hierarchy of the selected syllabus branch.

Examples:

```text
Unit -> Topic -> Descriptor
```

shows Unit, Topic and Descriptor.

```text
Unit -> Topic -> Subtopic -> Descriptor
```

shows all four levels.

Rules:

- show Descriptor selection where Descriptor exists in the selected branch;
- filter child choices from parent selections;
- clear invalid child selections when a parent changes;
- allow a Question to stop at Subtopic even when Descriptor children exist;
- do not allow a Question to stop at Topic when that Topic has direct Descriptor children; a Descriptor must be selected;
- reconstruct the same hierarchy correctly when editing an existing Question.

This consumes the existing persisted curriculum hierarchy. It does not redesign curriculum import, mapping, applicability or retrieval.

### 6.10 Revision presentation

Within each final current-curriculum output bucket:

- group questions sharing a `SourceQuestion`;
- preserve deterministic member order;
- render shared context once where required;
- derive combined marks from included members;
- keep parts in different buckets separate;
- keep independent questions sharing context independent.

The presentation/grouping rule belongs in the revision output/service boundary, not retrieval.

### 6.11 SCORM impact

The SCORM profile and package architecture do not change.

SCORM packages the amended revision website and inherits the corrected multipart/shared-context presentation.

Affected SCORM tests prove that the content remains completely declared and package-valid.

### 6.12 Explicitly unaffected subsystems

Sprint 07 does not redesign:

- curriculum import;
- curriculum mapping/review;
- mapping suggestion;
- historical/current syllabus semantics;
- applicability;
- Sprint 03 retrieval;
- exam/provider/booklet persistence;
- PDF path safety;
- backup/restore;
- SCORM 1.2 profile/manifest/ZIP mechanics;
- multiple original classifications;
- mapping workbook reconciliation;
- Exam Builder;
- clipboard/image questions;
- printable output.

## 7. Sprint 07 implementation sequence

1. schema v5 + domain types;
2. assessment persistence/update APIs;
3. legacy unresolved shared-context status;
4. selection ownership + guarded transitions;
5. source-question/shared-context capture UI;
6. syllabus-sensitive classification panel + validation;
7. question correction;
8. answer correction + marks display;
9. resizable capture workspace;
10. pure revision presentation planner;
11. HTML + SCORM integration;
12. full-suite/review/documentation/merge checkpoint.

After each meaningful slice:

- run focused tests;
- stop for result before proceeding.

Run the complete Maven suite at major checkpoints and before merge.

## 8. Parallel corpus-completion work

With Sprint 07 active, separate data work can continue:

- attach remaining question regions;
- attach remaining answer/marking regions;
- resolve missing source documents;
- verify historical classification;
- review mapping workbook uncertainty;
- reconcile confirmed mapping pairs;
- validate larger datasets.

A broad filtered capture/audit queue remains deferred unless a narrow Sprint 07 query is required for unresolved preamble work.

## 9. Remaining design decisions after Sprint 07

### 9.1 Multiple original classifications

Decide explicitly whether one-best-fit remains permanent policy or the model becomes many-to-many.

Any change must preserve historical provenance and avoid confusing original classifications with derived current applicability.

### 9.2 Out-of-scope source questions

Decide whether ingestion/audit needs an explicit reviewed out-of-scope state.

### 9.3 Richer mapping relation metadata

Decide whether relation type/confidence/notes from standalone mapping work should remain review metadata or become persisted application data.

## 10. Rich bank management — later

Possible later features:

- broader filtering;
- source inspection;
- completeness indicators;
- full capture/audit queues;
- import reconciliation reports;
- out-of-scope disposition;
- multiple-classification editing if adopted.

## 11. Exam Builder — later

Later capabilities:

- select questions;
- order questions/sections;
- calculate total marks;
- save drafts;
- include/exclude marking material;
- reproducible assessment generation.

Do not make Exam Builder a dependency of revision/SCORM output.

## 12. Printable assessment / solution output — later

Build on the same bank/provenance.

Potential requirements:

- sequential generated numbering;
- matching solution numbering;
- page breaks/layout;
- school headers/instructions;
- source attribution;
- vector-preserving source clipping.

## 13. Non-PDF/ephemeral image content — later

Support a separate content-source extension for transient documents/web pages:

- clipboard paste from Windows Snipping Tool;
- optional drag/drop PNG/JPEG;
- text plus image attachments;
- BLOB vs managed-image-file persistence decision;
- backup/export integration;
- provenance metadata;
- optional OCR later.

Do not replace normal source-PDF regions with this model.

## 14. Packaging/deployment — later

### Desktop

Investigate `jpackage` for self-contained deployment.

Keep writable DB/data outside installed application files.

### Faculty sharing

Do not use a live SharePoint-synchronised SQLite database for concurrent editing.

If simultaneous multi-user editing becomes necessary, evaluate controlled merge/import or an IT-supported central database.

## 15. Automation — future

Possible assistance:

- PDF text extraction;
- descriptor ranking;
- question boundary suggestions;
- part/dependency suggestions;
- OCR for image attachments;
- duplicate detection;
- coverage analytics;
- difficulty/blueprint suggestions.

Automation may propose; teachers confirm authoritative classifications and mappings.

## 16. Backlog and technical debt

Authoritative file:

`docs/design/backlog.md`

Important deferred themes after scheduling Sprint 07 include:

- TestFX stability;
- async search regressions;
- retrieval-domain/integration hardening;
- broad capture/audit queue;
- 5 September mapping reconciliation;
- multiple original classification decision;
- out-of-scope disposition;
- import audit;
- additional workbook validation;
- mapping-relation metadata decision;
- packaging/deployment;
- future clipboard image content.

Shared-context/multipart semantics are no longer an unresolved backlog item; they are defined by Sprint 07.

## 17. Development discipline

For self-contained changes:

1. start from up-to-date branch state;
2. make one focused change;
3. add/update unit tests;
4. add integration tests across SQLite/repository/service boundaries where relevant;
5. update public API documentation where appropriate;
6. run focused tests;
7. stop for result after each meaningful Sprint 07 slice;
8. run complete clean suite at major checkpoints;
9. run TestFX/manual checks for UI changes;
10. commit logical checkpoints;
11. push before repository review;
12. perform merge-readiness review for substantial branches;
13. update sprint/roadmap/current-status/history documentation when implementation boundaries change.

## 18. Recommended development order

```text
Sprint 07 — shared context + multipart + capture/editing + output
        |
        +------ parallel capture completion + mapping reconciliation
        |
        v
Resolve remaining classification/ingestion decisions
        |
        v
Richer bank management + measured hardening
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
