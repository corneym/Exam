# Exam Question Bank — Development Roadmap

> **Reference date:** 7 October 2026  
> **Version:** 24  
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
        ↓
Completed Sprint 13 — UI/Search/Dashboard refinement
        ↓
Sprint 14 — Subject-first managed-data layout
        ↓
Richer Search / descriptor-assignment / measured performance work when prioritised
        ↓
Printable output / Exam Builder / distributed collection when prioritised
```

Sprint 10 merged through pull request #2 (`a3dfda7e`).

Sprint 11 merged to protected `main` through pull request #39 on 27 September 2026. Merge commit: `1ccbb350a693568231bd14582ffbecc7684fb79a`.

Sprint 12 merged to protected `main` through PR #88 on 5 October 2026 as `8ba714e2ef7ff07eebb2375021e1e751d4876e47`. Release 0.2 passed the automated release gate and manual installed-MSI verification.

Sprint 13 merged to protected `main` through PR #107 on 7 October 2026 as `f497f1844c1eebf47f25ebe9e49454b965459f61`. Its full split headless UI closeout run passed 360 tests with zero failures/errors, strict Javadoc passed, and PR CI was green.

Sprint 14 is active under umbrella issue #100 with child issues #101–#106. Its current design is `docs/design/sprint-14.md`.

Git/GitHub remains authoritative for branch heads, pull-request state, issue state and CI state.

## 3. Architectural evolution and current constraints

### 3.1 Workbook/filesystem database → SQLite question bank

The predecessor application used an Excel workbook plus named image snips as its de facto database. The redevelopment retained useful publishing goals but replaced workbook/file naming as runtime authority.

SQLite is the live datastore. Excel is import/exchange/source material only. Sequential migrations, referential integrity and reconstruction tests protect persisted state.

The latest supported schema version remains **19**.

Sprint 12 schema evolution was:

```text
v15  Exam ACTIVE/COMPLETE state + booklet expected top-level Question count
v16  managed source-document SHA-256
v17  explicit Question source recapture requirement
v18  Exam-level expected Question-booklet / Answer-file counts
v19  AnswerFile contains-answer-explanations flag
```

Sprint 13 required no schema migration.

### 3.2 Authoritative Question content

Authoritative Question content may be an ordered mixture of `PDF_REGION` and `IMAGE` content.

PDF regions retain managed-document/page/normalised-rectangle provenance. Clipboard images are stored as PNG BLOBs in SQLite. Ordered content parts define assembly order.

An image-only Question is legitimate captured content. `source_capture_required` separately records the special case where a Question must regain PDF-derived source capture after a booklet PDF replacement even if independent image content survives.

### 3.3 Managed portable data root and source identity

One configured `data.root` derives managed application data. Persisted source-document paths remain portable relative paths rather than workstation-specific absolute paths.

Managed Exam source documents retain SHA-256 content hashes as duplicate/conflict evidence.

Managed syllabus PDFs are part of the portable curriculum data model. `SyllabusVersion.sourcePdfPath` stores a portable managed path and Curriculum Authoring manages attachment/replacement and reopening.

Sprint 13 #97 added managed curriculum-workbook onboarding: a workbook may be selected from anywhere, copied/reused safely under managed curriculum storage, and imported from that managed copy. Users no longer need to pre-position a curriculum workbook inside the data directory.

The current Sprint 14 architecture deliberately evolves this further. Subject becomes the primary managed filesystem partition beneath `data.root`, while SQLite remains authoritative for identities, relationships and corpus state.

### 3.4 Normalised source coordinates

Question, Answer and Shared Context PDF regions use normalised proportional coordinates and one-based domain page numbers. Rendering DPI is an output concern rather than persisted content semantics.

### 3.5 Historical classification and derived current applicability

Historical classification is preserved rather than rewritten to the current syllabus. Confirmed historical → current mappings derive current applicability. Mapping may be one-to-many and remains teacher-reviewed.

A Question stores one best-fit original Subtopic or Descriptor classification. A non-zero booklet `No descriptor` count prevents an ACTIVE Exam from being marked COMPLETE but remains distinct from ordinary content/Answer capture state.

### 3.6 SourceQuestion and SharedQuestionContext remain distinct

`SourceQuestion` represents original multipart identity. `SharedQuestionContext` represents reusable source material.

Multipart codes such as `21a`, `21b`, `21c` reuse persisted source identity and Shared Context decisions. Shared Context remains single-page by design.

### 3.7 Response type belongs to Question

Mixed-response booklets are supported.

```text
MULTIPLE_CHOICE  → valid A/B/C/D answer required
WRITTEN_RESPONSE → one or more Answer regions required
UNKNOWN          → response type unresolved; ordinary Answer capture blocked
```

Booklet format constrains/defaults genuinely new capture but does not rewrite existing Question response type.

### 3.8 Booklet-specific AnswerFile ownership

Each `ExamBooklet` may reference zero or one `AnswerFile`; one `AnswerFile` may serve multiple booklets.

Safe reassignment/replacement rules never silently retain coordinates captured from a different Answer PDF.

### 3.9 Exam planning metadata is not placeholder domain data

Planning values describe expected structure:

- Exam expected Question-booklet count;
- Exam expected Answer-file count;
- booklet expected top-level Question count.

They do not manufacture placeholder domain objects.

### 3.10 Exam lifecycle is explicit but completion is gated

Exam lifecycle is persisted as `ACTIVE ↔ COMPLETE` and changes only through explicit user action.

Completion requires authoritative structural/content readiness, including zero Questions below Descriptor classification level and all required MCQ explanations for explanation-capable booklets.

### 3.11 MCQ explanation semantics

A/B/C/D remains the authoritative MCQ Answer.

`AnswerFile.containsAnswerExplanations` declares whether explanation regions are expected. Missing required explanations form a separate Dashboard completion dimension.

### 3.12 Corpus Dashboard is application home

The Dashboard owns the authoritative Working Subject and exposes curriculum state, Exam/booklet audit, Question work queues, capture routes, lifecycle actions and Exam/Assets management.

Dashboard return paths refresh authoritative persistence state rather than retain stale workflow snapshots.

Sprint 13 #89 fixed the real-corpus Search-classification refresh defect: Descriptor Save now keeps the visible Search result state while the background refresh runs, reselects the edited Question, and closing Search refreshes the Dashboard only when a persisted classification change occurred. `No descriptor` counts therefore update without application restart.

### 3.13 Working Subject is application-level state

The Dashboard Working Subject is authoritative across Exam/Assets, capture, Search, curriculum workflows and Revision output.

Sprint 13 completed the remaining consistency gaps:

- #85 prevents Curriculum Mapping Review from opening when the Working Subject has fewer than two syllabus versions and presents a clear unavailable state;
- #78 removes competing Subject selection from Revision HTML and SCORM export, displays the authoritative Working Subject as read-only context, and rejects export cleanly when no single current syllabus exists.

Secondary workflows must not establish a competing application-level Subject context.

### 3.14 Legacy import uses authoritative current workflows

Legacy Question metadata intake is launched from the Dashboard, inherits Working Subject and resolves missing structure through Exam/Assets.

No second legacy Exam/audit model should be introduced.

Sprint 14 extends the managed-file side of this rule by retaining legacy import workbooks as Subject-scoped source assets; the workbook remains provenance/source material rather than an authoritative database.

### 3.15 Long-running UI work must not block JavaFX

Persistence, refresh and slow PDF work belong off the JavaFX application thread.

Asynchronous work requires stale-request/lifecycle protection.

Sprint 13 #87 moved Dashboard Exam lifecycle persistence plus authoritative Dashboard reload off the JavaFX thread. A compact busy indicator remains active across the complete operation, duplicate lifecycle actions are suppressed, and busy state clears on both success and failure without changing readiness or lifecycle semantics.

### 3.16 Search and Dashboard remain separate concerns

Search answers: “Which Questions do I want to inspect or use?”

Dashboard answers: “What is incomplete/problematic and where do I fix it?”

Sprint 13 #92 added Exam-level and booklet-level `Inspect Questions` actions that open the existing Search workflow with immutable visible narrowing. The narrowing remains active across Search modes and refreshes, so Dashboard can provide structural context without duplicating Search retrieval logic.

This reusable narrowing is the foundation for later richer retrieval-oriented filters under #94.

Issue #99 remains future work for a direct Dashboard workflow that represents and resolves missing Descriptor classification as Question-level work. It must reuse shared classification/persistence behaviour rather than create a parallel Dashboard-only editor.

### 3.17 Performance work follows behaviour

Broad Search benchmarking (#27) is paired with #94 rather than performed against an incomplete workload.

First establish the richer real Search workload. Then measure query plans, reconstruction cost and ordering. Only then decide whether #25, #26, indexes or query redesign are justified.

The Sprint 13 UI-test memory investigation followed the same principle: evidence showed that one monolithic JavaFX/TestFX JVM accumulated enough state to exhaust the heap. The headless regression now runs as four bounded suite JVMs rather than simply increasing heap indefinitely.

### 3.18 Version and Windows release identity

Maven `project.version` remains the authoritative application/release version and feeds packaged application metadata, About/Version Information, backup metadata and Windows package metadata.

Release 0.1 established the self-contained Java 25/jpackage per-user MSI architecture. Release 0.2 retained that architecture and remains the current published release.

### 3.19 Restart after data-root change or restore

Changing the configured data root or publishing a successful restore invalidates important in-memory application state.

Sprint 13 #98 introduced one shared packaged-application restart path integrated with the existing shutdown/resource-close coordination:

- Options prompts `Restart Now` / `Exit` after a changed data root is saved;
- successful restore can use the same restart capability only after restored data has been published and validated;
- normal shutdown/resource-close safety remains authoritative;
- unsupported development/Eclipse execution fails safely rather than guessing an IDE relaunch command.

The packaged launcher path was manually verified with both a jpackage application image and the installed MSI for data-root change and database restore.

## 4. Development history

### Foundation before numbered sprints

August 2026 redevelopment established Java/JavaFX/Maven, Git/GitHub workflow, curriculum modelling, PDF viewing/region capture, managed `data.root`, normalised source rectangles and the move to SQLite.

### Sprint 01 — Legacy Metadata Import

Legacy workbook import reconstructed Exam/Booklet metadata, Questions, historical classification and reliable MCQ answer letters.

### Sprint 02 — Directional Curriculum Applicability

Historical → current mapping direction became explicit. Descriptor/Subtopic same-level mapping, one-to-many relationships and reviewed-state semantics were implemented.

### Sprint 03 — Question Retrieval

Current-curriculum retrieval was implemented across Subject, Unit, Topic, Subtopic and Descriptor scopes while original provenance was retained.

### Sprint 04 — Backup, Restore and Data Safety

Versioned backup archives, SQLite-consistent snapshots, automatic/manual backup, bounded retention, validated restore, pre-restore safety backup, rollback and migration compatibility checks became first-class features.

### Sprint 05 — Hierarchical Revision Corpus and Static HTML Export

A deterministic current-curriculum revision corpus and static student website were implemented.

### Sprint 06 — SCORM 1.2 and QLearn validation

The revision site became the basis for deterministic SCORM 1.2 single-SCO packaging. A real Chemistry package successfully imported and launched in QLearn.

### Sprint 07 — Shared-context-aware capture and UI redesign

Persisted `SourceQuestion` identity and `SharedQuestionContext` were separated. Capture/correction gained Shared Context reuse and syllabus-sensitive editing.

### Sprint 08 — Curriculum and Corpus Completion

Subject-neutral curriculum authoring/lifecycle, mapping coverage/review, response-type-aware completeness and Corpus Audit were implemented.

### Sprint 09 — Capture Workflow and Corpus Correction

Real-corpus use drove sustained capture/correction improvements, UI package refactoring and CI hardening.

Sprint 09 merged on 20 September 2026 in commit `2533586`.

### Sprint 10 — Capture Hardening and Revision Output Refinement

Sprint 10 hardened capture state, Working Subject filtering, booklet-specific AnswerFile assignment, output exclusions and revision/SCORM presentation.

It merged through PR #2 (`a3dfda7e`).

### Sprint 11 — Release 0.1

Sprint 11 added composed integration regressions, clipboard-image Question content, compact Search layout, packaged Help/About/version support and self-contained Windows packaging.

Managed syllabus-PDF infrastructure was already present by later review: source PDFs can be attached into managed curriculum storage and reopened from persisted syllabus metadata. Issue #83 was retrospectively classified as completed in this period.

Release 0.1 merged through PR #39.

### Sprint 12 — Exam Intake, Capture Workflow and Corpus Dashboard

Sprint 12 completed authoritative Exam/Assets, planning counts, source replacement/deletion safety, streamlined capture, MCQ explanation requirements and the operational Corpus Dashboard as application home.

It merged through PR #88 and produced Release 0.2 after the automated release gate and installed-MSI verification.

### Sprint 13 — UI Refactoring, Search and Dashboard Refinement

Sprint 13 completed the bounded refinement work planned after real-corpus use:

1. #93 — behaviour-preserving UI refactoring;
2. #89 — Search classification Save/refresh defects;
3. #82 — Search layout polish;
4. #92 — Dashboard → Exam/booklet-scoped Question Search;
5. #86 — Dashboard column alignment;
6. #87 — asynchronous Dashboard lifecycle progress/responsiveness;
7. #85 + #78 — Working Subject consistency.

Two additional user-visible workflow issues were completed during the sprint:

8. #97 — curriculum workbooks selectable anywhere and retained as managed copies;
9. #98 — automatic packaged-app restart after data-root change or successful restore.

The sprint also replaced the locally problematic monolithic headless UI regression with four bounded suite JVMs while retaining complete UI coverage.

Sprint 13 merged through PR #107 on 7 October 2026. Merge commit: `f497f1844c1eebf47f25ebe9e49454b965459f61`.

See `docs/design/sprint-13-ui-refinement.md`.

### Sprint 14 — Subject-First Managed Data Layout

Sprint 14 is active under umbrella issue #100.

The target filesystem contract makes Subject the primary managed-data partition beneath `data.root`, centralises path construction and standardises new managed paths as data-root-relative.

Current child issues are:

- #101 — Subject-first managed data layout contract;
- #102 — move Exam and Answer PDFs;
- #103 — move curriculum assets;
- #104 — retain legacy import workbooks as managed Subject assets;
- #105 — migrate existing data roots safely;
- #106 — update backup/restore for the Subject-first layout.

SQLite remains authoritative. The Subject directory is a portable managed-source domain, not a second database.

See `docs/design/sprint-14.md`.

## 5. Forward direction

Immediate work is Sprint 14’s Subject-first managed-data restructure.

Beyond the active sprint, priorities remain deliberately issue-driven rather than fixed to old sprint numbers:

1. richer retrieval-oriented Question Search filters (#94);
2. benchmark the resulting real Search workload (#27);
3. use evidence to decide whether retrieval hardening (#25/#26) or optimisation is required;
4. direct Dashboard missing-Descriptor workflow (#99);
5. printable/vector-preserving assessment and solution output (#38);
6. Exam Builder (#37);
7. portable/distributed collection packages (#36).

The Subject-first Sprint 14 work is intended to simplify later distributed collection, but it does not implement worker/coordinator exchange.

Capture productivity assistance (#95) remains evidence-driven rather than automatically promoted.

## 6. Development discipline

1. Inspect the current repository and current GitHub issue before exact implementation work.
2. Design substantial changes before coding.
3. Work in small behaviour-focused slices.
4. Prefer integration/UI regressions that reproduce real production failure modes.
5. Maintain public API Javadoc and meaningful algorithmic comments.
6. Keep slow work off the JavaFX thread and protect asynchronous results from stale generations.
7. Use deterministic TestFX interactions.
8. Use bounded test-process structure when JavaFX/PDF state makes one monolithic JVM unreliable.
9. Require green CI before protected-main merge.
10. Keep sprint documents as historical evidence after closeout.
11. Keep genuinely deferred work in `docs/design/backlog.md`.
12. Use `scripts/build-release.ps1` as the release gate rather than ad hoc packaging.
