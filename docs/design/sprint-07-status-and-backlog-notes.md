# Sprint 07 Status and Backlog Notes

**Project:** Exam Question Bank  
**Branch:** `feature/preamble-capture`  
**Status date:** 8 September 2026

This document records the current Sprint 07 implementation position and backlog items discussed during the sprint. It is intended as a documentation update note for incorporation into the existing Sprint 07 design, roadmap and backlog documents.

## Overall Sprint 07 position

Sprint 07 is roughly **55–60% implemented**, with about **40–45% remaining**.

That percentage is only a planning estimate. The remaining work contains two substantial areas—editing workflows and revision-output grouping—so the remaining effort is larger than the number of unfinished slices alone might suggest.

## Implemented or substantially implemented

### Domain, schema and persistence

- `SourceQuestion` implemented.
- `SharedQuestionContext` implemented.
- ordered `SharedQuestionContextRegion` persistence implemented.
- `Question` links to source question and shared context implemented.
- schema v4 -> v5 implemented.
- schema v5 -> v6 implemented.
- `PreambleStatus` implemented on `SourceQuestion`:
  - `UNKNOWN`
  - `NONE`
  - `PRESENT`
- existing v5 rows migrate to `UNKNOWN`.
- shared-context and source-question repositories implemented.
- Question persistence/reconstruction of relationships implemented.
- transactionally attaching question regions with source/shared relationships implemented.

### Legacy semantics

- legacy `preambleCaptureRequired` remains historical evidence.
- unresolved shared context is derived rather than rewriting the imported flag.
- legacy import does not infer source-question or shared-context relationships from codes or row patterns.

### Selection ownership and transition safety

- explicit selection ownership implemented for:
  - `QUESTION`
  - `SHARED_CONTEXT`
  - `ANSWER`
- page navigation is blocked only by an unaccepted current selection.
- accepted regions do not block page navigation.
- question/answer/exam transition guards have been added.
- answer selection restoration avoids the previous JavaFX ComboBox transition problem.
- an already-drawn question selection can now be transferred to automatic shared-preamble capture when multipart intent is selected.

### Normal multipart capture workflow

- normal capture hides manual SourceQuestion controls.
- multipart source code is derived conservatively from codes such as `24a -> 24`.
- `First region is shared preamble` workflow implemented.
- source preamble state is persisted as `NONE` or `PRESENT`.
- existing shared context for a source question can be reused.
- accepted shared preamble locks the question number; ordinary accepted question regions do not.
- Save Question is disabled while capture is incomplete.

### Capture UI cleanup

- redundant current-selection preview removed from Question capture.
- `Add Region` and `Clear` retained.
- accepted-region previews expanded to use the available pane width.
- question number and marks remain editable while ordinary regions are accumulated.
- shared-preamble explanation moved to the checkbox tooltip rather than permanent status text.

### Syllabus-sensitive classification

- classification model distinguishes Unit, Topic, Subtopic and Descriptor.
- both hierarchy shapes are supported:
  - `Unit -> Topic -> Descriptor`
  - `Unit -> Topic -> Subtopic -> Descriptor`
- Subtopic may be a final classification even when it has Descriptor children.
- Topic is not final where a more precise valid classification is required.
- progressive code entry implemented.
- invalid characters, impossible code extensions and repeated periods are rejected.
- hierarchy controls and code field synchronise in both directions.
- Subtopic/Descriptor rows show according to the selected syllabus branch.
- classification changes now drive Save Question state.
- TestFX coverage exists for the selector.

### JavaFX test infrastructure

- non-UI and UI suites are separated.
- `AllTests` is the non-UI suite.
- `UITests` is the tagged UI suite.
- a dedicated `headless-ui-tests` Maven profile uses Monocle.
- the headless UI suite has been run successfully.
- JavaFX/TestFX tests changed during Sprint 07 are tagged `ui`.

## Remaining Sprint 07 work

### 1. Close the current capture/classification slice

Before moving on, complete the final automated and manual checks for:

- Unit/Topic/Subtopic/Descriptor -> Code synchronisation.
- Code -> hierarchy synchronisation.
- no invalid final trailing period.
- Save Question enabling/disabling immediately when classification changes.
- first accepted-region preview using full width on its first capture.
- multipart selection order:
  - draw region;
  - enter multipart code;
  - select shared-preamble intent;
  - reuse the already-drawn rectangle as the preamble.

This is expected to be a small close-out item rather than a new feature slice.

### 2. Complete legacy unresolved shared-context resolution

This remains incomplete.

A legacy question may already have ordinary question regions while still having:

```text
preambleCaptureRequired = true
sharedContext = null
```

The existing `attachRegions(...)` path is not sufficient for a question that already has regions.

Required work:

- add a relationship-only repository operation, conceptually:

```text
updateCaptureRelationships(...)
```

- validate booklet ownership transactionally.
- implement SQLite and in-memory versions.
- allow a legacy unresolved question with existing regions to resolve only its source/shared relationships.
- only after that works, broaden the legacy work queue to:

```text
question regions missing
OR
shared context unresolved
```

Do not broaden the queue before relationship-only resolution is available.

### 3. Question editing

Persisted questions must be reopenable for correction.

Editable:

- question code;
- marks;
- classification;
- ordered question regions;
- source-question relationship;
- shared-context relationship.

Invariant:

- exam/booklet ownership remains immutable.
- existing question ID remains stable.
- existing answer remains attached.
- existing question text and legacy preamble evidence remain preserved.
- region replacement and metadata update must be transactional.
- no delete workflow in Sprint 07.

### 4. Answer editing and marks display

Required:

- show the selected question's marks during answer capture.
- selector must support answered as well as unanswered questions.
- load existing answer text and regions.
- allow answered/unanswered state to be corrected as required by the existing answer model.
- update an existing answer transactionally.
- retain the existing answer ID where practical.
- support multiple answer files for the same exam as already intended by the persistence design.
- no answer deletion in Sprint 07.

### 5. Capture workspace layout

The Sprint 07 design still calls for replacing the fixed capture column with a horizontally resizable `SplitPane`.

Required:

- approximately 500–550 px initial capture width at the normal window size.
- vertically scrollable capture side.
- preserve the logical hierarchy:
  - CLASSIFICATION
  - QUESTION
  - ANSWER
- use width for useful controls/previews rather than blank space.

### 6. Revision presentation planner

This is a substantial remaining slice.

Implement a pure planning/grouping boundary that:

- operates after current-curriculum applicability/retrieval has determined the output bucket;
- groups questions sharing one `SourceQuestion` only within the same final bucket;
- preserves deterministic member order;
- derives grouped marks by summing included parts;
- renders a single member normally;
- keeps independent questions separate even when they share context;
- treats inconsistent shared-context links within an otherwise grouped source question as invalid/incomplete rather than guessing.

### 7. Revision HTML integration

Update HTML rendering to consume the presentation plan:

- render shared preamble once where appropriate;
- render grouped multipart member questions together;
- render member answers in matching order;
- preserve source attribution, navigation and reveal-answer behaviour;
- do not duplicate grouping logic inside the HTML renderer.

### 8. SCORM integration

SCORM should package the amended Sprint 07 revision output.

Do not redesign:

- SCORM 1.2 profile;
- manifest structure;
- ZIP mechanics;
- launch architecture.

SCORM must consume the same presentation result as ordinary revision HTML rather than independently rediscovering multipart/context relationships.

### 9. Sprint close-out

- run `mvn test`.
- run `mvn -Pheadless-ui-tests test`.
- perform realistic manual capture/edit/export acceptance.
- inspect final branch diff.
- update Sprint 07 design status.
- update roadmap and backlog.
- update architecture/history/status documentation as appropriate.
- remove outdated design statements such as the earlier manual SourceQuestion/shared-context controls.
- document schema v6 and `PreambleStatus`.
- reconcile the documented `shared_question_context_regions` key description with the implemented schema.
- merge only after review and explicit approval.

## Backlog items discussed during this sprint

These are not part of the remaining Sprint 07 core unless explicitly re-prioritised.

### Multiple-choice one-mark convenience

Add an explicit booklet/import option such as:

```text
One mark per question
```

When enabled:

- Marks displays `1`.
- Marks is read-only for that booklet/workflow.
- do not infer this from booklet names or filenames.

Preferred persistence is booklet-level metadata rather than UI-only state.

### Source-PDF fingerprint recognition

Add content-based recognition for previously imported source PDFs.

Preferred design:

- calculate SHA-256 for managed source documents;
- persist the fingerprint with `source_documents`;
- on import/open, look up by fingerprint rather than filename;
- one match -> offer/fill known exam/booklet metadata and prevent accidental duplicate import;
- multiple matches -> require a choice;
- no match -> normal import workflow.

Filename matching is not sufficient.

### Controlled development-data reset after the new capture workflow is stable

Existing captured development questions may be discarded, but not until the new UI is proven.

When performed, preserve:

- curriculum and mappings;
- exam/booklet/source-document metadata;
- managed source PDFs.

Remove/rebuild as appropriate:

- Questions and question regions;
- Answers and answer regions;
- SourceQuestions;
- SharedQuestionContexts and their regions.

Before doing this, inspect foreign keys and deletion order. Then rerun legacy metadata import.

This is an operational migration/reset task, not a product feature.

### Live application data outside the Git repository

Longer term, use a live data root outside the source tree, for example:

```text
D:\ExamQuestionBankData
```

The repository should contain source, migrations, fixtures and documentation—not the actively edited production database and managed PDFs.

This belongs with deployment/data-management hardening rather than Sprint 07 feature implementation.

### Clipboard / screen-snip questions

Already recorded in the main backlog.

Future workflow:

```text
Snipping Tool / clipboard image
    -> Paste Image / Ctrl+V
    -> durable question attachment
```

Needs decisions on:

- managed-file versus SQLite BLOB storage;
- provenance;
- backup/restore;
- HTML/PDF/SCORM rendering;
- optional OCR later.

Do not create a separate incompatible question hierarchy unless evidence requires it.

### Assisted PDF question-boundary detection

Already recorded in the main backlog.

Possible future workflow:

- extract PDF text positions;
- identify headings such as `QUESTION 12`;
- suggest crop boundaries;
- require user confirmation/correction.

### Assisted multipart/dependency recognition

Already recorded in the main backlog.

Future tooling may suggest source-question relationships from:

- codes such as `24a`, `24b`;
- phrases such as “using your answer to part a”.

Suggestions must remain non-authoritative until confirmed.

### Broad capture/audit queue

Already recorded in the main backlog.

Eventually provide filters for questions needing:

- question regions;
- answer regions;
- unresolved shared context;
- other adopted completion states.

Sprint 07 should only deliver the minimum unresolved-context workflow needed to complete imported records.

## Existing backlog that remains relevant

The repository backlog also retains:

- Chemistry 2019 -> 2025 mapping reconciliation.
- decision on multiple original classifications.
- explicit out-of-scope ingestion disposition.
- retrieval-domain invariant hardening.
- retrieval SQLite integration expansion.
- import audit/reconciliation reporting.
- additional legacy workbook-variant validation.
- legacy image-snip compatibility decision.
- richer mapping relation metadata decision.
- printable/vector-preserving output.
- search and preview performance measurement.
- mapping DB-enforcement decision.
- cosmetic UI polish.
- SQLite-backed revision-export integration coverage.
- self-contained desktop packaging.
- shared-faculty/multi-user architecture.
- public API/lifecycle documentation.

## Documentation corrections now required

The current Sprint 07 design and roadmap still describe Sprint 07 as “design complete / implementation not started”. That is now stale.

The backlog also says Sprint 07 items must not be marked complete until implementation and tests establish them. The implemented sections above can now be updated factually, while editing/output items must remain open.

The main backlog should also gain explicit entries for:

- multiple-choice one-mark booklet option;
- source-PDF SHA-256 recognition;
- controlled development-data reset;
- live application data outside the repository.
