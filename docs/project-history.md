# Exam Question Bank — Project History

> Historical reconstruction through 6 September 2026.
>
> Evidence basis: project development chats and their evidence extracts, the current `corneym/Exam` repository and sprint/design documents, and the preserved `corneym/ExamBuilderLegacy` repository.
>
> Status vocabulary:
>
> - **IMPLEMENTED** — coded and evidenced as working, tested, or persisted.
> - **DECIDED** — an adopted architectural/design rule.
> - **PROPOSED** — discussed or planned but not established as implemented.
> - **SUPERSEDED** — an earlier implementation/design later replaced or deliberately not adopted.
> - **CURRENT** — the newest known design or implementation as at the reference date.

## 1. Legacy faculty science Exam Builder

### Phase: before the current Exam Question Bank project

**IMPLEMENTED / SUPERSEDED — faculty-wide Java question-resource generator.**  
The predecessor application supported multiple science subjects rather than Chemistry alone. It organised questions under a syllabus hierarchy and assembled question resources from existing metadata and image assets. The surviving repository is predominantly Chemistry, but both project-chat evidence and the generic `Subject`, `Unit`, `Topic`, `Subtopic` and related classes establish that the original design was faculty-oriented.

**IMPLEMENTED / SUPERSEDED — Excel was the operational metadata store.**  
The legacy workflow used separate workbooks for syllabus descriptors and exam/question metadata. Metadata included subject context, exam/year, paper, question number or part, marks, curriculum classification, preamble information and multiple-choice answer letters. The surviving legacy repository includes files such as `Chemistry.xlsx`, `ChemistryExamBreakdown.xlsx` and `ChemistrySyllabusDescriptors.xlsx`.

**IMPLEMENTED / SUPERSEDED — named image snips were canonical question/answer assets.**  
Questions and written-response answers were stored as `.png`/`.jpg` files using naming conventions and exam-specific folders. Optional preamble/shared-context images were also supported. File names were derived from question metadata and copied into generated outputs.

**IMPLEMENTED — multiple-choice answers were stored as structured text.**  
`MultipleChoiceQuestion` stores the workbook answer letter and renders it into HTML and LaTeX. MCQ answers therefore did not require answer-image files.

**IMPLEMENTED — multipart questions and shared preambles.**  
`MultiPartQuestion` grouped short-answer parts, accumulated marks and could render a shared preamble once before the parts. This is important historical behaviour because later current-generation discussions reconsidered how shared context should be represented.

**IMPLEMENTED — LaTeX and PDF generation.**  
The legacy Java application generated LaTeX source and, according to the project-chat evidence, called an external executable to turn that LaTeX into PDF output. The preserved source directly establishes `.tex` generation; the external compilation step is established by the development-history chat rather than by the inspected `ExamBuilder.java` file alone.

**IMPLEMENTED — hierarchical static HTML.**  
HTML generation cascaded through the syllabus hierarchy. Question, preamble and answer images were copied into the output tree.

**IMPLEMENTED — SCORM-style publication.**  
The legacy application generated an `imsmanifest.xml`, declared ADL SCORM 1.2 metadata and built ZIP packages. Project-chat evidence also records that the resources were published through a school content system using SCORM.

**NOT ESTABLISHED — formal conformance validation.**  
The available evidence does not establish that the legacy SCORM packages were run through a formal standards validator, so validation should not be claimed as a legacy feature.

## 2. 17 August 2026 — descriptor batching proves the redevelopment requirements

Before application code was rebuilt, the project tested whether existing commercial examination PDFs could be reorganised into curriculum-specific question resources.

**DECIDED — preserve original visual formatting.**  
Questions should not be retyped from extracted text when the source PDF already contains authoritative chemistry notation, structures, graphs, spectra, tables and diagrams. Visual source fidelity became a central design requirement.

**TEST / RESULT — 2021 Neap Paper 1 MCQ classification exercise.**  
All 25 questions in a representative Neap Chemistry Paper 1 multiple-choice booklet were manually classified. The exercise demonstrated that:

- a source question may reasonably map to more than one syllabus descriptor;
- a source question may be deliberately considered out of scope;
- original exam/question provenance must survive rebatching.

The recorded example was original Q11 classified against three descriptors. Q6, Q21 and Q22 were marked out of scope.

**IMPLEMENTED AS A CHAT PROTOTYPE, NOT APPLICATION CODE — first descriptor booklet.**  
A prototype resource for descriptor `3.2.1` used original Neap questions Q4, Q5 and Q12. A revised version renumbered them sequentially from 1 while retaining exam/source information and original question number.

**TEST / RESULT — vector/original PDF content was preserved in the prototype.**  
This demonstrated the desired output pattern: generated numbering for the new resource, original source content, and explicit source attribution.

**TEST / RESULT — representative source PDFs were judged suitable for region extraction.**  
Question papers from QCAA, Neap trial examinations, Neap Diagnostic Topic Tests and QTE practice examinations were reviewed. Corresponding solution/marking-guide PDFs from the same source families were also reviewed and judged workable.

**OBSERVED — text extraction is not reliable enough to be authoritative.**  
Equations, subscripts, superscripts and chemistry notation can degrade under plain-text extraction. This reinforced the decision to preserve visual PDF content and use text only as supplementary/search/classification data.

**DECIDED — short-response solutions belong to the same logical question.**  
Written-response solution regions should be linked to the question. MCQ letters remain structured text; explanatory MCQ solution regions may additionally be linked when supplied.

## 3. 17 August 2026 — target architecture is set before implementation

**DECIDED / CURRENT — source PDF + page + crop coordinates replace permanent new snips.**  
The canonical representation for new PDF-based content became:

```text
source PDF
+ page number
+ crop rectangle / coordinates
```

The source PDF is immutable authoritative material. Generated images are disposable artefacts.

**IMPORTANT REASON.**  
The legacy application had accumulated hundreds of manually maintained snips. Region storage avoids duplicated assets, naming problems and avoidable rasterisation, while allowing crop boundaries to be changed without recreating source material.

**DECIDED — legacy snips should not force immediate migration.**  
Existing image-based material could remain supported during migration rather than requiring a destructive all-at-once conversion.

**DECIDED — PDFBox was the preferred Java PDF library.**  
It was selected for page rendering, coordinate handling, text-position inspection and generation of static web assets.

**DECIDED — SQLite should replace Excel as the live datastore.**  
Excel would remain useful for import/export and migration, but relational question, source-document, curriculum, region and answer data belonged in SQLite.

**DECIDED — the core must remain science-subject neutral.**  
Chemistry would be the development subject, but the data model should support Physics, Biology and other sciences without hard-coded Chemistry or fixed 2019 hierarchy assumptions. A generic `Subject` / curriculum version / `CurriculumNode` model was preferred.

**DECIDED — curriculum versions must coexist.**  
The 2019 and 2025 syllabuses should be separate versions. Historical classifications should not be overwritten by current classifications. A crosswalk/mapping layer should represent equivalence, split, merge, partial, removed and new content where useful.

**DECIDED — static HTML/SCORM should be built from derived web images.**  
For browser delivery, PDFBox should render the stored regions into PNG or similar assets at build time. Browser-side PDF rendering/PDF.js was rejected as the target approach. Full source PDFs should not normally be embedded inside the SCORM package.

**DECIDED — printable PDF output should preserve vector source content where practical.**  
The preferred later LaTeX approach was to include the original PDF page with clipping/viewport arguments rather than rasterising an intermediate question image.

**PROPOSED — self-contained desktop packaging.**  
`jpackage` was identified as the likely deployment mechanism. The writable database/data root should live outside the installed application directory.

**DECIDED — live shared SQLite on SharePoint/network sync is not a Version 1 architecture.**  
SharePoint may be useful for source documents, backups, exports and published resources, but a concurrently edited synced SQLite file was explicitly rejected. A central server database or controlled merge/import workflow could be considered later.

## 4. 18 August 2026 — new Java project and smallest vertical slice

**IMPLEMENTED — Maven project created.**  
The current repository begins with commit `2ee6d094` (`Initial Maven project setup`), followed by Java 25 configuration in `db5515ed`.

**DECIDED — build the smallest useful vertical slice rather than the whole database/UI.**  
The first concepts were `Question`, `QuestionRepository`, `InMemoryQuestionRepository`, `PdfStore` and a small smoke-test `Main`.

**IMPLEMENTED — portable source-path handling.**  
`PdfStore` resolved files under a configurable data root, normalized paths, rejected unsafe/absolute escapes and avoided storing machine-specific absolute paths in questions.

**IMPLEMENTED / EVOLVED — `SourceDocument -> Exam -> Question` removed duplicated PDF paths.**  
The first domain refactor moved source-document responsibility out of `Question`. Many questions could then refer to one source exam/document rather than duplicating the same path.

**IMPLEMENTED — first real PDF extraction.**  
Apache PDFBox rendered a representative page and `QuestionExtractor` cropped the selected area to a PNG. The successful first extraction proved that original PDF appearance could be reproduced without retyping content.

**DECIDED — generated PNGs belong under generated/target output, not source control.**

## 5. 18–20 August 2026 — `QuestionRegion` and multi-region extraction mature

**SUPERSEDED — one page number on `Question`.**  
A single page reference was insufficient for questions occupying part of a page, several areas or multiple pages.

**IMPLEMENTED — `QuestionRegion` introduced.**  
A question changed to an ordered list of regions.

**SUPERSEDED — pixel coordinates.**  
The initial rectangle used rendered pixels. This was replaced by normalized proportional `x`, `y`, `width`, `height` coordinates so stored regions remain stable when render DPI changes.

**DECIDED / CURRENT — page numbers are one-based in the domain.**  
Conversion to PDFBox's zero-based page indexing occurs only at the PDF boundary.

**IMPLEMENTED — stronger `QuestionRegion` validation.**  
The region model validates page number, finite coordinates, positive dimensions and normalized bounds. Documentation also records top-left origin, crop/rotation handling and region-list ordering.

**TEST / RESULT — multi-page region assembly.**  
An early implementation overwrote the first region because multiple crops were written to the same file. The extractor was changed to collect all crops, vertically combine them in order and write one assembled result. Single- and multi-region questions were verified.

**IMPLEMENTED — `HtmlQuestionRenderer`.**  
The first complete pipeline was proven:

```text
source PDF
 -> QuestionRegion(s)
 -> reconstructed question image
 -> HTML output
```

Commit `a3177efe` records `Implement PDF question extraction and HTML rendering`.

**IMPLEMENTED — tests added early.**  
Commit `6e162bbe` added JUnit coverage for `QuestionRegion`, `Question` and `PdfStore`.

## 6. 19–20 August 2026 — PDF lifecycle and JavaFX selection round-trip

**DECIDED — JavaFX should not manipulate PDFBox directly.**  
`PdfSession` was introduced to own PDF lifecycle, page count, one-based rendering and resource closing. `QuestionExtractor` was refactored to use it and return `BufferedImage` from core operations.

**IMPLEMENTED — application configuration boundary.**  
An early `ApplicationConfig` loaded a `questionbank.properties` file and supplied the PDF data root. The configuration model evolved later into a single `data.root`.

**IMPLEMENTED — JavaFX viewer.**  
The first UI loaded a PDF, rendered pages and navigated Previous/Next.

**SUPERSEDED — unnamed-module JavaFX workaround.**  
A non-modular launcher workaround produced unsupported-JavaFX warnings. The project was made properly modular with module name `au.edu.eq.questionbank`; the Eclipse project name remained `exam-question-bank`.

**IMPLEMENTED — draggable region overlay.**  
Mouse selection was corrected through several scene-graph/event issues until a visible rectangle produced a normalized `QuestionRegion`.

**TEST / RESULT — full coordinate round-trip.**  
On mouse release the normalized region was sent through PDFBox extraction and shown directly in JavaFX without a temporary PNG:

```text
JavaFX selection
 -> normalized QuestionRegion
 -> PDFBox extraction
 -> JavaFX preview
```

**BRANCH — `feature/pdf-region-selection`.**

**IMPLEMENTED — multi-region authoring workflow.**  
Accepted regions accumulated in order, were shown individually, could be removed/renumbered and could be combined for preview during this phase. Commit `d9148f7f` records `Add multi region selection controls`.

**TEST / RESULT — module-aware test configuration fixed.**  
After modularisation, JUnit reflection initially failed. Maven Surefire `--add-opens` settings were corrected; Maven and Eclipse suites returned green.

**BRANCH — `feature/question-metadata` followed the region-selection merge.**

## 7. 20–21 August 2026 — versioned curriculum enters the application

**IMPLEMENTED — versioned curriculum domain model.**  
Commit `3cc705a8` (`Add versioned curriculum domain model`) introduced explicit curriculum versions.

**IMPLEMENTED — in-memory curriculum repository.**  
Commit `4daeb6af` added the first repository implementation while persistence design was still deliberately deferred.

**IMPLEMENTED — curriculum Excel import.**  
Commit `15f31f11` added curriculum spreadsheet import/node construction. Commit `1a4cf91c` verified real Chemistry curriculum imports.

**IMPLEMENTED — Chemistry 2019 and 2025 data.**  
The repository contains both curriculum workbooks.

**IMPLEMENTED — early 2019 -> 2025 mapping data.**  
Commit `b31f8266` (`Curriculum mapping 2019 to 2025 added`) established the first application mapping capability.

**IMPLEMENTED — curriculum selection in the JavaFX capture UI.**  
By 21 August, subtopic-level selection and validation were working.

**DECIDED — questions should not merely store free-text `unit/topic/descriptor` strings.**  
Curriculum nodes belong to explicit syllabus versions and should be referenced as domain data.

**EARLY DECISION / NOT CURRENTLY IMPLEMENTED — direct multi-descriptor classification.**  
The initial Neap classification exercise and metadata-design discussion treated `Question <-> SyllabusDescriptor` as potentially many-to-many because one question may address several descriptors. The current `Question` model later adopted one best-fit original classification. No project-chat evidence establishes that the earlier multi-descriptor requirement was deliberately rejected; this remains a documented design gap for future review.

## 8. 21–26 August 2026 — question/answer capture becomes a coherent application slice

**BRANCH — `feature/question-answers`.**

**IMPLEMENTED — question capture metadata and save workflow.**  
The JavaFX slice supported multiple question regions, current-selection preview, accepted-region previews, removal, question code, curriculum classification and save workflow.

**IMPLEMENTED — answer domain.**  
`Answer`, `AnswerFile` and `AnswerRegion` were added. Answers could be text-only, region-only or both.

**DECIDED — SQLite was still deferred until capture/domain/UI responsibilities stabilised.**  
The application intentionally continued with an in-memory repository while the capture aggregate and UI boundaries were clarified.

**BRANCH — `refactor/application-structure` from `feature/question-answers` at commit `3b47f54`.**

**IMPLEMENTED — composition-root/UI refactor before persistence.**  
`QuestionBankApplication` was reduced and responsibilities moved into components including `QuestionCapturePane`, `AnswerCapturePane`, `ExamMetadataPane`, `PdfWorkspacePane`, `PdfFilePicker`, `SelectedPdf`, validators and curriculum-selection helpers.

**IMPORTANT REASON.**  
Persistence should not be built around a monolithic JavaFX class or transient control structure.

**IMPLEMENTED — TestFX workflow coverage.**  
The first broad refactor run reported 178 tests passing with no failures/errors/skips.

**REGRESSION FOUND AND FIXED — page change left answer actions enabled.**  
`clearCurrentSelectionForPageChange()` was corrected and TestFX coverage added.

**SUPERSEDED — Answer Undo workflow.**  
Question and Answer capture were standardised around current selection, Add/Clear, an accepted-region list and per-region Remove actions.

**SUPERSEDED — separate combined Question preview area.**  
The final capture interaction retained the current-selection preview and accepted-region list without a permanently separate combined preview area.

**IMPLEMENTED — dynamic accepted-region areas and answer thumbnails.**  
The accepted list hides when empty, grows with content and scrolls after a cap. Answer current-selection preview and accepted-region thumbnails were added and manually verified.

**IMPLEMENTED — Windows 125% scaling usability fix.**  
A native-window-edge dead zone affecting PDF navigation controls on a laptop was isolated and fixed by increasing bottom control padding. Temporary diagnostics were removed after manual verification.

**COMMITS MENTIONED — `Unify question and answer region previews`; `Clear answer preview after accepting region`.**

**DECIDED — minor UI polish was deferred.**  
Spacing/alignment/visual consistency work should not block persistence unless it causes incorrect data or makes essential controls unusable.

## 9. 26–31 August 2026 — SQLite becomes the runtime persistence layer

**IMPLEMENTED — SQLite connection.**  
Commit `889fa15c` (`Verify SQLite connection`) established database access.

**IMPLEMENTED — foreign-key enforcement.**  
Commit `2e989da3` enabled SQLite foreign keys.

**IMPLEMENTED / CURRENT — SQLite runtime.**  
Commit `779c22f8` (`Add SQLite curriculum runtime and UI import`) moved curriculum runtime storage into SQLite.

**IMPLEMENTED — complete question and answer persistence.**  
Commit `e64c8691` records completion of question/answer persistence.

**IMPLEMENTED — transactional schema migrations.**  
Commit `8fbf72cb` added transactional migration support.

**SUPERSEDED — in-memory repository as runtime storage.**  
In-memory repositories remain useful for tests/prototyping but are not the live persistence architecture.

**SUPERSEDED — Excel as live operational storage.**  
Excel remains an import/exchange format only.

## 10. Late August 2026 — application menu, viewer mode and managed data root

**BRANCH — `feature/menu-bar`.**

**IMPLEMENTED — main application menus.**  
The application gained File/Open PDF, Close PDF, Options and Exit; Exam Import; Curriculum Import and Review Mappings; Export placeholder; About and Version Information.

**IMPLEMENTED / CURRENT — arbitrary PDF viewer mode.**  
A user may open a PDF outside the managed data root purely for viewing. Viewer mode does not import it, does not change the active exam and disables region capture. Closing viewer mode restores the prior managed exam/answer PDF and page where applicable.

**IMPLEMENTED / CURRENT — one `data.root` configuration.**  
The application derives managed `pdf/`, `curriculum/` and `questionbank.db` locations from one root. Legacy three-property configuration remains readable. Options can change the data root, taking effect after restart.

**IMPLEMENTED — version information UI.**  
Help -> Version Information reports application/build/runtime details including SQLite and schema version.

**IMPLEMENTED — Exam Import owns its Subject.**  
The modal Exam Import dialog selects subject and exam metadata. External PDFs may be selected and are copied into the managed PDF root before persistence. Successful Set Exam synchronises Classification to the subject/current syllabus.

**SUPERSEDED — Classification subject as the direct owner of Exam metadata.**  
Exam subject is authoritative. If the user later changes Classification to an incompatible subject, the active exam/booklet is invalidated rather than silently retaining an inconsistent combination.

**TEST / RESULT — workflow tests returned green after modal-dialog test refactoring and subject invalidation restoration.**

## 11. 31 August–1 September 2026 — syllabus version selection, mapping persistence and package restructure

**IMPLEMENTED — syllabus-version selection.**  
The `feature/syllabus-version-selection` branch was reviewed/merged on 31 August (`3f4a6245`).

**IMPLEMENTED — curriculum mapping persistence/UI.**  
Descriptor-level mapping, review and persistence were exercised and hardened during this period.

**IMPLEMENTED / CURRENT — package reorganisation.**  
The codebase was reorganised around persistence/domain boundaries:

```text
repository.sqlite
repository.curriculum
repository.assessment
importer.curriculum
service.curriculum
ui  (kept together for this pass)
```

The broader application also has `model`, `pdf`, `output`, `admin` and other top-level responsibilities.

**DECIDED — do not widen visibility merely to force smaller packages.**  
Transactional/package-private dependencies were kept together where that better reflected real boundaries.

**DECIDED — `ExamMetadataOptionsRepository` stayed in its existing package.**  
Moving it would change the Java Preferences node and make existing saved preferences appear lost.

**TEST / RESULT — clean package-restructure verification.**  
Multiple clean Maven runs reported 379 tests, 0 failures, 0 errors and 3 pre-existing disabled Chemistry integration-test skips. Eclipse launch configuration also needed corresponding module `--add-opens` entries after the move.

**BRANCHES MENTIONED — `chore/restructure`, `main`.**  
The restructure was accidentally performed on `main` even though the branch had been created; no separate branch-only implementation history should be inferred from that name.

## 12. 1–2 September 2026 — Sprint 01: Legacy Metadata Import

Branch: `feature/legacy-metadata-import`

**IMPLEMENTED — legacy workbook import.**  
The sprint culminated in `6d3313f5` (`Complete legacy question metadata import workflow`) with later quality-gate commits.

**CONFIRMED WORKBOOK EVIDENCE — representative legacy structure.**  
The inspected workbook contained 59 rows with Year, Paper, Question, Marks, Topic, Answer and Preamble. Paper values were MCQ/1/2; question identifiers included both numbers and part codes such as `21a`; observed Chemistry Topic codes resolved to 2019 Subtopic nodes; MCQ rows contained A-D answers; written-response answer cells were blank; many rows carried `Preamble = 1`.

**DECIDED / CURRENT — normalized assessment ownership.**

```text
Subject
  -> Exam
       -> ExamBooklet
            -> Question
                 -> QuestionRegion*
                 -> optional Answer
```

A question belongs to exactly one booklet. Its exam is derived through that booklet.

**DECIDED / CURRENT — question identity is `(booklet_id, question_code)`.**  
Question codes are text.

**SUPERSEDED — earlier exam-based uniqueness uncertainty.**  
Pre-sprint discussion questioned whether `(exam_id, question_code)` was sufficient. The adopted model is booklet-based.

**IMPLEMENTED / CURRENT — legacy questions may persist with zero regions.**  
Metadata can be imported before source capture. Placeholder regions were explicitly rejected.

**IMPLEMENTED / CURRENT — historical classification is preserved.**  
The importer does not automatically translate 2019 classifications to 2025.

**IMPLEMENTED — MCQ answer letters can become text-only `Answer` objects.**  
Blank written-response answer cells do not create fake empty answers.

**SUPERSEDED — `expected_question_region_count` derived from Preamble.**  
Preamble does not imply a fixed number of question regions.

**SUPERSEDED / NOT ADOPTED — pre-sprint `QuestionPreamble` entity proposal.**  
An earlier schema discussion proposed `QuestionPreamble` and `preamble_regions`. Sprint 01 deliberately simplified the implemented model: `preambleCaptureRequired` is a capture hint, no placeholder/shared preamble entity is created during import, and selected content remains ordinary ordered regions. The final long-term shared-context representation remains a backlog decision.

**SUPERSEDED / DEFERRED — tri-state answer availability.**  
It was not required to import known MCQ letters or to represent unknown written-response answer availability.

## 13. 2–3 September 2026 — Sprint 02: Directional Curriculum Applicability

Branch: `feature/curriculum-applicability`

**IMPLEMENTED / CURRENT — mapping direction is explicit.**

```text
historical source -> current target
2019              -> 2025
```

Commit `ce3e5640` enforced the directional workflow; the sprint closed with quality-gate work including `3b46e531`.

**SUPERSEDED — direction-neutral/bidirectional mapping semantics.**

**IMPLEMENTED / CURRENT — Descriptor and Subtopic mappings.**  
Supported endpoints are same-level `DESCRIPTOR -> DESCRIPTOR` and `SUBTOPIC -> SUBTOPIC`. One-to-many mappings are valid.

**IMPLEMENTED / CURRENT — review state is authoritative.**  
Only `CONFIRMED` mappings affect applicability. Suggestions, explicit no-match reviews and unreviewed candidates do not.

**DECIDED / CURRENT — original classification remains provenance.**  
Current applicability is derived, never written back over the historical classification.

**DECIDED / CURRENT — Subtopic precision does not become invented Descriptor precision.**

## 14. 3 September 2026 — legacy capture completion improves

**IMPLEMENTED — repository-level selection of legacy questions without regions.**  
Commit `e33af4b3` added selection of capture-pending questions. Follow-up commits `9056bfb8` and `3ef1f63a` improved legacy question/answer capture usability.

**PROPOSED / BACKLOG — full capture-required work queue.**  
The richer workflow should distinguish missing question regions, answer regions, both, and possible shared/preamble capture, with subject/provider/year/booklet/completion filters.

## 15. 3–4 September 2026 — Sprint 03: Question Retrieval

Branch: `feature/question-retrieval`

**IMPLEMENTED / CURRENT — current-curriculum hierarchical retrieval.**  
Subject, Unit, Topic, Subtopic and Descriptor scopes are supported.

**IMPLEMENTED / CURRENT — direct-current and confirmed-mapped historical questions are returned together.**

**IMPLEMENTED / CURRENT — hierarchy expansion is explicit.**

```text
SUBJECT
    -> all valid question-classification nodes in the single current syllabus
UNIT
    -> valid descendants beneath all Topics
TOPIC
    -> valid Descriptor-mode or Subtopic-mode descendants
SUBTOPIC
    -> that Subtopic + Descriptor children
DESCRIPTOR
    -> exact Descriptor only
```

Questions themselves remain classified to one original Subtopic or Descriptor in the current model.

**IMPLEMENTED — duplicate prevention and provenance-preserving results.**

**IMPLEMENTED / CURRENT — SQLite-backed retrieval service and asynchronous JavaFX search.**  
Stored-question previews reconstruct content from managed PDFs through `QuestionPreviewService`, `PdfStore` and `QuestionExtractor`.

**IMPLEMENTED — stale-result/lifecycle protection.**  
Commit `3ec421f8` records a search-task lifecycle fix and asynchronous curriculum loading.

**TEST / RESULT — Sprint 03 quality gate.**  
The sprint design records all work packages implemented, the automated suite green and manual exercise against real stored questions including rendered previews.

## 16. 4 September 2026 — product priority changes

**SUPERSEDED IN PRIORITY — Exam Builder as the immediate next feature.**  
Assessment assembly remains a long-term goal but is not on the near-term critical path.

**DECIDED / CURRENT — student revision export becomes the immediate product outcome.**  
The revised development order is data safety -> deterministic current-curriculum corpus -> HTML/assets -> SCORM -> QLearn.

**DECIDED — the application itself will generate the final SCORM ZIP.**  
External/manual post-processing is not the target architecture.

**PROPOSED — Sprint 04: Backup, Restore and Data Safety.**  
The design exists and is explicitly planned, not implemented.

**PROPOSED — Sprint 05: hierarchical revision corpus and static HTML/assets.**  
The early batching decisions remain relevant: source attribution should survive rebatching and web images should be derived at build time from source PDFs/regions.

**PROPOSED — Sprint 06: SCORM generation and QLearn validation.**  
Source PDFs should not normally be included in the SCORM package; portable rendered assets and ordinary relative links are the intended delivery mechanism.

## 17. 5 September 2026 — standalone Chemistry 2019 -> 2025 descriptor mapping workbook completed

**IMPLEMENTED AS A PROJECT DATA ARTIFACT, NOT ESTABLISHED AS DATABASE IMPORT.**  
Using supplied `2019.xlsx` and `2025.xlsx`, a separate `Chemistry_2019_to_2025_Descriptor_Mapping.xlsx` was produced while leaving source workbooks unchanged.

The mapping workbook contains:

- one-row-per-2019-descriptor summary;
- normalized one-to-many pairwise mapping;
- 2025 coverage/predecessor sheet;
- method/legend sheet.

**TEST / RESULT — mapping counts.**

- 98 descriptors in 2019;
- 120 descriptors in 2025;
- 57 High-confidence 2019 mappings;
- 21 Medium;
- 9 Low;
- 11 with no direct 2025 equivalent;
- 23 2025 descriptors identified with no direct 2019 predecessor.

Low/no-match rows were flagged for manual review and Medium rows for checking.

**CONFIRMED RESULT — syllabus change is structural, not merely textual.**  
Descriptors were split, combined, narrowed, expanded, removed, added and moved within the hierarchy. This validates the application's one-to-many mapping architecture and the need for explicit human review.

**PROPOSED / CURRENT DATA WORK — manual review and reconciliation.**  
The project history does not establish that this 5 September workbook has been imported into or reconciled with the application's existing SQLite mapping records. That remains future work.

## 18. Future content-source extension — clipboard/image questions

**PROPOSED / UNIMPLEMENTED.**  
A later-stage workflow may allow a teacher to use Windows Snipping Tool or another source, then paste an image from the system clipboard into the application.

The proposed direction is to treat question content as potentially text plus zero or more image attachments, rather than creating mutually exclusive “text question” and “image question” types.

Possible persistence approaches discussed were SQLite BLOB storage or application-managed image files referenced by the database. No storage choice has been adopted.

**PROPOSED — drag/drop image support and optional OCR later.**  
OCR is not required for the basic feature; it would be a separate future search/classification enhancement.

## 19. Current development discipline

**CURRENT — Git/GitHub is the implementation record.**  
Feature branches, commits, sprint documents, tests and merge-readiness review are used to distinguish implementation from discussion.

**CURRENT — ChatGPT and Codex have different project roles.**  
Architecture, schema and incremental implementation guidance has primarily been handled in project chats. Codex has been used selectively for repository-aware review, tests and large mechanical refactors. `AGENTS.md` provides durable repository guidance.

**CURRENT — sprint documents remain historical records.**  
Completed sprint files should not be rewritten to absorb later deferred work. Deferred items belong in `docs/design/backlog.md`; current priorities belong in roadmap/current-status documents.
