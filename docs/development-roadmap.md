# Exam Question Bank — Development Roadmap

> **Reference date:** 26 November 2026  
> **Version:** 25  
> **Repository location:** `docs/development-roadmap.md`
>
> This is the canonical durable project record: project history, architectural evolution and forward roadmap. Detailed sprint evidence remains in `docs/design/sprint-*.md`. GitHub Issues, Project, pull requests, source, tests and Releases are authoritative for live status.

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves authoritative examination content, historical curriculum provenance and reusable marking material, then turns that bank into useful resources for students and teachers.

Chemistry remains the first full development dataset. Core model, repository, service, persistence and output logic remain subject-neutral.

Long-term purposes are to maintain a durable curriculum-aware bank of Questions and marking material, generate student revision resources, assemble teacher assessments and printable marking resources, and support practical deployment, assisted capture and distributed collection.

## 2. Current strategic path

```text
Application/data/curriculum foundations
        ↓
Backup/restore and data safety
        ↓
Revision HTML + SCORM output
        ↓
Shared-context/capture/edit foundations
        ↓
Curriculum/corpus tooling and sustained-use refinement
        ↓
Self-contained Windows releases and Subject-first managed storage
        ↓
Release 1.0 and subsequent maintenance
        ↓
Sprint 24 — offline collection and faculty consolidation
        ↓
Initial multi-Subject deployment and evidence-led follow-up
        ↓
Printable output / Exam Builder / capture assistance when prioritised
```

At the 26 November 2026 checkpoint, the latest published release is `v1.0.1`. The Sprint 24 candidate still records Maven version `1.0.1` pending the formal next-release build; do not infer that the new offline exchange functionality has already been published.

Sprint 24 integration #276 is functionally complete. The coordinator/contributor exchange, initial HoD Subject import and subsequent HoD refresh were demonstrated in manual acceptance (17/18 reported passes, limitations accepted). The independent audit identified and corrected one P1 imported-Subject authority defect. All 22 GitHub CI checks passed on `fea74e90cccf6332d1c2a3bb43ed70d5e79bd97e`; post-audit Javadoc was clean. Integration of the audit branch, final PR/merge and formal release remain pending at this checkpoint. See [Sprint 24 verification record](design/sprint-24-offline-exchange.md) and [operating guide](offline-exchange.md).

## 3. Architectural evolution and current constraints

### 3.1 Workbook/filesystem database → SQLite question bank

The predecessor application used an Excel workbook plus named image snips as its de facto database. The redevelopment retained useful publishing goals but replaced workbook/file naming as runtime authority.

SQLite is the live datastore. Excel is import/exchange/source material only. Sequential migrations, referential integrity and reconstruction tests protect persisted state.

The audited Sprint 24 schema is **49**. Sprint 12 schema evolution was:

```text
v15  Exam ACTIVE/COMPLETE state + booklet expected top-level Question count
v16  managed source-document SHA-256
v17  explicit Question source recapture requirement
v18  Exam-level expected Question-booklet / Answer-file counts
v19  AnswerFile contains-answer-explanations flag
```

Sprint 13 required no schema migration. Later migrations evolved the corpus and portable exchange infrastructure. Sprint 24 adds v48 imported-Subject authority and contributor disposition evidence, and v49 verified originating snapshot freshness evidence. The final audit introduced no further migration. Inspect `src/main/resources/db/` and the current migration tests for the complete schema evolution. The consolidated-baseline proposal #297 is not an implemented replacement for sequential migration.

### 3.2 Authoritative Question content

Authoritative Question content may be an ordered mixture of `PDF_REGION` and `IMAGE` content.

PDF regions retain managed-document/page/normalised-rectangle provenance. Clipboard images are stored as PNG BLOBs in SQLite. Ordered content parts define assembly order.

An image-only Question is legitimate captured content. `source_capture_required` separately records the special case where a Question must regain PDF-derived source capture after a booklet PDF replacement even if independent image content survives.

### 3.3 Managed portable data root and source identity

One configured `data.root` derives managed application data. Persisted source-document paths remain portable relative paths rather than workstation-specific absolute paths.

Managed Exam source documents retain SHA-256 content hashes as duplicate/conflict evidence.

Managed syllabus PDFs are part of the portable curriculum data model. `SyllabusVersion.sourcePdfPath` stores a portable managed path and Curriculum Authoring manages attachment/replacement and reopening.

Sprint 13 #97 added managed curriculum-workbook onboarding: a workbook may be selected from anywhere, copied/reused safely under managed curriculum storage, and imported from that managed copy. Users no longer need to pre-position a curriculum workbook inside the data directory.

Sprint 14 evolved this further: Subject is the primary managed filesystem partition beneath `data.root`, while SQLite remains authoritative for identities, relationships and corpus state. Startup checks prevent mixed old/new layout semantics; see [data-layout-migration.md](data-layout-migration.md). Exchange archives carry validated managed resources rather than workstation-local absolute paths.

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

Safe reassignment/replacement rules never silently retain coordinates captured from a different Answer PDF. Ordinary mutation is also subject to persisted Subject authority and assignment restrictions.

### 3.9 Exam planning metadata is not placeholder domain data

Planning values describe expected structure:

- Exam expected Question-booklet count;
- Exam expected Answer-file count;
- booklet expected top-level Question count.

They do not manufacture placeholder domain objects.

### 3.10 Exam lifecycle is explicit but completion is gated

Exam lifecycle is persisted as `ACTIVE ↔ COMPLETE` and changes only through explicit user action.

Completion requires authoritative structural/content readiness, including zero Questions below Descriptor classification level and all required MCQ explanations for explanation-capable booklets. Assignment locking is a distinct coordinator state: an outstanding assignment protects the authoritative Exam until merge or cancellation, and is not itself Exam completion.

### 3.11 MCQ explanation semantics

A/B/C/D remains the authoritative MCQ Answer.

`AnswerFile.containsAnswerExplanations` declares whether explanation regions are expected. Missing required explanations form a separate Dashboard completion dimension. The mixed-Exam Answer/Explanation capture sequence and reporting need refinement under #307; the accepted release limitation is not a new answer semantic.

### 3.12 Corpus Dashboard is application home

The Dashboard owns the authoritative Working Subject and exposes curriculum state, Exam/booklet audit, Question work queues, capture routes, lifecycle actions and Exam/Assets management.

Dashboard return paths refresh authoritative persistence state rather than retain stale workflow snapshots.

Sprint 13 #89 fixed the real-corpus Search-classification refresh defect: Descriptor Save keeps the visible Search result state while the background refresh runs, reselects the edited Question, and closing Search refreshes the Dashboard only when a persisted classification change occurred. `No descriptor` counts therefore update without application restart.

Sprint 24 adds coordinator assignment locks and automatic contributor selection/refresh. Submitted assignments disappear from the active Dashboard workspace. Known delays after returning from capture are tracked in #302; residual submitted Search/output access and HoD asset viewing remain #303/#304.

### 3.13 Working Subject is application-level state

The Dashboard Working Subject is authoritative across Exam/Assets, capture, Search, curriculum workflows and Revision output.

Sprint 13 completed the remaining consistency gaps:

- #85 prevents Curriculum Mapping Review from opening when the Working Subject has fewer than two syllabus versions and presents a clear unavailable state;
- #78 removes competing Subject selection from Revision HTML and SCORM export, displays the authoritative Working Subject as read-only context, and rejects export cleanly when no single current syllabus exists.

Secondary workflows must not establish a competing application-level Subject context. Sprint 24 adds durable Subject authority and active-assignment context rather than a second application role system.

### 3.14 Legacy import uses authoritative current workflows

Legacy Question metadata intake is launched from the Dashboard, inherits Working Subject and resolves missing structure through Exam/Assets.

No second legacy Exam/audit model should be introduced.

Sprint 14 extends managed-file handling by retaining legacy import workbooks as Subject-scoped source assets; the workbook remains provenance/source material rather than an authoritative database. The Sprint 24 authority audit protects ordinary legacy import from modifying imported Subjects.

### 3.15 Long-running UI work must not block JavaFX

Persistence, refresh and slow PDF work belong off the JavaFX application thread.

Asynchronous work requires stale-request/lifecycle protection.

Sprint 13 #87 moved Dashboard Exam lifecycle persistence plus authoritative Dashboard reload off the JavaFX thread. A compact busy indicator remains active across the complete operation, duplicate lifecycle actions are suppressed, and busy state clears on both success and failure without changing readiness or lifecycle semantics. Sprint 24 applies background-operation, workflow ownership and generation checks to package exchange. Subject Snapshot export progress presentation remains a known improvement (#305).

### 3.16 Search and Dashboard remain separate concerns

Search answers: “Which Questions do I want to inspect or use?”

Dashboard answers: “What is incomplete/problematic and where do I fix it?”

Sprint 13 #92 added Exam-level and booklet-level `Inspect Questions` actions that open the existing Search workflow with immutable visible narrowing. The narrowing remains active across Search modes and refreshes, so Dashboard can provide structural context without duplicating Search retrieval logic.

Richer retrieval filters (#94) and direct Dashboard descriptor assignment (#99) were selected in Sprint 15; consult the historical Sprint 15 design and current implementation rather than treating them as new backlog requirements. Use shared classification/persistence behaviour rather than a parallel Dashboard-only editor.

### 3.17 Performance work follows behaviour

Broad Search benchmarking (#27) was paired with #94 rather than performed against an incomplete workload. Establish representative workload, then measure query plans, reconstruction cost and ordering before considering #25/#26, indexes or query redesign.

The Sprint 13 UI-test memory investigation followed the same principle: evidence showed one monolithic JavaFX/TestFX JVM accumulated enough state to exhaust the heap. Four bounded headless suite JVMs were introduced at that checkpoint. The suite structure has since expanded; Sprint 24 CI runs 11 non-UI and 10 UI shards plus the non-UI aggregate check. Consult actual CI and Maven configuration rather than relying on historical counts. See [performance benchmarking](performance-benchmarking.md) and [runtime diagnostics](performance-diagnostics.md).

### 3.18 Version and Windows release identity

Maven `project.version` remains authoritative for application/release version, packaged application metadata, About/Version Information, backup metadata and Windows package metadata.

Release 0.1 established the self-contained Java 25/jpackage per-user MSI architecture. Release 0.2 retained it. Release 1.0 was the first completed major release; v1.0.1 is the most recent published release at this checkpoint. New releases use three numeric components (`major.minor.patch`); historical two-component releases retain their original identity. See [release-build.md](release-build.md). Do not build unverified candidates against live production data.

### 3.19 Restart after data-root change or restore

Changing the configured data root or publishing a successful restore invalidates important in-memory application state.

Sprint 13 #98 introduced one shared packaged-application restart path integrated with the existing shutdown/resource-close coordination:

- Options prompts `Restart Now` / `Exit` after a changed data root is saved;
- successful restore can use the same restart capability only after restored data has been published and validated;
- normal shutdown/resource-close safety remains authoritative;
- unsupported development/Eclipse execution fails safely rather than guessing an IDE relaunch command.

The packaged launcher path was manually verified with both a jpackage application image and the installed MSI for data-root change and database restore. An existing alternate properties file may also be selected at launch via the JVM property `eqb.config.file`, useful for isolated exchange testing.

### 3.20 One-way offline exchange and Subject authority

Sprint 24 implements the first shared-faculty operating model. The one standard application uses independent local data roots and typed `.examwork` files rather than accounts, a server, shared SQLite or two-way synchronisation.

- Locally authoritative Subjects are editable, subject to Exam assignment locks. A coordinator issues one whole-Exam Work Assignment and accepts one final Contribution for that exact outstanding assignment or explicitly cancels it. Late, duplicate or foreign returns do not mutate the authoritative corpus.
- Contributors capture new assigned work and correct their own unsent additions; issued baseline content/structure remains protected. A successful final export closes the assignment locally and preserves the original archive for resend. The current implementation retains submitted working data; #301 will simplify this rather than extend worker-side historical reconciliation.
- HoD installations can import multiple independent read-only Subjects and approve subsequent snapshots from the correct originating authority. Initial and refresh imports preserve portable identity, provenance, accepted revision/freshness evidence, validation, automatic full backups and transactional managed-asset recovery.
- Read-only protection is enforced beyond the UI. The final P1 audit correction resolves ownership from persisted records and guards ordinary Exam, capture, curriculum, asset, legacy-import, issuance and snapshot-export paths. Approved Subject import/refresh remains the authorised replacement path.

Current fail-closed limits and accepted usability/scope findings are documented in [offline-exchange.md](offline-exchange.md) and [#301–#309](design/backlog.md). Do not conflate logical contributor closure with physical data disposal, or claim that all subsequent assignments can replace any retained corpus. Keep production data upgrade and recovery compatibility.

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

Release 0.1 merged through PR #39 on 27 September 2026 as `1ccbb350a693568231bd14582ffbecc7684fb79a`.

### Sprint 12 — Exam Intake, Capture Workflow and Corpus Dashboard

Sprint 12 completed authoritative Exam/Assets, planning counts, source replacement/deletion safety, streamlined capture, MCQ explanation requirements and the operational Corpus Dashboard as application home.

It merged through PR #88 on 5 October 2026 as `8ba714e2ef7ff07eebb2375021e1e751d4876e47` and produced Release 0.2 after the automated release gate and installed-MSI verification.

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

The sprint also replaced the locally problematic monolithic headless UI regression with four bounded suite JVMs while retaining complete UI coverage. The split closeout run passed 360 tests with zero failures/errors; strict Javadoc and PR CI were green.

Sprint 13 merged through PR #107 on 7 October 2026. Merge commit: `f497f1844c1eebf47f25ebe9e49454b965459f61`.

See `docs/design/sprint-13-ui-refinement.md`.

### Sprint 14 — Subject-First Managed Data Layout

Sprint 14 was tracked under umbrella issue #100.

The filesystem contract makes Subject the primary managed-data partition beneath `data.root`, centralises path construction and standardises new managed paths as data-root-relative.

Child issues:

- #101 — Subject-first managed data layout contract;
- #102 — move Exam and Answer PDFs;
- #103 — move curriculum assets;
- #104 — retain legacy import workbooks as managed Subject assets;
- #105 — migrate existing data roots safely;
- #106 — update backup/restore for the Subject-first layout.

SQLite remains authoritative. The Subject directory is a portable managed-source domain, not a second database. See `docs/design/sprint-14.md`.

### Sprint 15 and subsequent development before Sprint 24

The Sprint 15 selected scope was #99, #94 and #27, with performance decomposition under #109–#111; see `docs/design/sprint-15.md`. Release history subsequently reached 1.0 (1 November 2026 maintainer confirmation) and v1.0.1 (published 4 November 2026 UTC). Intervening individual sprint changes and verification records remain in the GitHub issues, pull requests and release history. This reconciliation does not reconstruct undocumented sprint-by-sprint narratives or invent merge evidence.

### Sprint 24 — Offline collection and faculty consolidation

Parent #36 and exchange infrastructure #268–#275 culminated in #276. The approved operating model replaces routine receipts, repeated partial returns and arbitrary same-Subject reconciliation with single final assignment Contributions and explicitly approved authoritative Subject Snapshot refresh.

At the audited checkpoint, the three main workflows have passed consolidated manual acceptance with 17/18 reported passes and accepted known limitations. The imported-Subject authority P1 correction was verified by all 22 CI checks and clean Javadoc. No schema migration was needed for that correction. Remaining D21 and D23–D31 work is deferred to #301–#309.

See [Sprint 24 record](design/sprint-24-offline-exchange.md) for exact checkpoint commits, audit evidence and acceptance. Final PR/merge and new release publication were pending on 26 November 2026.

## 5. Forward direction

Complete the verified Sprint 24 candidate's documentation, protected-branch integration and formal release gate, then deploy to other Subject departments. Do not expand release scope to implement deferred limitations.

Beyond this release, priorities remain deliberately issue-driven:

- Simplify contributor lifecycle and subsequent assignment installation (#301), with #303 and #309 coordinated where necessary. Prefer removing unnecessary worker reconciliation to adding further complexity.
- Address measured Dashboard latency and remaining accepted UI/workflow improvements (#302, #304–#308) when selected.
- Consider safe whole-Subject deletion/reimport (#300), consolidated baseline schema for new installations (#297), and trusted Windows signing (#217) according to project priorities.
- Printable/vector-preserving assessment and solution output (#38) and Exam Builder (#37) remain future product areas.
- Capture productivity assistance (#166–#169) and curriculum assistance (#17/#18) remain evidence-driven rather than automatically promoted.

See the [backlog](design/backlog.md) and current GitHub Project for selected work. No new sprint assignment or priority is implied by this document.

## 6. Development discipline

1. Inspect the current repository and current GitHub issue before exact implementation work.
2. Design substantial changes before coding; confirm the minimum necessary operating model.
3. Work in behaviour-focused slices. Use issue-based branches and protected-branch PRs.
4. Prefer integration/UI regressions that reproduce real production failure modes.
5. Maintain public API Javadoc and meaningful algorithmic comments.
6. Keep slow work off the JavaFX thread and protect asynchronous results from stale generations.
7. Use deterministic TestFX interactions.
8. Use bounded test-process structure when JavaFX/PDF state makes one monolithic JVM unreliable.
9. Require green CI before protected-main merge; distinguish source audit, executed tests, accepted limitations and release publication.
10. Keep sprint documents as historical evidence after closeout.
11. Keep genuinely deferred work in `docs/design/backlog.md` and its GitHub issues.
12. Use `scripts/build-release.ps1` as the release gate rather than ad hoc packaging.
