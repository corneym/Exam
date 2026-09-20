# Current Status

> **Authoritative status date:** 20 September 2026.  
> Sprints 01 through 09 are complete and merged.  
> Current `main` at Sprint 10 start: `5a1e5a9`.  
> Active branch: `feature/capture-output`.  
> Sprint 10 is designed but its planned features must not be described as
> implemented until repository/test evidence exists.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by
SQLite. It manages authoritative exam and marking PDFs, versioned curriculum
data, legacy metadata import, Question and Answer capture from PDF regions,
historical-to-current curriculum mapping, current-curriculum retrieval,
revision HTML generation, SCORM 1.2 packaging, backup/restore, curriculum
authoring, corpus audit and correction of persisted assessment data.

Sprint 07 established persisted source-question identity, reusable shared
context, preamble-aware capture, multipart presentation semantics,
syllabus-sensitive classification and Question/Answer correction.

Sprint 08 added subject-neutral curriculum authoring, mapping coverage,
legacy-metadata correction, persisted Question response type,
response-type-aware Answer completeness and corpus-audit tooling.

Sprint 09 completed a sustained-use capture/correction pass and was merged to
`main` in commit `2533586`. It added responsive capture controls, deterministic
source ordering, shared-preamble recapture, Exam correction with managed-file
relocation, known-PDF reuse, legacy Question splitting, Search scope, audit
ordering, MCQ Answer-PDF visibility, UI package refactoring and CI hardening.

Protected-main workflow was then exercised through pull request #1. GitHub
reports `main` as protected.

The latest supported SQLite schema version is **8**.

## Implemented and current

### Application foundation

- Maven-based Java application using Java 25.
- Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox for PDF loading, rendering and region extraction.
- SQLite persistence with foreign-key enforcement and sequential migrations.
- JUnit and TestFX regression coverage.
- Repository/service/importer/PDF/output/UI package separation.
- GitHub Actions with separate non-UI, remaining-UI and workflow-UI jobs.

### Configuration and managed data

One configured `data.root` derives managed locations including `pdf/`,
`curriculum/` and `questionbank.db`.

Persisted document paths are portable paths beneath the managed data root rather
than machine-specific absolute paths. Managed source PDFs remain authoritative;
rendered images are derived assets.

Exam/provider/year correction relocates managed exam/answer PDFs when the
managed directory changes and updates persisted source paths with rollback
protection. Assessment-name-only edits do not move files because assessment name
is not part of the managed path.

### Database and assessment model

Schema version 8 supports Subjects, SyllabusVersions, curriculum hierarchy,
providers, Exams, Booklets, managed source documents, Questions, ordered Question
regions, persisted Question response type, Answers, ordered Answer regions,
historical-to-current mappings, SourceQuestion identity, SharedQuestionContext
and ordered shared-context regions.

Conceptually:

```text
Subject
  -> Exam
       -> ExamBooklet
            -> SourceQuestion (optional multipart identity)
            -> Question
                 -> QuestionResponseType
                 -> ordered QuestionRegion(s)
                 -> optional SharedQuestionContext
                 -> optional Answer
                      -> text and/or ordered AnswerRegion(s)
```

`SourceQuestion` and `SharedQuestionContext` are independent. A shared context may
legitimately be linked to otherwise independent Questions; the current normal
capture UI does not yet provide the general workflow for that case.

### Curriculum and mapping

Current behaviour supports Unit, Topic, optional Subtopic and Descriptor
hierarchies; historical/current syllabus versions; confirmed directional
historical-to-current mappings; one-to-many mapping; and provenance-preserving
retrieval.

Application SQLite mapping-review state is authoritative. The standalone
Chemistry mapping workbook remains reference material only.

### Curriculum authoring

Curriculum authoring can create and edit Subjects/syllabuses, author hierarchy
nodes, preserve managed syllabus-PDF provenance and use `IN_PROGRESS` / `FINAL`
lifecycle state.

One current user-observed defect is open: curriculum-code entry can leave visible
hierarchy ComboBox state inconsistent with the resolved model. A complete code
may populate Subtopic and Descriptor while Topic appears blank; after a complete
code, shortening to only the Unit can incorrectly restore the previous Topic.
This is Sprint 10 Slice 2 and is not yet fixed.

### Question capture and correction

Current Question capture supports rendered PDF selection, multiple ordered
ordinary regions, question code/marks, explicit Multiple Choice/Written Response
radio buttons, Subtopic/Descriptor classification, imported metadata-only
Questions, editing/correction, persisted SourceQuestion identity, multipart
shared-preamble capture/reuse, shared-context recapture and asynchronous save
work.

The current general shared-context UI remains primarily tied to multipart or
legacy preamble workflows. Independent MCQs that share a stimulus cannot yet be
captured naturally as separate Questions sharing one context. Sprint 10 plans to
expose this already-supported domain relationship without inventing multipart
identity.

A second current user-observed defect is open: after a valid PDF region is
selected, a later ordinary click can make the visible rectangle disappear while
the logical pending selection remains owned by the capture workflow. Sprint 10
Slice 1 will restore the invariant that visual and logical pending-selection
state agree.

### Answer capture and correction

Answer capture supports deterministic source-order unanswered queues, Question
marks, multiple ordered written-response regions, MCQ answer letters, registered
marking-PDF reuse, persisted Answer correction and asynchronous persistence.

Completeness remains response-type aware:

```text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> ordinary Answer capture blocked until resolved
```

### Legacy import and correction

Legacy import supports workbook Question metadata, exam/provider/year/booklet
reconstruction, historical classification, MCQ letters, metadata-only
zero-region Questions, managed source documents, idempotent/conflict-aware
persistence, conservative preamble evidence and marking-guide registration.

The legacy split workflow can transactionally convert one imported Question into
multipart parts with explicit part metadata, regions, Answer ownership and
shared-context options.

### Corpus audit and Search

Corpus Audit identifies missing Question source, missing Answer, unresolved
shared context and unknown response type. Search Questions supports `Current
syllabus` and `All Questions`, deterministic source ordering and stored preview
with shared context.

### Revision HTML and SCORM

The application builds a deterministic current-curriculum revision corpus and
exports a static hierarchical website with rendered assets, marks, provenance,
answer disclosure, SourceQuestion grouping and shared-context rendering.

Current presentation limitations include global presentation numbering, links to
empty curriculum branches, fixed hierarchy presentation and no export-time Unit
selection. The subject index currently exposes internal corpus counts that are
not the desired student-facing status model.

SCORM 1.2 packages the static revision site as a single-SCO ZIP. A real Chemistry
package has been imported into QLearn and launched successfully.

### Backup and restore

Current data-safety support includes versioned archives, SQLite-consistent
snapshots, manual and automatic backup, bounded retention, validated restore,
pre-restore protection, rollback after failed destructive restore,
migration-compatibility validation and a restart boundary.

## Repository governance

Sprint 09 merged to `main` on 20 September 2026 (`2533586`). The protected-main
workflow was then tested using pull request #1, resulting in current Sprint 10
starting point `5a1e5a9`. GitHub reports `main` as protected.

This supersedes earlier documentation that described Sprint 09 merge and main
protection as future closeout work.

## Sprint 10 planned scope — not yet implemented

Sprint 10 — Capture Hardening and Revision Output Refinement — is documented in
`docs/design/sprint-10-capture-output.md` and uses branch
`feature/capture-output`.

Planned work is:

1. pending PDF-selection state correction;
2. curriculum selector synchronisation correction;
3. Working Subject filtering for Question and Answer capture;
4. Written Response capture defaults for part-letter Questions and whole-number
   Questions worth more than one mark;
5. shared-context capture/reuse for independently classified Questions,
   including MCQs;
6. Subtopic-versus-Descriptor revision-output grouping;
7. MCQ-before-written ordering;
8. page-local numbering beginning at 1;
9. empty-branch pruning and selected-Unit export;
10. useful generated-site status including generation date/time rather than
    internal captured-part statistics.

## Active limitations and backlog themes

The authoritative deferred-work inventory is `docs/design/backlog.md`.

Major work deliberately outside Sprint 10 includes multi-page automatic shared
preamble capture, optional MCQ explanation regions, multipart provenance display
simplification, possible response-type section headings, Search-dialog position
persistence, empty managed-directory cleanup after successful Exam relocation,
Question-level applicability exceptions, multiple original-classification
decisions, explicit out-of-scope source disposition, import/reconciliation
hardening, future clipboard/image content, Exam Builder, printable assessment
output and packaging.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- Sprint 10 Working Subject filtering;
- automatic Written Response defaults from code/marks;
- general independent-Question shared-context capture through normal capture;
- selectable Subtopic/Descriptor revision grouping;
- selected-Unit revision export;
- page-local revision numbering;
- complete current-generation Exam Builder/assessment assembly;
- multiple direct original classifications on one Question;
- automatic multi-page shared-preamble capture;
- Question-level mapped-applicability exclusions;
- clipboard/image-attachment Question capture;
- current-generation printable assessment assembly;
- self-contained installer/deployment packaging;
- safe simultaneous multi-user editing of one shared SQLite database.
