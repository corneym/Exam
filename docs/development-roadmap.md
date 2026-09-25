# Exam Question Bank --- Development Roadmap

> **Reference date:** 26 September 2026\
> **Version:** 18\
> **Repository location:** `docs/development-roadmap.md`
>
> This is the canonical durable project record: project history,
> architectural evolution and forward roadmap. Detailed completed-sprint
> evidence remains in `docs/design/sprint-*.md`.

## 1. Project goal

Build a desktop Exam Question Bank for school science subjects that
preserves authoritative examination content, historical curriculum
provenance and reusable marking material, then turns that bank into
useful resources for students and teachers.

Chemistry remains the first full development dataset. Core model,
repository, service, persistence and output logic remain
subject-neutral.

Long-term purposes are to maintain a durable curriculum-aware bank of
Questions and marking material, generate student revision resources,
assemble teacher assessments and printable marking resources, and
support practical deployment and later assisted workflows.

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
Sprint 11 — Release 0.1
        ↓
Sprint 12 — Corpus Dashboard + richer Question filtering
        ↓
Real-corpus use and evidence-driven refinement
        ↓
Exam Builder + printable output when prioritised
```

Sprint 10 merged to `main` through pull request #2. Merge commit:
`a3dfda7e`. Post-merge GitHub Actions run 64 was successful.

Sprint 11 development branch: `feature/sprint-11-release-0.1`.

Git/GitHub remains authoritative for moving branch heads and CI state.

## 3. Architectural evolution and current constraints

### 3.1 Workbook/filesystem database -\> SQLite question bank

The predecessor application used an Excel workbook plus named image
snips as its de facto database. The redevelopment retained useful
publishing goals but replaced workbook/file naming as runtime authority.

SQLite is the live datastore. Excel is import/exchange only. Sequential
migrations, referential integrity and reconstruction tests protect
persisted state.

The latest supported schema version at Sprint 11 start is 13.

### 3.2 Image snips -\> authoritative PDFs plus normalised regions

The principal source model became:

``` text
managed PDF + owning document/booklet + page + ordered normalised rectangle(s)
```

Generated images are derived assets rather than authoritative Question
content. This preserves source fidelity and keeps future vector-oriented
print output possible.

Sprint 11 deliberately extends this model for Questions whose
authoritative source is an image obtained from the clipboard. It does
not replace PDF-region provenance for ordinary PDF Questions.

### 3.3 Machine-specific paths -\> managed portable data root

One configured `data.root` derives managed PDF, curriculum and SQLite
locations. Persisted source-document paths are portable relative paths
beneath that root.

Exam provider/year correction relocates managed PDFs and updates
persisted paths transactionally.

### 3.4 Pixel coordinates -\> normalised source coordinates

Question, Answer and Shared Context PDF regions use normalised
proportional coordinates and one-based domain page numbers. Rendering
DPI is an output concern rather than persisted content semantics.

### 3.5 In-memory prototypes -\> SQLite repositories/services

Early in-memory repositories proved vertical slices. Runtime persistence
is now SQLite. In-memory repositories remain focused test tools.

Sprint 11.1 adds composed integration regressions specifically to
protect seams that unit/component tests already cover separately.

### 3.6 Historical classification overwrite -\> provenance plus derived applicability

Historical classification is preserved rather than rewritten to the
current syllabus. Confirmed historical -\> current mappings derive
current applicability. Mapping may be one-to-many and remains
teacher-reviewed.

A Question currently stores one best-fit original Subtopic or Descriptor
classification.

### 3.7 SourceQuestion and SharedQuestionContext are distinct

`SourceQuestion` represents original multipart identity.
`SharedQuestionContext` represents reusable source material.

Shared Context does not imply multipart identity. Independent MCQs may
share context through sequence-aware booklet-scoped continuation without
acquiring common `SourceQuestion` identity.

**Design decision:** multi-page Shared Context capture will not be
supported. A Shared Context is contained within one source page. This is
a rejected direction, not backlog work.

### 3.8 Response type belongs to Question

Mixed-response booklets are supported. Runtime Answer behaviour does not
depend on booklet-name inference.

``` text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> response type unresolved; ordinary Answer capture blocked
```

Booklet format constrains/defaults genuinely new capture but does not
rewrite existing Question response type.

### 3.9 Exam-wide Answer PDF -\> booklet-specific AnswerFile

Each `ExamBooklet` may reference one `AnswerFile`; one `AnswerFile` may
serve several booklets. Null represents unresolved/not-yet-selected
ownership.

This superseded the earlier exam-wide heuristic.

### 3.10 Mapping-only output applicability -\> Question-specific exception layer

A Question normally appears at every current placement derived from
classification/mapping. Schema v12 stores explicit Question/current-node
exclusions.

An exclusion suppresses only that placement. It does not rewrite
historical classification or curriculum mapping.

### 3.11 Preamble terminology -\> Shared Context

Schema v13 aligned live physical database names with the Shared Context
terminology already used by domain and UI code. Historical migrations
and external legacy vocabulary retain historical names where required.

### 3.12 Search filters and selected-Question editing are separate concerns

Search filters determine why a Question matched. Selected-Question
classification and revision-output applicability describe/edit the
stored Question.

Sprint 10 separated those responsibilities. Sprint 11 redesigns the
Search layout without adding richer filtering; richer bank-management
filters are reserved for Sprint 12.

### 3.13 Presentation choices do not rewrite curriculum data

Revision grouping, response ordering, selected Units, page numbering,
generated timestamp and Question-level output exclusions are
output/presentation concerns, not persisted curriculum semantics.

### 3.14 Long-running UI work must not block JavaFX

Persistence, refresh and slow PDF work belong off the JavaFX application
thread. Asynchronous work requires stale-request and lifecycle
protection.

### 3.15 Version and release identity

Sprint 11 establishes one authoritative application version source.
Help/About and release artefacts must derive from that source rather
than maintain independent hard-coded versions.

## 4. Development history

### Foundation before numbered sprints

August 2026 redevelopment established Java/JavaFX/Maven, Git/GitHub
workflow, curriculum modelling, PDF viewing/region capture, managed
`data.root`, normalised source rectangles and the move to SQLite.

A representative Chemistry batching exercise established durable
requirements: preserve source formatting, generate independent revision
numbering while retaining provenance, support curriculum remapping,
preserve Question/Answer links, and allow deliberate handling of source
material that does not fit current output needs.

### Sprint 01 --- Legacy Metadata Import

Legacy workbook import reconstructed Exam/Booklet metadata, Questions,
historical classification and reliable MCQ answer letters. Managed
source documents were registered and import became
conflict-aware/idempotent. Import deliberately avoided inventing
multipart or Shared Context relationships.

### Sprint 02 --- Directional Curriculum Applicability

Historical -\> current mapping direction became explicit.
Descriptor/Subtopic same-level mapping, one-to-many relationships and
reviewed-state semantics were implemented. Only confirmed mappings
affect applicability.

### Sprint 03 --- Question Retrieval

Current-curriculum retrieval was implemented across Subject, Unit,
Topic, Subtopic and Descriptor scopes. Direct-current and
confirmed-mapped historical Questions are returned while original
provenance is retained. Search/preview became asynchronous with
stale/lifecycle protection.

### Sprint 04 --- Backup, Restore and Data Safety

Versioned backup archives, SQLite-consistent snapshots, automatic/manual
backup, bounded retention, validated restore, pre-restore safety backup,
rollback and migration compatibility checks became first-class features.

### Sprint 05 --- Hierarchical Revision Corpus and Static HTML Export

A deterministic current-curriculum revision corpus and static student
website were implemented with rendered Question/Answer assets,
hierarchical navigation, generated numbering/marks, answer disclosure
and provenance.

### Sprint 06 --- SCORM 1.2 and QLearn validation

The revision site became the basis for deterministic SCORM 1.2
single-SCO packaging. Manifest/package validation was implemented and a
real Chemistry package successfully imported and launched in QLearn.

### Sprint 07 --- Shared-context-aware capture and UI redesign

Persisted `SourceQuestion` identity and `SharedQuestionContext` were
separated. Capture/correction gained Shared Context reuse,
syllabus-sensitive classification, Question/Answer editing, multipart
presentation semantics and asynchronous persistence/PDF transitions.

### Sprint 08 --- Curriculum and Corpus Completion

Subject-neutral curriculum authoring/lifecycle, mapping coverage/review,
legacy metadata correction, persisted Question response type,
mixed-response booklet support, response-type-aware completeness and
Corpus Audit were implemented. Sprint 08 merged 17 September 2026.

### Sprint 09 --- Capture Workflow and Corpus Correction

Sustained real-corpus use drove capture/correction improvements:
deterministic ordering, responsive capture controls, Shared Context
recapture, authoritative Exam correction with managed-file relocation,
known-PDF reuse, legacy Question split, Search scope, Corpus Audit
ordering, MCQ Answer-PDF visibility, UI package refactoring and CI
hardening.

Sprint 09 merged to `main` on 20 September 2026 in commit `2533586`.
Protected-main workflow was then established and exercised through pull
request #1.

### Sprint 10 --- Capture Hardening and Revision Output Refinement

Sprint 10 hardened pending-selection/booklet-transition state,
curriculum selector synchronisation and Working Subject filtering.
Schema v9-v13 added booklet Question format, booklet-specific AnswerFile
assignment, restart-safe independent-MCQ Shared Context continuation,
Question-specific revision-output exclusions and live Shared Context
terminology.

Revision HTML/SCORM gained safe Subtopic/Descriptor grouping,
response-type ordering/sections, page-local numbering, empty-branch
pruning, selected-Unit scope, student-facing counts, deterministic
generated timestamp and persistent breadcrumbs.

Search gained classification refinement, stored-region edit navigation,
output-applicability controls, geometry hardening and native-window
dirty-close protection. Public API Javadoc was completed across affected
production boundaries.

Final local validation included the full Maven suite, strict
warning-free Javadoc and `git diff --check`. Sprint 10 merged through
pull request #2 (`a3dfda7e`); post-merge GitHub Actions run 64
succeeded.

Detailed evidence remains in `docs/design/sprint-10-capture-output.md`.

## 5. Sprint 11 --- Release 0.1

Sprint 11 is the active sprint. Its canonical design and working status
record is:

`docs/design/sprint-11-release-0.1.md`

Planned sequence:

1.  11.1 composed integration regressions;
2.  11.2 clipboard/Snipping Tool Question capture;
3.  11.3 Question Search Dialog redesign;
4.  11.4 application tooltips;
5.  11.5 documented Help system;
6.  11.6 Help -\> About and authoritative version infrastructure;
7.  11.7 deployment/package build;
8.  11.8 release 0.1.

The sprint endpoint is a tested installable 0.1 release.

## 6. Sprint 12 --- Corpus Dashboard and richer Question filtering

Sprint 12 is reserved for bank-management work rather than being allowed
to expand Sprint 11.

### Corpus Audit -\> Corpus Dashboard

Redesign the existing Corpus Audit into an operational Corpus Dashboard.
It should answer:

``` text
What work remains?
Why does it need attention?
Take me to the existing workflow that can resolve it.
```

The Dashboard should reuse existing audit/domain truth rather than
invent parallel completeness rules. It is intended as an operational
work queue, not a graph-heavy reporting dashboard.

Detailed scope will be designed against the then-current code before
implementation.

### Richer Question filtering

Extend Question Search as the principal bank-management/search surface
with practical filters justified by real corpus use. Candidate
dimensions include exam/provider/year, response type, source/Answer
presence, Shared Context state, current applicability and
revision-output exclusion state.

The Search Dialog layout redesign occurs in Sprint 11; richer search
semantics remain Sprint 12.

## 7. Later product direction

Exam Builder and printable assessment/solution output remain later
product areas. Their priority should be determined by real use after the
0.1 release and corpus-management work.

Exam Builder should consume the bank rather than become a dependency of
revision/SCORM output.

Print-oriented output may later require vector-preserving clipping from
authoritative source PDFs.

## 8. Development discipline

For self-contained changes:

1.  inspect the current repository before implementation instructions;
2.  design substantial changes before coding;
3.  work in small behaviour-focused slices;
4.  add integration tests where real boundaries justify them;
5.  add UI regressions for actual behaviour failures;
6.  maintain public API Javadoc and algorithmic comments;
7.  run focused tests during implementation and broader suites at
    checkpoints;
8.  keep slow work off the JavaFX thread;
9.  protect asynchronous UI work from stale/lifecycle races;
10. keep feature branches coherent;
11. require green CI before protected-main merge;
12. keep the active sprint document current during the sprint;
13. on sprint closeout, fold durable history/architecture changes into
    this roadmap and move active status to the next sprint document;
14. keep deliberately deferred work in `docs/design/backlog.md`.

## 9. Documentation model

Canonical documentation is deliberately small:

-   `development-roadmap.md` --- durable project history, architecture
    evolution and forward roadmap;
-   `design/sprint-*.md` --- detailed sprint design, working status
    while active, and immutable final-state evidence after closeout;
-   `design/backlog.md` --- deliberately deferred/unresolved work;
-   `Working-Instructions.md` --- working rules for repository-guided
    development.

`project-history.md`, `design/architecture-evolution.md` and
`current-status.md` are retired after their durable content is
consolidated here. The active sprint document carries current status; at
closeout, durable changes are folded into this roadmap and the next
sprint document becomes the active status record.
