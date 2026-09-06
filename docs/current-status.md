# Exam Question Bank — Current Status

> Authoritative status at 6 September 2026.
>
> Repository evidence inspected through the 6 September 2026 `feature/html-output` state. Where a separate data artifact is not established as committed/imported into the application, that limitation is stated explicitly.

## Status summary

The Exam Question Bank is a working Java/JavaFX desktop application with SQLite persistence, managed source PDFs, versioned curriculum data, PDF-region question/answer capture, legacy metadata import, directional curriculum mapping and current-curriculum question retrieval.

The application is no longer an Excel-backed generation pipeline. Excel is an import/exchange format; SQLite is the live datastore. Original exam and marking PDFs are authoritative source material. Rendered/cropped images are derived.

Completed sprint work currently extends through **Sprint 05 — Hierarchical Revision Corpus and Static HTML Output**. **Sprint 06 — SCORM Package Generation and QLearn Validation is the next development sprint.**

## Implemented and current

### Application foundation

**IMPLEMENTED / CURRENT**

- Maven-based Java application configured for Java 25.
- Proper Java module `au.edu.eq.questionbank`.
- JavaFX desktop UI.
- Apache PDFBox PDF handling/extraction.
- SQLite runtime database.
- SQLite foreign-key enforcement.
- Transactional schema migrations.
- JUnit 6 automated tests and TestFX workflow coverage.
- Repository/service/importer/PDF/output/UI package separation.

### Configuration and managed data root

**IMPLEMENTED / CURRENT**

One configured `data.root` derives managed locations for:

```text
pdf/
curriculum/
questionbank.db
```

Legacy configuration is still readable. Options can change the root, taking effect after restart.

Persisted managed documents use portable paths beneath the configured data root rather than machine-specific absolute paths.

### PDF viewer and managed exam import

**IMPLEMENTED / CURRENT**

- File -> Open -> PDF can view an arbitrary external PDF without importing it.
- Viewer mode disables region capture and does not replace the active exam.
- Closing viewer mode restores the prior managed PDF/page where applicable.
- Exam Import owns subject/exam metadata.
- An external exam PDF selected for persistence is copied into the managed PDF hierarchy before it is stored.
- Changing Classification to a subject incompatible with the active exam invalidates the active exam/booklet rather than leaving inconsistent state.
- Help -> Version Information exposes build/runtime, SQLite and schema information.

### Assessment domain

**IMPLEMENTED / CURRENT**

```text
Subject
  -> Exam
       -> ExamBooklet
            -> Question
                 -> ordered QuestionRegion(s)
                 -> optional Answer / marking material
```

- A `Question` belongs to one `ExamBooklet`.
- Its `Exam` is obtained through that booklet.
- Natural identity is `(booklet_id, question_code)`.
- Question codes are text and support values such as `21a`.
- Marks are positive whole numbers.
- Legacy-imported questions may exist with zero question regions while capture is pending.
- Normal manual capture requires at least one question region before save.

### Current original-classification model

**IMPLEMENTED / CURRENT — one best-fit classification.**

The current `Question` model stores one original curriculum classification, which must be a Subtopic or Descriptor.

**KNOWN HISTORICAL REQUIREMENT / DESIGN GAP.**  
The 17 August Neap classification exercise demonstrated at least one real question reasonably classified against three descriptors. An early many-to-many `Question <-> Descriptor` design was therefore proposed. The current one-best-fit model does not implement that direct multi-classification requirement, and no available history establishes that the requirement was deliberately withdrawn. This should be resolved explicitly rather than silently assumed away.

### PDF-region question storage

**IMPLEMENTED / CURRENT**

`QuestionRegion` stores:

- source `ExamBooklet`;
- one-based page number;
- normalized `x`, `y`, `width`, `height`;
- meaningful list order.

Coordinates are independent of render DPI/zoom. The PDF boundary performs any zero-based page conversion required by PDFBox.

### Question capture UI

**IMPLEMENTED / CURRENT**

- rendered PDF page viewer;
- draggable current selection;
- normalized region creation;
- current-selection preview generated directly from PDFBox output;
- accepted question-region list;
- multiple ordered regions;
- per-region Remove;
- dynamic accepted-region area.

A separate permanent combined-question preview area was intentionally removed from the final capture interaction.

### Answer capture

**IMPLEMENTED / CURRENT**

- explicit `Answer`, `AnswerFile`, `AnswerRegion` model;
- text-only, region-only or combined answers;
- current answer-selection preview;
- accepted answer-region thumbnails;
- multiple answer regions;
- per-region Remove;
- answer persistence;
- answer preview clearing on page change/acceptance.

MCQ letters can be represented as text-only answers.

### Subject, exam and question metadata

**IMPLEMENTED / CURRENT**

- subject-neutral domain rather than Chemistry-specific core code;
- exam provider/year/booklet relationships;
- managed source documents;
- question code and marks;
- historical curriculum classification;
- optional answer material;
- legacy `preambleCaptureRequired` hint.

### Versioned curriculum

**IMPLEMENTED / CURRENT**

- Subject and `SyllabusVersion` data;
- generic curriculum nodes;
- Unit, Topic, Subtopic and Descriptor hierarchy;
- curriculum Excel import;
- current/historical syllabus selection;
- Chemistry 2019 and 2025 curriculum workbooks;
- Physics 2019 curriculum data in the repository.

**CURRENT LIMIT.**  
Chemistry is the first and most complete development dataset. Complete current-version/mapping/question-corpus parity for every science subject is not established.

### Historical -> current curriculum mapping

**IMPLEMENTED / CURRENT**

- explicit historical-to-current direction;
- Descriptor -> Descriptor mapping;
- Subtopic -> Subtopic mapping;
- one-to-many mappings;
- confirmed/suggested/no-match/unreviewed semantics;
- only confirmed mappings affect retrieval;
- original historical classification remains provenance;
- current applicability is derived;
- Subtopic mapping does not invent Descriptor-level precision.

### 5 September Chemistry mapping workbook

**IMPLEMENTED AS A DATA ARTIFACT; APPLICATION INTEGRATION NOT ESTABLISHED.**

A separate `Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` was produced from supplied 2019/2025 workbooks with normalized pairwise relationships and coverage sheets.

Recorded counts:

- 98 source descriptors;
- 120 target descriptors;
- 57 High, 21 Medium, 9 Low confidence;
- 11 source descriptors with no direct target;
- 23 target descriptors with no direct predecessor.

Low/no-match mappings require manual review; Medium mappings require checking.

The application already has curriculum-mapping functionality, but the available history does **not** establish that this new 5 September workbook has been imported into or reconciled with the SQLite mapping records.

### Legacy metadata import

**IMPLEMENTED / CURRENT — Sprint 01 complete**

- imports legacy Excel question metadata;
- reconstructs source Exam/ExamBooklet relationships;
- treats question codes as text;
- preserves historical classification;
- imports MCQ answer letters when supplied;
- supports metadata-only questions with zero regions;
- supports managed source-document resolution/import;
- uses idempotent/conflict-aware persistence;
- does not create placeholder regions or fake empty answers.

### Preamble/shared-context state

**IMPLEMENTED / CURRENT — limited.**  
The current persistence model retains a legacy `preambleCaptureRequired` hint.

**NOT CURRENTLY IMPLEMENTED — generalized shared-context model.**  
There is no established persisted `QuestionPreamble` entity, no generalized dependency graph between part questions and no confirmed “pin region” state. Long-term shared-context/multipart semantics remain backlog work.

### Current-curriculum retrieval

**IMPLEMENTED / CURRENT — Sprint 03 complete**

Search scopes:

```text
Subject
Unit
Topic
Subtopic
Descriptor
```

Retrieval includes:

- questions directly classified to the current curriculum;
- historical questions connected through confirmed mappings;
- explicit hierarchy expansion;
- duplicate prevention;
- original provenance;
- SQLite repository/service retrieval;
- asynchronous JavaFX search;
- stale-result/lifecycle protection;
- question details and reconstructed source preview.

The completed sprint quality gate recorded the automated suite green and manual exercise against real stored questions.

### Backup, restore and data safety

**IMPLEMENTED / CURRENT — Sprint 04 complete**

Sprint 04 delivered:

- versioned backup archives;
- SQLite-consistent snapshots;
- manual full backup;
- automatic database-only backup on normal application close;
- bounded automatic-backup retention;
- validated database-only and full restore;
- pre-restore safety backup;
- rollback after failed destructive restore;
- migration-compatibility validation before restore;
- backup archive path/content validation;
- backup/restore filesystem-layout hardening;
- restart boundary after successful or partially destructive restore.

The completed sprint included focused service, SQLite and JavaFX lifecycle tests plus manual full-backup and restore acceptance testing.

### Testing and development workflow

**IMPLEMENTED / CURRENT**

- feature branches and logical commits;
- Maven clean/full-suite checks at major boundaries;
- SQLite integration tests where persistence crosses layers;
- TestFX coverage for UI workflow/regressions;
- manual visual verification where needed;
- merge-readiness review for substantial branches;
- `AGENTS.md` repository guidance for Codex;
- project chats used for architectural/schema decisions, with Codex used selectively for repository-wide review/refactors.

Known branch examples include:

- `feature/pdf-region-selection`
- `feature/question-metadata`
- `feature/question-answers`
- `refactor/application-structure`
- `feature/menu-bar`
- `feature/syllabus-version-selection`
- `feature/legacy-metadata-import`
- `feature/curriculum-applicability`
- `feature/question-retrieval`
- `feature/backup-restore`
- `feature/html-output`

## Implemented foundation, but not the final product feature

### Revision corpus and static HTML output

**IMPLEMENTED / CURRENT — Sprint 05 complete.**

The application can export one Subject's current syllabus as a deterministic static revision website.

Implemented behaviour includes:

- transient current-curriculum `RevisionCorpus` generation;
- reuse of Sprint 03 current-applicability semantics;
- deterministic curriculum and question ordering;
- unique rendered question assets shared across repeated placements;
- ordered answer-region assets;
- generated Subject, Unit, Topic and Subtopic pages;
- selectable Subtopics where the Topic hierarchy contains Subtopics;
- direct Subtopic question placement;
- omission of empty Descriptor sections;
- Descriptor-mode Topic rendering where applicable;
- generated revision numbering for renderable placements;
- source attribution and original-classification provenance;
- native HTML answer disclosure;
- explicit missing-answer presentation;
- omission and statistics for zero-region metadata-only questions;
- staged generation;
- generated-reference validation;
- safe publication only after successful validation;
- portable relative links;
- no source PDFs copied into the static export;
- JavaFX Subject/destination workflow;
- background generation;
- genuine question/answer progress reporting;
- success/failure summaries;
- collision-safe export-directory naming.

The earlier `HtmlQuestionRenderer` remains a small proof/foundation class; the production revision workflow is implemented separately under the revision corpus/output packages.

Real Chemistry browser acceptance was completed successfully on 6 September 2026.

### PDF/LaTeX output

**IMPLEMENTED — legacy application only.**  
The predecessor generated LaTeX and invoked an external executable for PDF generation.

**PROPOSED — current application later.**  
For printable current-generation output, the recorded preferred approach is direct clipping of original PDF pages in LaTeX (or an equivalent vector-preserving method), rather than using rasterized intermediate question images.

### SCORM

**IMPLEMENTED — predecessor only.**  
The legacy application generated hierarchical SCORM-style packages.

**PROPOSED — current application Sprint 06.**  
The new application does not yet have the full SCORM exporter/validator. The target is static generated web assets and ordinary relative links; full source PDFs should not normally be packaged.

## Current work / next sprint

### Sprint 06 — SCORM Package Generation and QLearn Validation

**NEXT DEVELOPMENT SPRINT.**

Sprint 05 now supplies the tested static HTML/assets content layer.

Sprint 06 will package that content as an application-generated SCORM ZIP and validate the result against QLearn.

The known-working QLearn SCORM example should be used as an implementation/acceptance reference during Sprint 06 rather than changing the static-content model speculatively.

## Parallel data/corpus work

**CURRENT / IN PROGRESS AS DATA WORK**

- capture missing question regions;
- capture missing answer/marking regions;
- verify historical classifications;
- review/reconcile 2019 -> 2025 mappings;
- manually review CHECK/YES rows in the 5 September mapping workbook;
- determine whether/how that workbook should be imported or reconciled with current SQLite mapping records;
- resolve missing source documents;
- test retrieval/preview against larger real datasets.

## Known backlog / unresolved design requirements

**CURRENT BACKLOG**

- dedicated capture-required work queue;
- generalized shared-context/multipart semantics;
- explicit decision on multi-descriptor original classification;
- possible out-of-scope disposition for source questions during exam processing;
- import audit/reconciliation reporting;
- additional real-world workbook variants;
- TestFX focus stability and async regression hardening;
- retrieval-domain/integration hardening;
- measurement-driven broad-search and preview performance work;
- mapping workbook/database reconciliation and review completion;
- later clipboard/drag-drop image-question support;
- later installer/packaging/deployment design.

## Future output/deployment requirements

**PROPOSED**

- No browser-side PDF.js dependency for the primary static revision package.
- Sprint 06 application-generated SCORM ZIP and real QLearn validation.
- Printable assessment resources with sequential generated numbering and retained source attribution.
- Vector-preserving PDF output where practical.
- `jpackage`-style self-contained desktop packaging.
- Writable data outside the installed application directory.
- SharePoint useful for backups/exports/source distribution, not a concurrently edited live SQLite database.

## Future non-PDF source content

**PROPOSED / NOT IMPLEMENTED**

A later feature may accept image content from the system clipboard (for example Windows Snipping Tool), and possibly drag/drop PNG/JPEG files. The storage model has not been chosen. OCR is explicitly optional later work rather than a prerequisite.

## Explicit non-current claims

Do **not** describe the following as current application capabilities:

- completed current-generation SCORM export/QLearn validation;
- completed current-generation Exam Builder;
- complete multi-subject current-curriculum/mapping corpus for every science;
- multiple direct original classifications on one `Question`;
- a finalized generalized shared-preamble/part-dependency model;
- clipboard/image-attachment question capture;
- current-generation LaTeX/PDF assembly from clipped source PDFs;
- self-contained installer/jpackage deployment.
