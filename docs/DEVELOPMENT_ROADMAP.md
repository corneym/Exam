# Exam Question Bank — Development Roadmap

> Reconstructed from the project planning discussions and updated to reflect the design decisions that followed.
>
> **Reference date:** 3 September 2026
> **Version: 2  
> **Suggested repository location:** `docs/DEVELOPMENT_ROADMAP.md`

---

## 1. Project goal

Build a desktop **Exam Question Bank** for school science subjects.

Chemistry is the first subject used for development, but the core design must not assume Chemistry. The same application should be capable of supporting Physics, Biology and other science subjects by changing curriculum and question-bank data rather than rewriting the application.

The eventual workflow is:

1. Open an existing examination PDF.
2. Identify one or more regions that make up a question.
3. Identify the corresponding answer / marking material where appropriate.
4. Record the source examination and question metadata.
5. Classify the question against the relevant syllabus/curriculum.
6. Store the question in a persistent question bank.
7. Search and select questions later.
8. Assemble selected questions into a new examination or worksheet.
9. Export the assembled document to HTML and PDF.

The **original examination PDF remains the authoritative source**. Extracted images and generated documents are derived output.

---

## 2. Important design decisions

These decisions should continue to guide development.

### 2.1 Science-subject neutral

The program is being developed with Chemistry data, but:

- core model classes must not contain Chemistry-specific assumptions;
- Subject, Curriculum/Syllabus Version, Unit, Topic, Subtopic and Descriptor data belong in data rather than hard-coded program logic;
- different science subjects may have different curriculum structures or numbers of descriptors;
- subject coordinators should eventually be able to manage their own subject data.

### 2.2 SQLite rather than Excel as the application database

The original program used an Excel workbook as its database.

For the new application:

- Excel remains useful as an **import format** for existing subject, examination and curriculum data;
- SQLite is the runtime persistent store;
- database schema changes should be handled through migrations;
- the application should not depend on a PostgreSQL server or other infrastructure that is unrealistic in a school environment.

### 2.3 Examination PDFs are managed application data
Source examination and marking-guide PDFs are part of the question-bank data set.

For the current development workflow:

- examination PDFs may be stored in the private Git repository so that the same managed source documents are available on development machines at home and at work;
- PDFs should be stored under the application's managed data hierarchy rather than referenced from arbitrary external locations;
- the database should store portable paths relative to the configured data/PDF root rather than machine-specific absolute paths;
- generated output, temporary files and machine-specific configuration should remain outside Git.

The application must not depend on Git for locating source documents. Git is currently a convenient mechanism for synchronising the managed development data set between machines.

If the PDF collection later becomes large enough that normal Git storage becomes impractical, alternatives such as Git LFS or another controlled shared-data mechanism can be considered without changing the application's relative-path model.

The database stores portable source-document paths relative to the configured managed PDF/data root. Imported PDFs are copied into the managed PDF hierarchy rather than being referenced by machine-specific absolute paths.

### 2.4 Questions may contain multiple regions

A question is not assumed to be one rectangular image.

A question may:

- occupy part of a page;
- occupy an entire page;
- contain several separate regions;
- continue onto another page.

The order of those regions matters.

### 2.5 Curriculum versions must coexist

The question bank needs to support more than one version of a syllabus/curriculum.

In particular, existing Chemistry questions classified using the **2019 syllabus** must remain usable while the bank moves to the **2025 syllabus**.

Mapping between versions is stored explicitly rather than destroying the original classification.

### 2.6 Historical classification and current applicability are different concepts

A question keeps the classification under which it was originally created or imported.

A historical classification may have one or more confirmed mappings to current curriculum nodes. These mappings provide **derived current applicability**; they do not rewrite the question.

Only confirmed mappings are authoritative for applicability. Suggested mappings, explicit no-match reviews and unreviewed nodes remain distinct states.

A subtopic-level mapping must not invent descriptor-level precision.

---

# 3. Roadmap at a glance

| Phase | Objective | Current position |
|---|---|---|
| 1 | Project foundation and toolchain | Substantially complete |
| 2 | PDF viewing and question-region extraction | Substantially complete |
| 3 | Core question/answer model and persistence | Substantially complete |
| 4 | Curriculum model and Excel import | Substantially complete |
| 5 | Syllabus versions, mapping and current applicability | Final quality gate before merge |
| 6 | Complete question metadata entry workflow | In progress / refinement |
| 7 | Question-bank browse, search and edit | **Likely next sprint starts here** |
| 8 | Exam builder | Later major capability |
| 9 | HTML/PDF output and document finishing | Partial foundation exists; expand with builder |
| 10 | Legacy data migration | Major import path working; refinements remain |
| 11 | Multi-user/school deployment | Later |
| 12 | Assisted classification and automation | Future enhancement |

The phases describe dependencies rather than rigid release boundaries. Small supporting pieces may be implemented earlier when they make the current vertical slice easier to test.

---

# 4. Phase 1 — Project foundation and toolchain

## Goal

Create a stable Java project that can be developed on more than one machine and changed safely.

## Tasks

- [x] Put the project under Git.
- [x] Use GitHub as the shared remote repository.
- [x] Establish a branch-based workflow for feature development.
- [x] Use Maven and the Maven wrapper.
- [x] Establish JUnit testing.
- [x] Establish JavaFX as the desktop UI technology.
- [x] Establish Apache PDFBox for PDF handling.
- [x] Separate responsibilities into packages such as:
  - `model`
  - `pdf`
  - `repository`
  - `service`
  - `output`
  - `ui`
- [x] Keep generated output and examination PDFs out of Git.
- [x] Store managed examination/answer PDFs using portable relative paths.
- [x] Allow the private repository to synchronise the managed development PDF data set between machines.
- [x] Keep machine-specific configuration out of Git.
- [ ] Continue keeping the full test suite green before merges.
- [ ] For substantial feature branches, use a merge-readiness gate covering:
  - focused code review;
  - public API/Javadoc review;
  - additional unit tests for meaningful edge cases;
  - SQLite/integration tests across persistence boundaries;
  - the complete Maven suite;
  - TestFX/UI tests where applicable.
- [ ] Replace or deliberately retire older disabled curriculum test fixtures rather than allowing them to remain indefinitely disabled.

## Ongoing rule

Avoid adding frameworks or abstractions simply because they may be useful later. Add them when a demonstrated requirement appears.

---

# 5. Phase 2 — PDF viewing and question-region extraction

## Goal

Allow an existing examination PDF to be used as the authoritative source and identify exactly which parts belong to a question.

## Tasks

- [x] Load and render examination PDF pages.
- [x] Display pages in JavaFX.
- [x] Allow a rectangular region to be selected visually.
- [x] Represent a selected area as a `QuestionRegion`.
- [x] Store region coordinates proportionally rather than as rendered pixels.
- [x] Support multiple regions for a question.
- [x] Preserve region order.
- [x] Allow an incorrectly selected region to be removed.
- [x] Support questions spanning multiple pages at the model level.
- [x] Maintain separate remembered page positions for exam and answer PDF workspaces.
- [ ] Continue polishing selection ergonomics and visual feedback.
- [ ] Test edge cases around page boundaries, resizing and coordinate conversion.
- [ ] Add assisted question-boundary recognition only if it becomes worthwhile.

## Definition of done

A user can point the application at an examination PDF, select all regions that make up a question, and recreate the question later without storing a permanent cropped image as the source of truth.

---

# 6. Phase 3 — Core question/answer model and persistence

## Goal

Move from a visual proof of concept to a real stored question bank.

## Question data should include

At minimum:

- a persistent question ID;
- source examination/document;
- source question number or label;
- ordered question regions;
- answer / marking material;
- marks;
- metadata required for classification and later searching;
- enough source information to reconstruct the question from the original PDF.

## Tasks

- [x] Define the `Question` domain model.
- [x] Define and use `QuestionRegion`.
- [x] Support one-to-many question regions.
- [x] Add answer persistence.
- [x] Add SQLite persistence.
- [x] Add database schema migrations.
- [x] Protect stored repository contents and paths from invalid input.
- [x] Support an `Exam` with multiple `ExamBooklet` source documents.
- [x] Support registered answer files at exam level and reload them from persistence.
- [ ] Continue expanding persistence tests as fields are added.
- [ ] Decide deliberately which data belongs directly on `Question` and which belongs on related entities.
- [ ] Add edit/update workflows, not only creation.
- [ ] Formalise the long-term uniqueness/identity rule for `Exam` before broad imports and editing make duplicate exam records costly.
- [ ] Revisit whole-batch transaction semantics for imports only if partial-but-valid imports prove problematic in real use.
- [ ] Add explicit export/migration protection before development data becomes irreplaceable production data.

## Design rule

Do not make generated PNG files the question bank. They are cache/output artefacts. The database plus the original PDF is the durable representation.

---

# 7. Phase 4 — Curriculum model and Excel import

## Goal

Represent curriculum classification properly and import existing structured data rather than re-entering it manually.

## Required classification

The original classification scheme was based on:

**Subject → Unit → Topic → Subtopic → Descriptor**

The descriptor is the lowest meaningful curriculum classification level.

The UI should allow a user to navigate the hierarchy while also showing descriptor text clearly enough to classify a question correctly.

## Tasks

- [x] Represent Subject.
- [x] Represent Unit.
- [x] Represent Topic.
- [x] Represent Subtopic.
- [x] Represent Descriptor.
- [x] Refactor away from an overly generic curriculum-node representation where specific types are clearer.
- [x] Import curriculum information from Excel.
- [x] Store imported curriculum data in SQLite.
- [x] Display curriculum data in the UI.
- [x] Validate the expected curriculum workbook structure and reject duplicate codes.
- [ ] Continue improving import error messages where malformed workbooks are difficult to diagnose.
- [ ] Continue ensuring imports are repeatable without silently duplicating data.
- [ ] Verify additional science subjects work without changes to hierarchy logic.

## Excel's role

Excel is useful for:

- importing existing curriculum descriptors;
- importing data from the original application;
- preparing curriculum data outside the program;
- exchanging tabular data when convenient.

It should not become the primary runtime database again.

---

# 8. Phase 5 — Syllabus versions, curriculum mapping and current applicability

## Goal

Allow old questions to remain correctly classified while a new syllabus becomes the active curriculum.

This is particularly important for the transition from the **2019 Chemistry syllabus to the 2025 Chemistry syllabus**.

The central model is:

```text
Original historical classification
            |
            v
Confirmed one-way curriculum mapping
            |
            v
Derived current applicability
```

## Completed in the curriculum-applicability sprint

- [x] Add syllabus/curriculum version as a first-class concept.
- [x] Allow multiple curriculum versions to coexist.
- [x] Allow a user to choose the relevant syllabus version.
- [x] Store descriptors at the bottom level of the hierarchy.
- [x] Persist mappings between curriculum versions.
- [x] Provide descriptor-level mapping review.
- [x] Generalise mapping review to support `DESCRIPTOR -> DESCRIPTOR` and `SUBTOPIC -> SUBTOPIC`.
- [x] Support one-to-many confirmed descriptor mappings.
- [x] Support one-to-many confirmed subtopic mappings.
- [x] Support explicit `NO_MATCH` review outcomes.
- [x] Support editing existing descriptor and subtopic reviews transactionally.
- [x] Generate subtopic candidates from confirmed descendant descriptor mappings.
- [x] Require human confirmation before inferred subtopic candidates become confirmed mappings.
- [x] Preserve historical question classification.
- [x] Add a read-only current-applicability service.
- [x] Ignore `SUGGESTED` mappings when deriving current applicability.
- [x] Ignore confirmed targets that are not in the current syllabus when deriving current applicability.
- [x] Verify applicability using both in-memory and SQLite persistence tests.
- [x] Verify a real `Question` retains its historical classification while reporting current applicability.

## Remaining sprint close-out items before merge

- [ ] Enforce the historical-source → current-target rule in persistence/service validation, not only in the review UI.
- [ ] Complete subtopic evidence reporting:
  - reviewed descriptor coverage;
  - unreviewed/incomplete coverage;
  - descendant descriptor `NO_MATCH` count.
- [ ] Add tests specifically proving incomplete subtopic evidence coverage and no-match counts.
- [ ] Run the planned Codex merge-readiness review over the full branch against `main`.
- [ ] Review/add Javadocs for new or materially changed public APIs and verify any configured Javadoc checks.
- [ ] Add any unit/integration tests identified by the review.
- [ ] Run the complete Maven and TestFX suites before merge.

## Mapping rules

- mappings are directional;
- source and target belong to the same subject;
- source and target belong to different syllabus versions;
- supported endpoint levels in this sprint are:
  - `DESCRIPTOR -> DESCRIPTOR`;
  - `SUBTOPIC -> SUBTOPIC`;
- one-to-many is valid;
- `CONFIRMED` mappings are authoritative;
- `SUGGESTED`, `NO_MATCH` and unreviewed are distinct states;
- original question classification is never rewritten merely because a mapping exists;
- a subtopic mapping does not imply any particular descriptor beneath the target subtopic;
- automatic transitive mapping across three or more syllabus generations remains out of scope.

## Remaining curriculum-data work

- [ ] Complete/verify the actual 2019 → 2025 Chemistry mapping dataset.
- [ ] Consider mapping notes/confidence metadata only if real manual review demonstrates a need.
- [ ] If a future syllabus is introduced, design 2019 → 2025 → future mapping traversal explicitly rather than assuming transitivity.
- [ ] Consider a future manual refinement workflow that can record more precise current applicability for a historical question without overwriting its original classification.

## Important rule

A mapping is an additional relationship. It must not rewrite history by pretending that an old examination question was originally written against a syllabus that did not yet exist.

---

# 9. Phase 6 — Complete question metadata entry workflow

## Goal

Make the question-capture screen efficient enough for entering a large number of real examination questions.

The user should be able to work through a PDF, create a question, classify it, save it, and continue without unnecessary navigation.

## Metadata areas

### Source examination

Likely information includes:

- subject;
- examination/provider;
- year;
- source booklet/PDF;
- question number;
- total marks or marks for the question.

### Classification

- syllabus version;
- unit;
- topic;
- subtopic;
- descriptor.

A stored question has exactly one original classification at `SUBTOPIC` or `DESCRIPTOR` level. Current applicability may later contain multiple mapped nodes.

### Question

- one or more question regions;
- question metadata;
- marks;
- potentially multiple parts/subparts where the model requires them.

### Answer

- answer/marking guide source;
- answer regions or answer content;
- unanswered/no-answer state where legitimate.

## Tasks

- [x] Create the JavaFX metadata-entry screen.
- [x] Add examination/source-document selection.
- [x] Add question-region collection.
- [x] Add answer-related fields/persistence.
- [x] Add curriculum selectors.
- [x] Add descriptor/subtopic-level curriculum handling.
- [x] Add a global queue for imported questions whose PDF regions still require capture.
- [x] Auto-advance through pending imported question capture.
- [x] Auto-advance through unanswered questions after answer capture.
- [x] Copy answer PDFs into the managed `PdfStore` hierarchy.
- [x] Reuse a single registered answer PDF for an exam when exactly one is known.
- [x] Restore persisted answer-file registration after restart.
- [ ] Finish the layout and UX so long entry sessions are comfortable.
- [ ] Ensure required fields are clearly distinguished from optional fields.
- [ ] Continue improving useful validation before save.
- [ ] Support editing an already stored question from this workflow.
- [ ] Verify questions with multiple parts are represented cleanly rather than forcing the old Excel model onto the new design.
- [ ] Add **preamble pinning/shared preamble capture** so a preamble used by several questions can be captured efficiently without duplicating unnecessary work.
- [ ] Keep the preamble-pin concept transient initially; do not persist it until a genuine persistence requirement is demonstrated.
- [ ] For ordinary non-legacy exam import, ensure source PDFs use the same managed hierarchical `PdfStore` approach as legacy-created booklets.
- [ ] Continue UI/aesthetic cleanup around Question, Answer and Exam Details areas where it improves long capture sessions.

## Definition of done

A real exam can be processed from beginning to end without editing the database manually.

---

# 10. Phase 7 — Question-bank browse, search and edit

## Goal

Turn stored questions into a usable bank rather than merely an archive.

## Likely next sprint: Curriculum-aware Question Retrieval

Once `feature/curriculum-applicability` is complete and merged, the next sprint should probably build the retrieval layer that consumes current applicability.

The sprint should be narrower than implementing the whole Phase 7 browser.

### Likely work packages

#### WP1 — Current-node question lookup

Add a repository/service boundary such as:

```java
findQuestionsApplicableTo(CurriculumNode currentNode)
```

or an equivalent query object/service API.

For a current descriptor, results should include:

```text
questions directly classified to that current descriptor
+
historical descriptor-classified questions with CONFIRMED mappings to it
```

For a current subtopic, results should include:

```text
questions directly classified to that current subtopic
+
historical subtopic-classified questions with CONFIRMED mappings to it
```

Import technique must not matter: manually captured current questions and legacy metadata-imported historical questions should participate through the same stored `Question` and curriculum relationships.

#### WP2 — Define hierarchical search semantics deliberately

Decide and test whether selecting a current subtopic should also include questions classified directly to descendant current descriptors, and historical descriptor questions that map to those descendant descriptors.

The likely useful behaviour is hierarchical inclusion, but it should be an explicit search rule rather than hidden inside curriculum mapping.

A subtopic-classified historical question must still **not** be treated as descriptor-specific merely because the target subtopic has descriptors beneath it.

#### WP3 — Persistence-efficient retrieval

- implement SQLite-backed retrieval rather than filtering the entire question bank in memory;
- include only `CONFIRMED` mappings;
- ensure `SUGGESTED` and `NO_MATCH` states cannot create results;
- prevent duplicate questions when several mappings or hierarchy paths reach the same current area;
- add indexes/query changes if real query plans justify them;
- test behaviour after constructing fresh repositories/services to represent application restart.

#### WP4 — Minimal search/filter UI

After the retrieval boundary is proven:

- choose subject/current syllabus;
- navigate Unit → Topic → Subtopic → Descriptor;
- show matching questions;
- display enough provenance to distinguish current-direct from historical-mapped questions where useful;
- show a basic question preview;
- allow opening the original source examination.

Do not make full editing, favourites, exam-builder selection or sophisticated multi-filter combinations prerequisites for the first retrieval sprint unless they fall out naturally from the implementation.

### Search/filter requirements over Phase 7

Users should eventually be able to find questions by combinations of:

- subject;
- current curriculum area;
- original syllabus version;
- unit;
- topic;
- subtopic;
- descriptor;
- mapped descriptor/subtopic from another syllabus version;
- source examination/provider;
- year;
- marks;
- question number;
- text/tag information if available later.

## Later Phase 7 tasks

- [ ] Build the full question-bank browser.
- [ ] Add broader filter combinations.
- [ ] Show a richer preview of each candidate question.
- [ ] Allow a stored question to be opened for editing.
- [ ] Allow classification/source metadata corrections without corrupting provenance or region data.
- [ ] Allow questions to be selected for an exam/worksheet.
- [ ] Consider saved searches/favourites only after the basic browser is effective.

## Definition of done

A teacher can answer: "Show me suitable questions for this part of the current syllabus" without knowing whether the original question came from the current syllabus or a historical import.

---

# 11. Phase 8 — Exam builder

## Goal

Use the question bank to build a new assessment.

This is the point where the application moves beyond question capture and retrieval into the original exam-builder purpose.

## Tasks

- [ ] Create an exam/project model separate from a stored source examination.
- [ ] Add selected questions to an exam.
- [ ] Reorder selected questions.
- [ ] Remove selected questions without deleting them from the bank.
- [ ] Track total marks.
- [ ] Allow question numbering to be generated for the new exam.
- [ ] Decide how source question numbering is displayed or suppressed.
- [ ] Support page breaks and layout control.
- [ ] Allow headings/instructions to be inserted.
- [ ] Support sections if required.
- [ ] Save an unfinished exam and reopen it.
- [ ] Produce a corresponding answer/marking document where possible.

## Later refinements

Possible later features include:

- target marks by curriculum area;
- warnings about repeated source exams;
- balance reports;
- difficulty tagging;
- duplicate/similar-question detection.

Do not make these prerequisites for the first working builder.

---

# 12. Phase 9 — Output generation

## Goal

Produce documents that are practical for classroom use.

## Tasks

- [x] Establish HTML/PDF output as an architectural responsibility.
- [ ] Render selected question regions in exam order.
- [ ] Produce clean HTML output.
- [ ] Produce clean PDF output.
- [ ] Handle multi-region and multi-page questions correctly.
- [ ] Handle page breaks deliberately.
- [ ] Add exam title/header information.
- [ ] Add question numbering.
- [ ] Add marks display.
- [ ] Generate an answer/marking version.
- [ ] Verify print quality at normal school printer resolutions.
- [ ] Keep generated output under a generated-output location such as `target/`, unless the user explicitly exports elsewhere.

## Definition of done

An assessment built entirely inside the application can be exported and printed without manual reconstruction in another program.

---

# 13. Phase 10 — Legacy data migration

## Goal

Recover useful work from the original Excel-based Exam Builder without recreating everything by hand.

## Current status

A substantial legacy import path now exists.

It can parse the legacy workbook, validate it before writes, derive missing exam/booklet requirements, create required `Exam`/`ExamBooklet` records from selected PDFs, import question metadata and preserve historical curriculum classification.

Legacy imported questions may initially have zero question regions. This is an intentional **capture pending** state, not invalid data.

## Tasks

- [x] Inspect/document the relevant old workbook schema.
- [x] Map the important old fields to new domain concepts.
- [x] Import source exam/booklet metadata where the workbook plus user-selected PDFs can identify it reliably.
- [x] Import question metadata where reliable.
- [x] Import 2019 curriculum classifications without rewriting them to 2025.
- [x] Preserve MCQ answer letters when available.
- [x] Preserve preamble-required metadata for later capture.
- [x] Keep import transactional/idempotent at the question level and reject conflicts.
- [x] Copy selected source PDFs into the managed hierarchy rather than retaining arbitrary external paths.
- [ ] Allow an optional answer/marking PDF to be supplied during legacy import alongside the question booklet PDFs.
- [ ] Remember the last legacy-workbook chooser directory for the current application session.
- [ ] Continue reporting records that cannot be migrated automatically.
- [ ] Manually review genuinely ambiguous records.
- [ ] Decide whether whole-workbook atomicity is needed after real migration experience; do not add it only for theoretical purity.
- [ ] Retire migration UI from day-to-day workflows once migration is complete while keeping reproducible import support if useful.

---

# 14. Phase 11 — School deployment and shared use

## Goal

Make the program realistic in a school environment without requiring infrastructure that is unlikely to be available.

## Constraints already identified

* A dedicated PostgreSQL server may not be available.
* Faculty storage may be based on SharePoint or a network/shared folder.
* Multiple teachers may eventually use the bank.
* Examination PDFs and marking materials may contain material that requires controlled access.
* The current private Git repository may be used to synchronise development source PDFs between trusted development machines, but this should not be assumed to be the eventual school-wide document-distribution mechanism.
* Source PDFs must not be placed in a public repository or otherwise exposed beyond the access permitted for the examination material.


## Questions to resolve later

- Is one shared SQLite file safe enough for the expected pattern of access?
- Should each user have a local working database with an import/export/synchronisation mechanism?
- Can school IT provide a suitable shared service?
- Can SharePoint safely host the data files, and what are its locking/synchronisation consequences?
- How are source PDF paths made portable across school machines?
- What is the backup strategy?

## Tasks

- [ ] Establish the realistic number of simultaneous users.
- [ ] Test SQLite behaviour on the intended shared storage.
- [ ] Discuss available hosting/storage options with school IT if needed.
- [ ] Establish backup and restore procedures.
- [ ] Package the Java application for straightforward installation.
- [ ] Document configuration of the examination data root.
- [ ] Add data export/backup capability before widespread use.

Do not solve this prematurely. A robust single-user application with clean persistence boundaries should come first.

---

# 15. Phase 12 — Assisted classification and automation

## Goal

Reduce manual work once the underlying data and workflows are reliable.

These are enhancements, not prerequisites for the core question bank.

## Possible features

- [ ] Suggest curriculum descriptors from question content.
- [ ] Use descriptor text plus Unit/Topic/Subtopic context as classifier input.
- [ ] Suggest likely current descriptors for historical questions, while keeping suggestions reviewable.
- [ ] Detect likely question boundaries on PDF pages.
- [ ] Extract question text with OCR/text-layer analysis where useful.
- [ ] Detect duplicate or near-duplicate questions.
- [ ] Suggest marks/difficulty metadata.
- [ ] Generate question-bank coverage reports.
- [ ] Suggest questions to fill gaps in an exam blueprint.

## Rule

Automation should **suggest** where uncertainty exists. The stored curriculum classification should remain reviewable and editable by a teacher.

---

# 16. Recommended development order from the current point

The important dependency chain is:

```text
Finish curriculum-applicability quality gate
        ↓
Reliable current-applicability query
        ↓
Curriculum-aware question retrieval
        ↓
Question-bank browser / edit workflow
        ↓
Select questions
        ↓
Exam builder
        ↓
Finished HTML/PDF export
```

Accordingly, the recommended order is:

1. **Close and merge `feature/curriculum-applicability`**
   - persistence-level direction enforcement;
   - complete subtopic evidence reporting;
   - Javadocs;
   - missing unit/integration tests;
   - Codex review;
   - full Maven/TestFX quality gate.

2. **Run a Curriculum-aware Question Retrieval sprint**
   - `findQuestionsApplicableTo(...)` or equivalent;
   - current-direct plus confirmed historical mapping results;
   - explicit hierarchical subtopic search semantics;
   - SQLite integration and duplicate prevention;
   - minimal search/filter/preview UI.

3. **Expand the question-bank browser**
   - broader filtering;
   - preview;
   - editing/correction;
   - source inspection;
   - selection.

4. **Build the exam-selection model**
   - selected question list;
   - ordering;
   - total marks;
   - saved draft assessment.

5. **Build the exam document renderer**
   - numbering;
   - layout;
   - page breaks;
   - HTML;
   - PDF;
   - answer document.

6. **Continue capture/import refinements as bounded supporting work**
   - preamble pinning;
   - legacy answer/marking PDF import;
   - chooser-directory memory;
   - ordinary exam PDF-store consistency.

7. **Then expand migration, deployment and assisted classification**
   as real usage reveals the highest-value improvements.

---

# 17. Minimum viable product (MVP)

The project has reached a useful first release when a teacher can:

- configure the folder containing examination PDFs;
- import curriculum data;
- select the appropriate subject and syllabus version;
- open an existing examination;
- identify all regions belonging to a question;
- associate answer/marking material;
- enter marks and source metadata;
- classify the question;
- save the question;
- find the question later by current curriculum applicability;
- select several stored questions;
- arrange them into a new assessment;
- export a printable question paper;
- export a corresponding answer/marking document.

Anything beyond this can be judged against a simple question:

> Does it make question capture, retrieval, assessment construction or maintenance materially better?

If not, it can wait.

---

# 18. Development discipline

For each reasonably self-contained change:

1. start from an up-to-date branch;
2. make one focused change;
3. add/update focused unit tests;
4. add integration tests when behaviour crosses SQLite/repository/service boundaries;
5. review public API Javadocs when introducing or materially changing public classes/methods;
6. run the full Maven test suite;
7. run TestFX/UI checks where the change affects UI behaviour;
8. inspect the UI manually when the change is visual;
9. commit the logical change;
10. push the branch;
11. for substantial branches, perform a pre-merge code review;
12. merge only when the feature is stable;
13. update this roadmap when a phase changes materially.

Useful test command on Windows:

```powershell
.\mvnw.cmd clean test
```

The roadmap itself should be version-controlled so it evolves with the program.

---

# 19. Things deliberately deferred

These ideas may be valuable, but they should not distract from the main dependency chain:

- automatic question segmentation;
- AI classification;
- OCR-heavy processing;
- sophisticated analytics;
- cloud/server architecture;
- multi-user concurrency;
- elaborate exam balancing;
- difficulty prediction;
- saved search/favourite systems;
- automatic transitive curriculum mapping across multiple generations;
- fully automatic migration of every legacy edge case.

The best order remains:

**capture reliably → classify reliably → retrieve reliably → build exams → automate repetitive work.**

---

# 20. Roadmap maintenance

When a significant feature is completed:

- change its checkbox;
- update the phase status in the summary table;
- add newly discovered work under the relevant phase;
- avoid turning the roadmap into a daily task log;
- use GitHub commits/issues or a separate task list for very small implementation steps.

This document should answer two questions quickly:

1. **What is this project ultimately trying to do?**
2. **What should I work on next, and why?**
