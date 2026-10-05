# Exam Question Bank — Development Roadmap

> **Reference date:** 5 October 2026  
> **Version:** 22  
> **Repository location:** `docs/development-roadmap.md`
>
> This is the canonical durable project record: project history, architectural evolution and forward roadmap. Detailed sprint evidence remains in `docs/design/sprint-*.md`.

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves authoritative examination content, historical curriculum provenance and reusable marking material, then turns that bank into useful resources for students and teachers.

Chemistry remains the first full development dataset. Core model, repository, service, persistence and output logic remain subject-neutral.

Long-term purposes are to maintain a durable curriculum-aware bank of Questions and marking material, generate student revision resources, assemble teacher assessments and printable marking resources, and support practical deployment and later assisted/distributed workflows.

## 2. Current strategic path

```text
Completed application/data/curriculum foundations
        ↓
Completed backup/restore and data safety
        ↓
Completed revision HTML + SCORM output
        ↓
Completed shared-context/capture/edit foundations
        ↓
Completed curriculum/corpus tooling
        ↓
Completed Sprint 09 sustained-use capture/correction
        ↓
Completed Sprint 10 capture/output hardening
        ↓
Completed Sprint 11 — Release 0.1
        ↓
Completed Sprint 12 — Release 0.2
PR #88 merged to protected main
Exam intake + asset management + streamlined capture
+ operational Corpus Dashboard
        ↓
Release 0.2 verified and post-merge CI green
        ↓
Real-corpus use and evidence-driven refinement
        ↓
Exam Builder + printable output when prioritised
```

Sprint 10 merged through pull request #2 (`a3dfda7e`); post-merge GitHub Actions run 64 succeeded.

Sprint 11 merged to protected `main` through pull request #39 on 27 September 2026. Merge commit: `1ccbb350a693568231bd14582ffbecc7684fb79a`. Final feature-branch CI run 81 succeeded.

Sprint 12 implementation is recorded in `docs/design/sprint-12-release-0.2.md`. PR #88 merged to protected `main` on 5 October 2026 as `8ba714e2ef7ff07eebb2375021e1e751d4876e47`. The automated Release 0.2 gate and manual installed-MSI verification passed; Maven/application/MSI version is 0.2. Post-merge CI run #164 and CodeQL run #15 passed on the merge commit, and issue #64 is closed.

Git/GitHub remains authoritative for branch heads, pull-request state, issue state and CI state.

## 3. Architectural evolution and current constraints

### 3.1 Workbook/filesystem database → SQLite question bank

The predecessor application used an Excel workbook plus named image snips as its de facto database. The redevelopment retained useful publishing goals but replaced workbook/file naming as runtime authority.

SQLite is the live datastore. Excel is import/exchange only. Sequential migrations, referential integrity and reconstruction tests protect persisted state.

The latest supported schema version on Sprint 12 is **19**.

Sprint 12 schema evolution is:

```text
v15  Exam ACTIVE/COMPLETE state + booklet expected top-level Question count
v16  managed source-document SHA-256
v17  explicit Question source recapture requirement
v18  Exam-level expected Question-booklet / Answer-file counts
v19  AnswerFile contains-answer-explanations flag
```

### 3.2 Authoritative Question content

Authoritative Question content may be an ordered mixture of:

```text
PDF_REGION
IMAGE
```

PDF regions retain managed-document/page/normalised-rectangle provenance. Clipboard images are stored as PNG BLOBs in SQLite. Ordered content parts define assembly order.

Sprint 12 clarified that an image-only Question is legitimate captured content. Separately, `source_capture_required` records the special case where a Question must regain PDF-derived source capture after a booklet PDF replacement even if independent image content survives.

### 3.3 Managed portable data root and source identity

One configured `data.root` derives managed PDF, curriculum and SQLite locations. Persisted source-document paths remain portable relative paths beneath that root.

Sprint 12 added SHA-256 content hashes to managed source documents. Hashes provide duplicate/conflict evidence independent of filename/path but are deliberately not a database uniqueness constraint.

Question/Answer PDF replacement and Exam metadata relocation preserve the invariant that stored region coordinates are valid only for the source document against which they were captured. Old coordinates are invalidated rather than silently remapped.

### 3.4 Normalised source coordinates

Question, Answer and Shared Context PDF regions use normalised proportional coordinates and one-based domain page numbers. Rendering DPI is an output concern rather than persisted content semantics.

### 3.5 Historical classification and derived current applicability

Historical classification is preserved rather than rewritten to the current syllabus. Confirmed historical → current mappings derive current applicability. Mapping may be one-to-many and remains teacher-reviewed.

A Question stores one best-fit original Subtopic or Descriptor classification. Sprint 12 added explicit reporting of Questions that have not reached Descriptor level. A non-zero booklet `No descriptor` count prevents an ACTIVE Exam from being marked COMPLETE (#81) but remains separate from the ordinary Question-work queue.

### 3.6 SourceQuestion and SharedQuestionContext remain distinct

`SourceQuestion` represents original multipart identity. `SharedQuestionContext` represents reusable source material.

Multipart codes such as `21a`, `21b`, `21c` reuse persisted source identity and Shared Context decisions. Shared Context remains single-page by design. Multi-page Shared Context capture is rejected, not backlog work.

### 3.7 Response type belongs to Question

Mixed-response booklets are supported. Runtime Answer behaviour does not depend on booklet-name inference.

```text
MULTIPLE_CHOICE  → valid A/B/C/D answer required
WRITTEN_RESPONSE → one or more Answer regions required
UNKNOWN          → response type unresolved; ordinary Answer capture blocked
```

Booklet format constrains/defaults genuinely new capture but does not rewrite existing Question response type. The proposal for additional per-response-type expected counts in mixed booklets (#73) was closed as not planned; one total expected top-level Question count remains authoritative.

### 3.8 Booklet-specific AnswerFile ownership

Each `ExamBooklet` may reference zero or one `AnswerFile`; one `AnswerFile` may serve multiple booklets. This superseded earlier Exam-wide Answer-PDF heuristics.

Sprint 12 made the assignment visible/editable through Exam/Assets and added safe reassignment/replacement rules that never silently retain coordinates captured from a different Answer PDF.

### 3.9 Exam planning metadata is not placeholder domain data

Sprint 12 introduced explicit planning values:

- Exam expected Question-booklet count;
- Exam expected Answer-file count;
- booklet expected top-level Question count.

These values describe expected structure. They do not manufacture placeholder Exams, booklets, Questions or Answers.

Top-level Question counts use source identity; multipart parts such as `21a`, `21b`, `21c` count as one source Question.

### 3.10 Exam lifecycle is explicit but completion is gated

Exam lifecycle is persisted as:

```text
ACTIVE ↔ COMPLETE
```

The state changes only through an explicit user action; audit never silently toggles it. However Sprint 12’s final accepted design gates `Mark Complete` on readiness rather than permitting a knowingly structurally incomplete transition.

An Exam is ready for completion only when:

- expected Question-booklet and Answer-file counts are recorded and match available assets;
- every Question booklet has its source PDF;
- every booklet has an expected top-level Question count matching the encountered source count;
- ordinary Question work is complete (content, Answer, response type and Shared Context);
- every Question is classified to Descriptor level;
- every explanation-capable booklet has all required MCQ explanation regions captured.

Structural management requires an ACTIVE Exam. COMPLETE Exams remain visible in the Dashboard and can still receive permitted correction/enrichment work; reactivation is required before structural change.

### 3.11 MCQ explanation semantics

The stored A/B/C/D value remains the authoritative MCQ Answer and determines ordinary Answer completeness.

`AnswerFile.containsAnswerExplanations` is persisted asset metadata. The final Sprint 12 acceptance decision is stronger than the early “optional explanation” wording in issues #55–#57: when an assigned AnswerFile is marked as containing explanations, explanation regions are required for every eligible answered MCQ before the Exam can be marked COMPLETE.

This remains a separate work dimension rather than `MISSING_ANSWER`. Dashboard reporting shows booklet-level captured/eligible coverage, a missing-explanations summary/drill-down and direct capture actions.

### 3.12 Corpus Dashboard is application home

Sprint 12 replaced the old standalone Corpus Audit presentation with an operational Dashboard. The Dashboard owns the authoritative Working Subject and exposes:

- curriculum availability and mapping-review status;
- Provider/year/Exam-state filters;
- Exam structural/audit tables;
- booklet source, Answer assignment, expected/found counts, descriptor gaps and MCQ explanation coverage;
- Question work queues for missing content, Answers, response type and Shared Context;
- direct Question/Answer/explanation capture routes;
- Exam lifecycle actions;
- Manage Exam / Assets;
- Add Subject, Add Curriculum, Add Exam and legacy-import entry points where valid.

A Subject must have curriculum before an Exam can be added. Dashboard return paths refresh authoritative persistence state rather than retaining stale workflow snapshots.

### 3.13 Working Subject is application-level state

The Dashboard’s Working Subject is authoritative across Exam/Assets, capture, Search and curriculum workflows. Secondary workflows inherit that Subject and do not provide competing Subject selectors.

Subject refresh is application-coordinated and asynchronous (#75). Persistence-heavy loading runs away from the JavaFX application thread; Question and Answer capture reuse one loaded Question snapshot; curriculum/Exam state is published on the JavaFX thread; stale generations are discarded. Existing capture/edit guards remain synchronous.

### 3.14 Legacy import uses authoritative current workflows

Legacy Question metadata intake is launched from the Dashboard (#68). The current Working Subject is inherited; historical syllabus/workbook selection remains explicit. Import preflight reuses existing Exam/booklet assets and sends missing structure through Exam/Assets rather than reviving a parallel legacy Exam-creation model.

The earlier umbrella issue #22 is superseded by #58, #65, #68 and #81; no second import-audit system should be implemented.

### 3.15 Safe destructive asset deletion

Sprint 12 added explicit Question-booklet and AnswerFile deletion (#80). Deletion is an ACTIVE-Exam structural operation with destructive confirmation. Persistence cleanup is transactional; dependent metadata/regions are removed; managed PDFs are deleted only when their source-document record is no longer referenced. Expected planning counts are not silently reduced, allowing the Dashboard to report the resulting shortfall.

### 3.16 Long-running UI work must not block JavaFX

Persistence, refresh and slow PDF work belong off the JavaFX application thread. Asynchronous work requires stale-request/lifecycle protection.

TestFX workflow tests use semantic activation for ordinary controls and reserve pointer operations for genuine gestures such as PDF-region dragging. Modal-dialog tests resolve real `DialogPane` buttons.

Sprint 12 applied these rules heavily to Dashboard routing, capture transitions, Subject refresh, PDF loading and the Question/Answer pane refactors (#70, #79).

### 3.17 Search and Dashboard remain separate concerns

Search answers retrieval/edit questions: “which Questions do I want to inspect or use?”

Dashboard answers operational questions: “what is incomplete/problematic and where do I fix it?”

Richer retrieval-oriented Search filtering remains deferred. Do not copy Dashboard work-queue semantics into Search without a genuine retrieval use case.

### 3.18 Version and Windows release identity

Maven `project.version` remains the authoritative application/release version and feeds packaged application metadata, About/Version Information, backup metadata and Windows package metadata.

Release 0.1 established the self-contained Java 25/jpackage per-user MSI architecture. Sprint 12 completed Release 0.2 with the same architecture: the automated gate and manual installed-MSI verification passed with Maven/application/MSI version 0.2, PR #88 merged to protected `main`, and post-merge CI/CodeQL passed. See `docs/release-build.md`.

## 4. Development history

### Foundation before numbered sprints

August 2026 redevelopment established Java/JavaFX/Maven, Git/GitHub workflow, curriculum modelling, PDF viewing/region capture, managed `data.root`, normalised source rectangles and the move to SQLite.

### Sprint 01 — Legacy Metadata Import

Legacy workbook import reconstructed Exam/Booklet metadata, Questions, historical classification and reliable MCQ answer letters. Managed source documents were registered and import became conflict-aware/idempotent.

### Sprint 02 — Directional Curriculum Applicability

Historical → current mapping direction became explicit. Descriptor/Subtopic same-level mapping, one-to-many relationships and reviewed-state semantics were implemented. Only confirmed mappings affect applicability.

### Sprint 03 — Question Retrieval

Current-curriculum retrieval was implemented across Subject, Unit, Topic, Subtopic and Descriptor scopes while original provenance was retained. Search/preview became asynchronous with stale/lifecycle protection.

### Sprint 04 — Backup, Restore and Data Safety

Versioned backup archives, SQLite-consistent snapshots, automatic/manual backup, bounded retention, validated restore, pre-restore safety backup, rollback and migration compatibility checks became first-class features.

The old-backup regression later requested by issue #34 already existed and proves schema-v11 backup → production restore → sequential migration → reopen/integrity verification through later schemas.

### Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

A deterministic current-curriculum revision corpus and static student website were implemented with rendered Question/Answer assets, hierarchical navigation, generated numbering/marks, Answer disclosure and provenance.

### Sprint 06 — SCORM 1.2 and QLearn validation

The revision site became the basis for deterministic SCORM 1.2 single-SCO packaging. A real Chemistry package successfully imported and launched in QLearn.

### Sprint 07 — Shared-context-aware capture and UI redesign

Persisted `SourceQuestion` identity and `SharedQuestionContext` were separated. Capture/correction gained Shared Context reuse, syllabus-sensitive classification, Question/Answer editing and asynchronous persistence/PDF transitions.

### Sprint 08 — Curriculum and Corpus Completion

Subject-neutral curriculum authoring/lifecycle, mapping coverage/review, legacy metadata correction, persisted Question response type, mixed-response booklet support, response-type-aware completeness and Corpus Audit were implemented.

### Sprint 09 — Capture Workflow and Corpus Correction

Real-corpus use drove capture/correction improvements: deterministic ordering, responsive controls, Shared Context recapture, authoritative Exam correction with managed-file relocation, known-PDF reuse, legacy Question split, Search scope, Corpus Audit ordering, MCQ Answer-PDF visibility, UI package refactoring and CI hardening.

Sprint 09 merged on 20 September 2026 in commit `2533586`.

### Sprint 10 — Capture Hardening and Revision Output Refinement

Sprint 10 hardened capture state, Working Subject filtering, booklet-specific AnswerFile assignment, independent-MCQ Shared Context continuation, revision-output exclusions, Search correction and revision/SCORM presentation. It merged through PR #2 (`a3dfda7e`).

### Sprint 11 — Release 0.1

Sprint 11 added composed integration regressions, clipboard-image Question content, the compact Search layout, tooltips, packaged Help/About/version support and self-contained Windows app-image/MSI packaging. Release 0.1 passed install/launch/uninstall/configuration-survival checks and merged through PR #39.

### Sprint 12 — Exam Intake, Capture Workflow and Corpus Dashboard

Sprint 12 is complete and merged through PR [#88](https://github.com/corneym/Exam/pull/88). Merge commit `8ba714e2ef7ff07eebb2375021e1e751d4876e47` landed on protected `main` on 5 October 2026; post-merge CI run #164 and CodeQL run #15 passed. The automated Release 0.2 gate and manual MSI verification also passed.

The implementation spans the following issue groups:

- Exam/Assets and planning: #43–#50, #55, #69, #72, #74, #80;
- capture and state cleanup: #51–#54, #59, #70, #71, #75, #79, #84;
- MCQ explanation capture: #56, #57, with final completion semantics established in #65;
- Dashboard/audit/home/Subject integration: #58, #60–#62, #65–#67, #76, #77, #81;
- legacy import reconciliation: #68, with umbrella #22 superseded;
- maintained Help: #63.

Final pre-PR review hardened the branch without changing accepted product semantics: duplicate Dashboard MCQ-explanation handler wiring was removed, ordinary TestFX activation/scene-graph lookup/save waits were made deterministic, and destructive Exam asset deletion gained direct SQLite regression coverage. Verification passed 1,031 non-UI tests (3 expected skips), 342 UI tests, a combined 1,373-test AllTests run (3 expected skips), Maven `clean verify`, strict Javadoc, Spotless and `git diff --check`, all with zero failures/errors.

The formal `scripts/build-release.ps1` gate subsequently passed and produced the Release 0.2 MSI; Maven/application/MSI version is 0.2. Manual verification outside Eclipse passed Dashboard startup, existing persisted-data access, Help, About reporting 0.2, normal shutdown and uninstall with configuration/data preserved. Hard-coded Release 0.1 expectations in `ApplicationVersionTest` and the About-dialog `ApplicationLifecycleWorkflowTest` were corrected during the gate.

Issue #73 was closed as not planned. Issue #64 was closed as completed after merge and post-merge CI evidence were recorded. The implemented Sprint 12 issues were closed after PR #88 merged; deliberately deferred items remain open and are maintained in `docs/design/backlog.md` rather than being silently folded into Sprint 12.

Detailed implementation decisions, acceptance changes, verification and consolidated screen designs are in `docs/design/sprint-12-release-0.2.md`.

## 5. Forward direction after Sprint 12

Immediate next work is not another broad redesign. Release 0.2 is complete; use the real corpus to prioritise evidence-driven refinements.

Explicitly deferred issues include revision-export Working Subject scoping (#78), Search layout polish (#82), managed syllabus documents (#83), mapping-review empty-state behaviour (#85), Dashboard column alignment (#86) and lifecycle progress feedback (#87). Richer Search filtering also remains a high-priority retrieval backlog item.

Longer-term product areas remain:

- portable offline collection packages for distributed collection;
- Exam Builder;
- printable/vector-preserving assessment and solution output;
- measured retrieval/performance hardening;
- later curriculum/capture assistance where real use justifies it.

## 6. Development discipline

1. Inspect the current repository and current GitHub issue before exact implementation work.
2. Design substantial changes before coding.
3. Work in small behaviour-focused slices.
4. Prefer integration/UI regressions that reproduce real production failure modes.
5. Maintain public API Javadoc and meaningful algorithmic comments.
6. Keep slow work off the JavaFX thread and protect asynchronous results from stale generations.
7. Use deterministic TestFX interactions.
8. Require green CI before protected-main merge.
9. Keep sprint documents as historical evidence after closeout.
10. Keep genuinely deferred work in `docs/design/backlog.md`.
11. Use `scripts/build-release.ps1` as the release gate rather than ad hoc packaging.
