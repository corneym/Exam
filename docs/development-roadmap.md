# Exam Question Bank --- Development Roadmap

> **Reference date:** 28 September 2026\
> **Version:** 20\
> **Repository location:** `docs/development-roadmap.md`
>
> This is the canonical durable project record: project history,
> architectural evolution and forward roadmap. Detailed completed-sprint
> evidence remains in `docs/design/sprint-*.md`.

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that preserves
authoritative examination content, historical curriculum provenance and reusable
marking material, then turns that bank into useful resources for students and
teachers.

Chemistry remains the first full development dataset. Core model, repository,
service, persistence and output logic remain subject-neutral.

Long-term purposes are to maintain a durable curriculum-aware bank of Questions
and marking material, generate student revision resources, assemble teacher
assessments and printable marking resources, and support practical deployment
and later assisted workflows.

## 2. Current strategic path

``` text
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
Sprint 12 — Exam intake, capture workflow, MCQ explanations
            and Corpus Dashboard
            target Release 0.2
        ↓
Real-corpus use and evidence-driven refinement
        ↓
Exam Builder + printable output when prioritised
```

Sprint 10 merged to `main` through pull request #2. Merge commit:
`a3dfda7e`. Post-merge GitHub Actions run 64 was successful.

Sprint 11 merged to protected `main` through pull request #39 on
27 September 2026. Merge commit:
`1ccbb350a693568231bd14582ffbecc7684fb79a`.

Final feature-branch GitHub Actions CI run 81 completed successfully.

Sprint 12 design is recorded in `docs/design/sprint-12-release-0.2.md`.
The implementation branch is `feature/sprint-12`.

Git/GitHub remains authoritative for moving branch heads, pull-request state and
CI state.

## 3. Architectural evolution and current constraints

### 3.1 Workbook/filesystem database -> SQLite question bank

The predecessor application used an Excel workbook plus named image snips as its
de facto database. The redevelopment retained useful publishing goals but
replaced workbook/file naming as runtime authority.

SQLite is the live datastore. Excel is import/exchange only. Sequential
migrations, referential integrity and reconstruction tests protect persisted
state.

The latest supported schema version is **14**.

### 3.2 Authoritative Question content: PDF regions plus clipboard images

The principal source model began as:

``` text
managed PDF + owning document/booklet + page + ordered normalised rectangle(s)
```

Sprint 11 extended Question bodies so authoritative content can be an ordered
mixture of:

``` text
PDF_REGION
IMAGE
```

PDF regions retain their managed-document/page/normalised-rectangle provenance.
Clipboard images are stored as authoritative PNG BLOBs in SQLite. An ordered
content-parts table defines the assembly order across both forms.

This preserves the existing PDF model while allowing Windows Snipping Tool and
other clipboard images to become first-class Question content. Generated
preview/revision images remain derived output.

### 3.3 Machine-specific paths -> managed portable data root

One configured `data.root` derives managed PDF, curriculum and SQLite locations.
Persisted source-document paths are portable relative paths beneath that root.

Exam provider/year correction relocates managed PDFs and updates persisted paths
transactionally.

Sprint 11 separated application installation from mutable configuration/data.
On Windows the user-specific configuration is:

``` text
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties
```

and a first-run default data root is:

``` text
%LOCALAPPDATA%\Exam Question Bank Data\data
```

The writable directory is deliberately a sibling of the jpackage installation
directory so MSI uninstall cannot remove user-owned configuration/data.

A legacy working-directory `questionbank.properties` can be migrated on first
use when no user-specific configuration yet exists.

### 3.4 Pixel coordinates -> normalised source coordinates

Question, Answer and Shared Context PDF regions use normalised proportional
coordinates and one-based domain page numbers. Rendering DPI is an output
concern rather than persisted content semantics.

### 3.5 In-memory prototypes -> SQLite repositories/services

Early in-memory repositories proved vertical slices. Runtime persistence is now
SQLite. In-memory repositories remain focused test tools.

Sprint 11 added composed integration regressions across persisted
mapping/exclusion -> corpus -> HTML/SCORM and legacy backup -> restore ->
sequential migration -> reopen boundaries.

### 3.6 Historical classification overwrite -> provenance plus derived applicability

Historical classification is preserved rather than rewritten to the current
syllabus. Confirmed historical -> current mappings derive current applicability.
Mapping may be one-to-many and remains teacher-reviewed.

A Question currently stores one best-fit original Subtopic or Descriptor
classification.

### 3.7 SourceQuestion and SharedQuestionContext are distinct

`SourceQuestion` represents original multipart identity.
`SharedQuestionContext` represents reusable source material.

Shared Context does not imply multipart identity. Independent MCQs may share
context through sequence-aware booklet-scoped continuation without acquiring
common `SourceQuestion` identity.

**Design decision:** multi-page Shared Context capture will not be supported. A
Shared Context is contained within one source page. This is a rejected direction,
not backlog work.

### 3.8 Response type belongs to Question

Mixed-response booklets are supported. Runtime Answer behaviour does not depend
on booklet-name inference.

``` text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> response type unresolved; ordinary Answer capture blocked
```

Booklet format constrains/defaults genuinely new capture but does not rewrite
existing Question response type.

### 3.9 Exam-wide Answer PDF -> booklet-specific AnswerFile

Each `ExamBooklet` may reference one `AnswerFile`; one `AnswerFile` may serve
several booklets. Null represents unresolved/not-yet-selected ownership.

This superseded the earlier exam-wide heuristic.

### 3.10 Mapping-only output applicability -> Question-specific exception layer

A Question normally appears at every current placement derived from
classification/mapping. Schema v12 stores explicit Question/current-node
exclusions.

An exclusion suppresses only that placement. It does not rewrite historical
classification or curriculum mapping.

### 3.11 Preamble terminology -> Shared Context

Schema v13 aligned live physical database names with the Shared Context
terminology already used by domain and UI code. Historical migrations and
external legacy vocabulary retain historical names where required.

### 3.12 Search filters and selected-Question editing are separate concerns

Search filters determine why a Question matched. Selected-Question
classification and revision-output applicability describe/edit the stored
Question.

Sprint 10 separated those responsibilities. Sprint 11 redesigned Search into a
more compact two-column layout without changing retrieval semantics.

Richer retrieval-oriented Search filtering is deliberately deferred beyond
Sprint 12 and is tracked in `docs/design/backlog.md`. Operational completeness
and correction filtering belongs in Corpus Dashboard rather than being mixed
into Search.

### 3.13 Presentation choices do not rewrite curriculum data

Revision grouping, response ordering, selected Units, page numbering, generated
timestamp and Question-level output exclusions are output/presentation concerns,
not persisted curriculum semantics.

### 3.14 Long-running UI work must not block JavaFX

Persistence, refresh and slow PDF work belong off the JavaFX application thread.
Asynchronous work requires stale-request and lifecycle protection.

TestFX semantic interactions use real JavaFX control actions rather than pointer
hit-testing unless pointer behaviour itself is under test. Modal-dialog tests use
actual showing windows/DialogPanes to avoid scene-graph races.

Sprint 12 applied this rule to application-level Working Subject changes.
Accepted Subject transitions now use an application-owned asynchronous refresh
generation covering curriculum, Question/Answer capture and visible Exam/Assets
state. Question and Answer capture share one loaded Question snapshot, curriculum
Subject data is preloaded before JavaFX publication, and stale results from an
older Subject generation are discarded. Subject-change safety guards remain
synchronous so unfinished capture work cannot be invalidated by a background
transition.

### 3.15 Version and release identity

Maven `project.version` is the authoritative application/release version.

Maven filters that value into packaged `application.properties`.
`ApplicationVersion.current()` supplies the value used by About, Version
Information and backup metadata. Packaging scripts read the same Maven version
for application-image and MSI metadata.

Release versions use a validated `major.minor` form. The normal release command
automatically advances the minor version; an explicit `-Version` selects a
specific version or major-version transition. A failed release restores the POM
when the release script changed it.

Sprint 12 targets Release **0.2** because it is expected to make substantial
user-facing workflow changes. That target remains provisional until the normal
release gate is run at sprint closeout.

### 3.16 Self-contained Windows deployment

Sprint 11 established a repeatable Windows packaging architecture based on Java
25 `jpackage`.

The application image is packaged as a class-path application with collected
runtime dependencies and a private stripped Java runtime. The final Windows MSI
is per-user, adds a Start-menu entry and uses a fixed upgrade UUID that must
remain stable across future releases.

Release creation is gated by formatting, full non-UI tests, full headless UI
tests, strict Javadoc and packaging. The release procedure is documented in
`docs/release-build.md`.

## 4. Development history

### Foundation before numbered sprints

August 2026 redevelopment established Java/JavaFX/Maven, Git/GitHub workflow,
curriculum modelling, PDF viewing/region capture, managed `data.root`, normalised
source rectangles and the move to SQLite.

A representative Chemistry batching exercise established durable requirements:
preserve source formatting, generate independent revision numbering while
retaining provenance, support curriculum remapping, preserve Question/Answer
links, and allow deliberate handling of source material that does not fit
current output needs.

### Sprint 01 --- Legacy Metadata Import

Legacy workbook import reconstructed Exam/Booklet metadata, Questions,
historical classification and reliable MCQ answer letters. Managed source
documents were registered and import became conflict-aware/idempotent. Import
deliberately avoided inventing multipart or Shared Context relationships.

### Sprint 02 --- Directional Curriculum Applicability

Historical -> current mapping direction became explicit.
Descriptor/Subtopic same-level mapping, one-to-many relationships and
reviewed-state semantics were implemented. Only confirmed mappings affect
applicability.

### Sprint 03 --- Question Retrieval

Current-curriculum retrieval was implemented across Subject, Unit, Topic,
Subtopic and Descriptor scopes. Direct-current and confirmed-mapped historical
Questions are returned while original provenance is retained. Search/preview
became asynchronous with stale/lifecycle protection.

### Sprint 04 --- Backup, Restore and Data Safety

Versioned backup archives, SQLite-consistent snapshots, automatic/manual backup,
bounded retention, validated restore, pre-restore safety backup, rollback and
migration compatibility checks became first-class features.

### Sprint 05 --- Hierarchical Revision Corpus and Static HTML Export

A deterministic current-curriculum revision corpus and static student website
were implemented with rendered Question/Answer assets, hierarchical navigation,
generated numbering/marks, answer disclosure and provenance.

### Sprint 06 --- SCORM 1.2 and QLearn validation

The revision site became the basis for deterministic SCORM 1.2 single-SCO
packaging. Manifest/package validation was implemented and a real Chemistry
package successfully imported and launched in QLearn.

### Sprint 07 --- Shared-context-aware capture and UI redesign

Persisted `SourceQuestion` identity and `SharedQuestionContext` were separated.
Capture/correction gained Shared Context reuse, syllabus-sensitive
classification, Question/Answer editing, multipart presentation semantics and
asynchronous persistence/PDF transitions.

### Sprint 08 --- Curriculum and Corpus Completion

Subject-neutral curriculum authoring/lifecycle, mapping coverage/review, legacy
metadata correction, persisted Question response type, mixed-response booklet
support, response-type-aware completeness and Corpus Audit were implemented.
Sprint 08 merged 17 September 2026.

### Sprint 09 --- Capture Workflow and Corpus Correction

Sustained real-corpus use drove capture/correction improvements: deterministic
ordering, responsive capture controls, Shared Context recapture, authoritative
Exam correction with managed-file relocation, known-PDF reuse, legacy Question
split, Search scope, Corpus Audit ordering, MCQ Answer-PDF visibility, UI package
refactoring and CI hardening.

Sprint 09 merged to `main` on 20 September 2026 in commit `2533586`. Protected
main workflow was then established and exercised through pull request #1.

### Sprint 10 --- Capture Hardening and Revision Output Refinement

Sprint 10 hardened pending-selection/booklet-transition state, curriculum
selector synchronisation and Working Subject filtering. Schema v9-v13 added
booklet Question format, booklet-specific AnswerFile assignment, restart-safe
independent-MCQ Shared Context continuation, Question-specific revision-output
exclusions and live Shared Context terminology.

Revision HTML/SCORM gained safe Subtopic/Descriptor grouping, response-type
ordering/sections, page-local numbering, empty-branch pruning, selected-Unit
scope, student-facing counts, deterministic generated timestamp and persistent
breadcrumbs.

Search gained classification refinement, stored-region edit navigation,
output-applicability controls, geometry hardening and native-window dirty-close
protection. Public API Javadoc was completed across affected production
boundaries.

Final local validation included the full Maven suite, strict warning-free
Javadoc and `git diff --check`. Sprint 10 merged through pull request #2
(`a3dfda7e`); post-merge GitHub Actions run 64 succeeded.

Detailed evidence remains in `docs/design/sprint-10-capture-output.md`.

### Sprint 11 --- Release 0.1

Sprint 11 added two composed integration regressions, then introduced
authoritative clipboard-image Question content alongside existing PDF-region
content. Schema v14 persists PNG image BLOBs and authoritative mixed content
order.

Question Search was redesigned into a more compact two-column layout without
changing retrieval/edit semantics. Purpose/consequence tooltips were added
across application surfaces.

A packaged HTML/CSS Help system was added through JavaFX WebView, including
Getting Started, Question Capture, Search, Curriculum, Revision Output and Data
Safety. Help also exposes About and Version Information.

Maven project version became the single release identity. Version 0.1 is embedded
into the application through a filtered resource and reused by application
metadata.

Windows deployment was established with a self-contained jpackage app image and
per-user MSI. Mutable user configuration/data was moved to a sibling
`%LOCALAPPDATA%\Exam Question Bank Data` directory after uninstall testing
exposed that installer-owned directories must not contain user state.

A top-level release script now validates/increments release versions and gates
packaging on Spotless, the full non-UI suite, the full headless UI suite and
strict Javadoc. The generated 0.1 MSI passed install, Start-menu launch, Help,
About/version, existing-data and uninstall/configuration-survival checks outside
Eclipse.

Detailed evidence remains in `docs/design/sprint-11-release-0.1.md`.

Sprint 11 merged to protected `main` through pull request #39 after final
feature-branch GitHub Actions CI run 81 completed successfully. Merge commit:
`1ccbb350a693568231bd14582ffbecc7684fb79a`.

## 5. Sprint 12 --- Exam intake, capture workflow and Corpus Dashboard

Sprint 12 is the active designed sprint on branch `feature/sprint-12` and
targets Release 0.2.

Its design is deliberately organised into four dependent slices rather than an
uncontrolled collection of UI changes.

### Slice 1 --- Exam setup, assets, hashes and safe correction

Question capture begins from an explicitly selected/configured Exam and Question
booklet.

The Exam-level setup workflow is designed to:

- select or create an Exam;
- manage all Question booklets and Answer/marking assets in one place;
- display a selected Question booklet PDF in inspection-only mode while the user
  records its expected top-level Question count;
- preserve booklet format metadata;
- persist managed source-document hashes for duplicate detection and future
  reconciliation;
- assign AnswerFiles to Question booklets;
- record whether an AnswerFile contains optional MCQ explanation material;
- replace incorrectly selected Question/Answer assets without silently remapping
  old region coordinates;
- let the user declare an Exam `COMPLETE` and explicitly reactivate it later.

Expected Question count refers to top-level numbered Questions. Multipart parts
such as `21a`, `21b` and `21c` collectively represent source Question 21 for
counting purposes.

Exam completeness is user-declared. Audit findings remain advisory and do not
automatically alter the declared state.

A completed Exam locks structural change while ordinary content correction and
enrichment remain available.

### Slice 2 --- Streamlined Question capture

After Slice 1 establishes the active Exam/booklet, capture is simplified around
the real sustained-use workflow.

Planned behaviour includes:

- workspace-level Working Subject;
- clear active Exam/booklet context;
- one bounded capture workspace containing Classification, Question and Answer;
- explicit `Start New Question Capture`;
- sequential new-Question capture after save;
- only `Add Region` and `Add From Clipboard` as Question-content creation
  actions;
- existing click-away cancellation for an unaccepted PDF selection;
- individual removal/reordering of accepted Question content;
- existing `21a`, `21b`, etc. multipart detection;
- reuse of existing SourceQuestion and Shared Context semantics;
- imported/incomplete Question controls only when relevant work exists.

### Slice 3 --- MCQ explanation regions

Slice 3 is implemented.

Optional marking-PDF explanation material can be attached to MCQ Answers while
the stored A/B/C/D choice remains authoritative and sufficient for Answer
completeness.

AnswerFiles persist whether they contain MCQ explanation material. Ordinary MCQ
Answer capture exposes optional region capture only for a flagged AnswerFile.

An explicit `Capture MCQ Explanations` workflow permits already-answered MCQs to
be enriched later. The workflow is scoped to the active Question booklet
selected through Exam/Assets, reuses the existing Answer-edit persistence path,
and preserves the stored A-D choice and Answer identity.

After a successful retrofit update the completed candidate is removed from the
current session and the next candidate opens automatically. Cancelling leaves
the candidate available.

Explanation coverage remains separate from ordinary Answer completeness. The
AnswerFile capability flag does not imply that every MCQ requires an explanation.

### Slice 4 --- Corpus Dashboard / Audit refinement

The existing Corpus Audit evolves into an Exam -> booklet -> Question operational
Dashboard.

The Dashboard distinguishes:

``` text
declared Exam state
    ACTIVE / COMPLETE

calculated audit findings
    clear / work requiring attention
```

It uses Slice 1 expected-count and asset metadata while retaining useful
Question-level audit semantics.

Expected Question counts are compared with top-level/source Questions, not
multipart Question-row count.

The current PDF-region-only Question-source audit must be corrected so
clipboard/image-only Questions count as having authoritative Question content.

The operational Corpus Dashboard is now implemented with Exam/booklet/Question
scope, completeness summaries, expected-versus-present asset reporting, explicit
Question and Answer capture actions, response-type correction and direct
Exam/Assets routing.

Issue #61 adds the correction-routing circuit around that Dashboard. Dashboard
actions now enter the existing authoritative Question capture, Answer capture and
Exam/Assets workflows and return through refreshed persistence state. Manage
Exam / Assets is available for any selected Exam and preserves an explicitly
selected booklet. The latest routing, responsive-workspace and Subject-change
safeguards have passed manual acceptance and feature-branch CI.

Issue #65 owns the next application-shell step: make the Corpus Dashboard the
default home screen, move authoritative Subject selection into that surface and
provide useful empty-Subject actions such as Add Exam and Add Curriculum.

Curriculum mapping review coverage remains a separate reporting dimension.

Detailed working design and implementation status are maintained in
`docs/design/sprint-12-release-0.2.md`.

### Deferred from Sprint 12

Richer retrieval-oriented Question Search filtering has returned to
`docs/design/backlog.md`.

The Dashboard owns operational completeness/correction filtering; Search remains
a retrieval/edit surface.

## 6. Later product direction

Exam Builder and printable assessment/solution output remain later product areas.
Their priority should be determined by real use after Release 0.2 and
corpus-management work.

Exam Builder should consume the bank rather than become a dependency of
revision/SCORM output.

Print-oriented output may later require vector-preserving clipping from
authoritative source PDFs.

Portable offline collection packages remain a planned future capability rather
than Sprint 12 scope.

## 7. Development discipline

For self-contained changes:

1. inspect the current repository before implementation instructions;
2. design substantial changes before coding;
3. work in small behaviour-focused slices;
4. add integration tests where real boundaries justify them;
5. add UI regressions for actual behaviour failures;
6. maintain public API Javadoc and algorithmic comments;
7. run focused tests during implementation and broader suites at checkpoints;
8. keep slow work off the JavaFX thread;
9. protect asynchronous UI work from stale/lifecycle races;
10. keep feature branches coherent;
11. require green CI before protected-main merge;
12. keep the active sprint document current during the sprint;
13. on sprint closeout, fold durable history/architecture changes into this
    roadmap and move active status to the next sprint document;
14. keep deliberately deferred work in `docs/design/backlog.md`;
15. use `scripts/build-release.ps1` as the release gate rather than assembling a
    release through ad hoc commands.

## 8. Documentation model

Canonical documentation is deliberately small:

- `development-roadmap.md` --- durable project history, architecture evolution
  and forward roadmap;
- `design/sprint-*.md` --- detailed sprint design, working status while active,
  and immutable final-state evidence after closeout;
- `design/backlog.md` --- deliberately deferred/unresolved work;
- `release-build.md` --- repeatable Windows release/package procedure;
- `Working-Instructions.md` --- working rules for repository-guided development.

`project-history.md`, `design/architecture-evolution.md` and `current-status.md`
are retired after their durable content is consolidated here. The active sprint
document carries current status; at closeout, durable changes are folded into
this roadmap and the next sprint document becomes the active status record.
