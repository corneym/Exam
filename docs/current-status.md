# Current Status

> **Authoritative status date:** 22 September 2026.  
> Sprints 01 through 09 are complete and merged.  
> Current `main` at Sprint 10 start: `5a1e5a9`.  
> Active branch: `feature/capture-output`.  
> Sprint 10 implementation is in progress; only behaviour supported by repository, test or explicit manual-verification evidence is described as implemented below.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application backed by SQLite. It manages authoritative exam and marking PDFs, versioned curriculum data, legacy metadata import, Question and Answer capture from PDF regions, historical-to-current curriculum mapping, current-curriculum retrieval, revision HTML generation, SCORM 1.2 packaging, backup/restore, curriculum authoring, corpus audit and correction of persisted assessment data.

Sprint 07 established persisted source-question identity, reusable shared context, preamble-aware capture, multipart presentation semantics, syllabus-sensitive classification and Question/Answer correction.

Sprint 08 added subject-neutral curriculum authoring, mapping coverage, legacy-metadata correction, persisted Question response type, response-type-aware Answer completeness and corpus-audit tooling.

Sprint 09 completed a sustained-use capture/correction pass and was merged to `main` in commit `2533586`. It added responsive capture controls, deterministic source ordering, shared-context recapture, Exam correction with managed-file relocation, known-PDF reuse, legacy Question splitting, Search scope, audit ordering, MCQ Answer-PDF visibility, UI package refactoring and CI hardening.

Protected-main workflow was then exercised through pull request #1. GitHub reports `main` as protected.

Sprint 10 implementation to date includes capture-selection state correction, curriculum selector synchronisation, Working Subject filtering, persisted booklet-level Question format, response-type capture rules, booklet-specific AnswerFile assignment, and sequence-aware shared-context capture for independent MCQs.

The latest supported SQLite schema version is **11**.

## Implemented and current

### Application foundation

- Maven-based Java application using Java 25.
- Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox for PDF loading, rendering and region extraction.
- SQLite persistence with foreign-key enforcement and sequential migrations.
- SQLite connections use a bounded busy timeout so short asynchronous writes do not cause concurrent JavaFX reads to fail immediately with `SQLITE_BUSY`.
- JUnit and TestFX regression coverage.
- Repository/service/importer/PDF/output/UI package separation.
- GitHub Actions with separate non-UI, remaining-UI and workflow-UI jobs.

### Configuration and managed data

One configured `data.root` derives managed locations including `pdf/`, `curriculum/` and `questionbank.db`.

Persisted document paths are portable paths beneath the managed data root rather than machine-specific absolute paths. Managed source PDFs remain authoritative; rendered images are derived assets.

Exam/provider/year correction relocates managed exam/answer PDFs when the managed directory changes and updates persisted source paths with rollback protection. Assessment-name-only edits do not move files because assessment name is not part of the managed path.

### Database and assessment model

Schema version 11 supports Subjects, SyllabusVersions, curriculum hierarchy, providers, Exams, Booklets, managed source documents, booklet-level Question format, booklet-to-AnswerFile assignment, restart-safe pending independent-MCQ shared-context continuation, Questions, ordered Question regions, persisted Question response type, Answers, ordered Answer regions, historical-to-current mappings, SourceQuestion identity, SharedQuestionContext and ordered shared-context regions.

Schema version 9 added persisted `ExamBookletQuestionFormat`.

Schema version 10 added the nullable `ExamBooklet -> AnswerFile` relationship. Several booklets may share one AnswerFile, but each resolved booklet has at most one assigned AnswerFile. Legacy data is migrated conservatively and unresolved relationships are not guessed.

Schema version 11 added nullable `exam_booklets.pending_mcq_shared_context_id`. The field records only an explicit one-step continuation requested during independent MCQ capture. Existing databases migrate with NULL because legacy data does not establish continuation intent.

Conceptually:

```text
Subject
  -> Exam
       -> AnswerFile
       -> ExamBooklet
            -> ExamBookletQuestionFormat
            -> optional assigned AnswerFile
            -> optional pending independent-MCQ SharedQuestionContext
            -> SourceQuestion (optional multipart identity)
            -> Question
                 -> QuestionResponseType
                 -> ordered QuestionRegion(s)
                 -> optional SharedQuestionContext
                 -> optional Answer
                      -> text and/or ordered AnswerRegion(s)
```

`SourceQuestion` and `SharedQuestionContext` are independent. Independent MCQs may share a SharedQuestionContext without acquiring SourceQuestion multipart identity.

### Curriculum and mapping

Current behaviour supports Unit, Topic, optional Subtopic and Descriptor hierarchies; historical/current syllabus versions; confirmed directional historical-to-current mappings; one-to-many mapping; and provenance-preserving retrieval.

Application SQLite mapping-review state is authoritative. The standalone Chemistry mapping workbook remains reference material only.

### Curriculum authoring

Curriculum authoring can create and edit Subjects/syllabuses, author hierarchy nodes, preserve managed syllabus-PDF provenance and use `IN_PROGRESS` / `FINAL` lifecycle state.

Sprint 10 corrected code-driven hierarchy synchronisation so visible Unit/Topic/Subtopic/Descriptor controls and the `CurriculumSelectionModel` remain consistent when a complete code is entered or shortened.

### Question capture and correction

Current Question capture supports rendered PDF selection, multiple ordered ordinary regions, question code/marks, Subtopic/Descriptor classification, imported metadata-only Questions, editing/correction, persisted SourceQuestion identity, multipart shared-context capture/reuse, independent-MCQ shared-context capture/reuse, shared-context recapture and asynchronous save work.

Sprint 10 corrected the pending-selection invariant so a visually cancelled PDF selection no longer remains logically owned by Question or Answer capture.

Successful booklet activation now also clears transient Question code/marks, accepted regions, pending rectangles, transient shared-context state and stale green pending-status text from the previous booklet.

A workspace-level Working Subject filters Question and Answer capture queues without rewriting persisted Subject, classification or Exam ownership.

Each ExamBooklet records a Question format:

```text
MULTIPLE_CHOICE
WRITTEN_RESPONSE
MIXED
UNSPECIFIED
```

`UNSPECIFIED` is reserved for unresolved legacy data.

For genuinely new Question capture:

- MCQ-only booklets fix response type to Multiple Choice and marks to 1;
- Written-Response-only booklets fix response type to Written Response while marks remain editable;
- Mixed booklets permit manual response-type choice and conservative Written Response inference;
- one mark alone never implies Multiple Choice;
- imported, edited and legacy-split Questions retain their persisted response type.

For genuinely new independent MCQs, the UI exposes `Shared context with next question`.

When selected on an MCQ that has no inherited context, the application begins automatic Shared Context capture. Saving the Question records that context and a booklet-scoped one-step continuation.

The next supported numeric successor may inherit automatically. Examples include `5 -> 6`, `Q5 -> Q6` and `Q09 -> Q10`.

Continuation is sequence-aware rather than capture-order-aware. If Q5 leaves context pending and Q7 is captured first, Q7 does not inherit or consume the Q5 context. Q6 may still inherit it later.

An inherited Question starts with the continuation checkbox clear. Selecting it extends the same context one Question further without recapturing the context. Leaving it clear consumes the continuation when the intended successor is successfully saved.

The pending continuation is restart-safe and booklet-scoped. EDIT and IMPORTED operations do not consume it. Independent MCQ sharing never creates a common SourceQuestion.

Question-code duplication is now reported immediately and non-modally when the entered code already exists in the active booklet. Save is disabled before the user repeats classification/capture work. Correcting the code preserves already accepted regions and metadata.

User-facing capture/correction terminology is now **Shared Context**. Legacy/internal identifiers such as `PreambleStatus`, historical database fields and method names may remain where changing them would add unnecessary migration/refactor risk.

### Answer capture and correction

Answer capture supports deterministic source-order unanswered queues, Question marks, multiple ordered written-response regions, MCQ answer letters, persisted Answer correction and asynchronous persistence.

Answer PDFs are resolved at ExamBooklet level rather than merely at Exam level. Each booklet may have one assigned AnswerFile, while one AnswerFile may serve several booklets. For example, an MCQ booklet and Paper 1 may share one marking PDF while Paper 2 uses another.

When Answer capture moves to a booklet whose AnswerFile is already known, the correct PDF is restored automatically. If the booklet has no assignment, the previous booklet's PDF is cleared and `Choose PDF...` is shown again. Selecting a PDF persists that mapping for later Questions and later application sessions.

Answer persistence rejects one Answer whose regions span multiple AnswerFiles, rejects AnswerFiles belonging to another Exam and rejects regions that conflict with the AnswerFile assigned to the Question's booklet.

The MCQ/Paper 1 shared-answer-file plus Paper 2 separate-answer-file workflow has been manually verified in the real application, including persistence across application restart.

Completeness remains response-type aware:

```text
MULTIPLE_CHOICE -> valid A/B/C/D answer required; regions optional
WRITTEN_RESPONSE -> one or more Answer regions required
UNKNOWN -> ordinary Answer capture blocked until resolved
```

### Legacy import and correction

Legacy import supports workbook Question metadata, exam/provider/year/booklet reconstruction, historical classification, MCQ letters, metadata-only zero-region Questions, managed source documents, idempotent/conflict-aware persistence, conservative legacy preamble evidence and marking-guide registration.

The legacy split workflow can transactionally convert one imported Question into multipart parts with explicit part metadata, regions, Answer ownership and shared-context options.

User-facing correction dialogs and statuses now use Shared Context terminology while internal historical identifiers may remain.

### Corpus audit and Search

Corpus Audit identifies missing Question source, missing Answer, unresolved shared context and unknown response type. Search Questions supports `Current syllabus` and `All Questions`, deterministic source ordering and stored preview with shared context.

### Revision HTML and SCORM

The application builds a deterministic current-curriculum revision corpus and exports a static hierarchical website with rendered assets, marks, provenance, answer disclosure, SourceQuestion grouping and shared-context rendering.

Current presentation limitations include global presentation numbering, links to empty curriculum branches, fixed hierarchy presentation and no export-time Unit selection. The subject index currently exposes internal corpus counts that are not the desired student-facing status model.

SCORM 1.2 packages the static revision site as a single-SCO ZIP. A real Chemistry package has been imported into QLearn and launched successfully.

### Backup and restore

Current data-safety support includes versioned archives, SQLite-consistent snapshots, manual and automatic backup, bounded retention, validated restore, pre-restore protection, rollback after failed destructive restore, migration-compatibility validation and a restart boundary.

## Repository governance

Sprint 09 merged to `main` on 20 September 2026 (`2533586`). The protected-main workflow was then tested using pull request #1, resulting in current Sprint 10 starting point `5a1e5a9`. GitHub reports `main` as protected.

This supersedes earlier documentation that described Sprint 09 merge and main protection as future closeout work.

## Sprint 10 implementation status

Sprint 10 — Capture Hardening and Revision Output Refinement — is documented in `docs/design/sprint-10-capture-output.md` and uses branch `feature/capture-output`.

Implemented work includes:

1. pending PDF-selection state correction and booklet-transition capture reset;
2. curriculum selector synchronisation correction;
3. Working Subject filtering for Question and Answer capture;
4. persisted booklet-level Question format and response-type capture rules;
5. booklet-specific AnswerFile persistence and automatic Answer-PDF switching;
6. independent-MCQ Shared Context capture, restart-safe continuation, immediate-successor enforcement and continuation consumption/extension;
7. early duplicate Question-code feedback;
8. user-facing Shared Context terminology cleanup in the affected capture/correction workflows.

Still-planned Sprint 10 work includes:

- Subtopic-versus-Descriptor revision-output grouping;
- MCQ-before-written ordering;
- page-local numbering beginning at 1;
- empty-branch pruning and selected-Unit export;
- useful generated-site status including generation date/time rather than internal captured-part statistics.

## Verification evidence

Current Sprint 10 verification includes:

- schema/persistence tests green with latest schema version 11;
- the previously stale schema-version expectation corrected and passing;
- focused response-type workflow tests green;
- focused Answer Capture workflow tests green after booklet-specific AnswerFile changes and SQLite busy-timeout hardening;
- service tests covering restart-safe and sequence-aware independent-MCQ context continuation green;
- `WorkflowCaptureTests` and `WorkflowStateEditingTests` green after Slice 5 implementation, follow-up state fixes and Shared Context terminology cleanup;
- manual real-application verification of shared/separate answer PDFs across multiple booklets;
- manual real-application verification of Q5/Q6/Q7 Shared Context continuation, restart persistence, different classifications, out-of-sequence protection and booklet-switch reset behaviour.

A broader full-suite/CI run remains appropriate at major Sprint 10 checkpoints and closeout.

## Active limitations and backlog themes

The authoritative deferred-work inventory is `docs/design/backlog.md`.

Major work deliberately outside Sprint 10 includes multi-page automatic shared-context capture, optional MCQ explanation regions, multipart provenance display simplification, possible response-type section headings, Search-dialog position persistence, empty managed-directory cleanup after successful Exam relocation, Question-level applicability exceptions, multiple original-classification decisions, explicit out-of-scope source disposition, import/reconciliation hardening, future clipboard/image content, Exam Builder, printable assessment output and packaging.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- selectable Subtopic/Descriptor revision grouping;
- selected-Unit revision export;
- page-local revision numbering;
- complete current-generation Exam Builder/assessment assembly;
- multiple direct original classifications on one Question;
- automatic multi-page shared-context capture;
- Question-level mapped-applicability exclusions;
- clipboard/image-attachment Question capture;
- current-generation printable assessment assembly;
- self-contained installer/deployment packaging;
- safe simultaneous multi-user editing of one shared SQLite database.
