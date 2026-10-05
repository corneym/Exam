# Exam Question Bank — Development Roadmap

> **Reference date:** 5 October 2026  
> **Version:** 23  
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
Sprint 13 — bounded UI/Search/Dashboard refinement
        ↓
Richer Search filters (#94) + benchmark real workload (#27)
        ↓
Sprint 14 printable/vector-preserving output (#38)
        ↓
Exam Builder / distributed collection when prioritised
```

Sprint 10 merged through pull request #2 (`a3dfda7e`).

Sprint 11 merged to protected `main` through pull request #39 on 27 September 2026. Merge commit: `1ccbb350a693568231bd14582ffbecc7684fb79a`.

Sprint 12 merged to protected `main` through PR #88 on 5 October 2026 as `8ba714e2ef7ff07eebb2375021e1e751d4876e47`. Release 0.2 passed the automated release gate, manual installed-MSI verification, post-merge CI run #164 and CodeQL run #15.

Git/GitHub remains authoritative for branch heads, pull-request state, issue state and CI state.

## 3. Architectural evolution and current constraints

### 3.1 Workbook/filesystem database → SQLite question bank

The predecessor application used an Excel workbook plus named image snips as its de facto database. The redevelopment retained useful publishing goals but replaced workbook/file naming as runtime authority.

SQLite is the live datastore. Excel is import/exchange only. Sequential migrations, referential integrity and reconstruction tests protect persisted state.

The latest supported schema version after Sprint 12 is **19**.

Sprint 12 schema evolution was:

```text
v15  Exam ACTIVE/COMPLETE state + booklet expected top-level Question count
v16  managed source-document SHA-256
v17  explicit Question source recapture requirement
v18  Exam-level expected Question-booklet / Answer-file counts
v19  AnswerFile contains-answer-explanations flag
```

### 3.2 Authoritative Question content

Authoritative Question content may be an ordered mixture of `PDF_REGION` and `IMAGE` content.

PDF regions retain managed-document/page/normalised-rectangle provenance. Clipboard images are stored as PNG BLOBs in SQLite. Ordered content parts define assembly order.

An image-only Question is legitimate captured content. `source_capture_required` separately records the special case where a Question must regain PDF-derived source capture after a booklet PDF replacement even if independent image content survives.

### 3.3 Managed portable data root and source identity

One configured `data.root` derives managed PDF, curriculum and SQLite locations. Persisted source-document paths remain portable relative paths beneath that root.

Managed Exam source documents retain SHA-256 content hashes as duplicate/conflict evidence.

Managed syllabus PDFs are also already part of the portable curriculum data model. `SyllabusVersion.sourcePdfPath` stores a portable relative path beneath the curriculum root. Curriculum Authoring manages attachment/replacement and automatically resolves the persisted PDF on reopen.

Issue #83 was therefore closed as completed and retrospectively assigned to Sprint 11 rather than creating duplicate Sprint 13 storage work.

### 3.4 Normalised source coordinates

Question, Answer and Shared Context PDF regions use normalised proportional coordinates and one-based domain page numbers. Rendering DPI is an output concern rather than persisted content semantics.

### 3.5 Historical classification and derived current applicability

Historical classification is preserved rather than rewritten to the current syllabus. Confirmed historical → current mappings derive current applicability. Mapping may be one-to-many and remains teacher-reviewed.

A Question stores one best-fit original Subtopic or Descriptor classification. A non-zero booklet `No descriptor` count prevents an ACTIVE Exam from being marked COMPLETE but remains separate from the ordinary Question-work queue.

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

Dashboard return paths must refresh authoritative persistence state rather than retain stale workflow snapshots.

A Sprint 13 defect confirmed that Search classification Save currently persists correctly but closing Search can leave the Dashboard snapshot stale. This is tracked under #89 and must be corrected without requiring application restart.

### 3.13 Working Subject is application-level state

The Dashboard Working Subject is authoritative across Exam/Assets, capture, Search and curriculum workflows.

Sprint 13 closes the remaining consistency gaps:

- #85 prevents Mapping Review from opening when the Working Subject has no second syllabus;
- #78 scopes Revision HTML and SCORM export to the authoritative Working Subject rather than a competing dialog Subject selection.

### 3.14 Legacy import uses authoritative current workflows

Legacy Question metadata intake is launched from the Dashboard, inherits Working Subject and resolves missing structure through Exam/Assets.

No second legacy Exam/audit model should be introduced.

### 3.15 Long-running UI work must not block JavaFX

Persistence, refresh and slow PDF work belong off the JavaFX application thread.

Asynchronous work requires stale-request/lifecycle protection.

Sprint 13 applies this rule particularly to Dashboard lifecycle progress (#87) and preserves it during #93 refactoring.

### 3.16 Search and Dashboard remain separate concerns

Search answers: “Which Questions do I want to inspect or use?”

Dashboard answers: “What is incomplete/problematic and where do I fix it?”

Sprint 13 #92 may open Search with an initial Exam/booklet scope from Dashboard. This is still retrieval, not a second Dashboard work queue.

Richer retrieval-oriented filters are tracked by #94 for later work.

### 3.17 Performance work follows behaviour

Broad Search benchmarking (#27) is paired with #94 rather than Sprint 13.

First establish the richer real Search workload. Then measure query plans, reconstruction cost and ordering. Only then decide whether #25, #26, indexes or query redesign are justified.

### 3.18 Version and Windows release identity

Maven `project.version` remains the authoritative application/release version and feeds packaged application metadata, About/Version Information, backup metadata and Windows package metadata.

Release 0.1 established the self-contained Java 25/jpackage per-user MSI architecture. Release 0.2 retained that architecture and passed the formal release gate and installed-MSI verification.

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

Sprint 12 is complete and merged through PR #88. It established authoritative Exam/Assets, planning counts, source replacement/deletion safety, streamlined capture, MCQ explanation requirements and the operational Corpus Dashboard as application home.

Release 0.2 passed 1,031 non-UI tests, 342 UI tests, a combined 1,373-test AllTests run with 3 expected skips, Maven `clean verify`, strict Javadoc, Spotless, release packaging and manual installed-MSI verification.

### Sprint 13 — UI Refactoring, Search and Dashboard Refinement

Sprint 13 is planned as a bounded refinement sprint rather than a broad redesign.

Current slices are:

1. #93 UI TODO refactoring;
2. #89 Search classification Save/refresh defects;
3. #82 Search layout polish;
4. #92 Dashboard → Exam/booklet-scoped Question Search;
5. #86 Dashboard column alignment;
6. #87 Dashboard lifecycle progress/responsiveness;
7. #85 + #78 Working Subject consistency.

#83 is complete and outside the sprint. #27 moves with #94. #38 moves to Sprint 14.

See `docs/design/sprint-13-ui-refinement.md`.

## 5. Forward direction

Immediate work is Sprint 13’s bounded UI/Search/Dashboard refinement.

After Sprint 13:

1. implement richer retrieval-oriented Search filters (#94);
2. benchmark the resulting real Search workload (#27);
3. use evidence to decide whether retrieval hardening (#25/#26) or optimisation is required;
4. take printable/vector-preserving assessment and solution output (#38) into Sprint 14;
5. continue longer-term Exam Builder and portable distributed collection only when prioritised.

Capture productivity assistance (#95) remains evidence-driven rather than automatically promoted.

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
