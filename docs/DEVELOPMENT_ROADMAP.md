# Exam Question Bank — Development Roadmap

> Reconstructed from the project planning discussions and updated to reflect the design decisions that followed.
>
> **Reference date:** 2 September 2026  
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

### 2.3 Existing PDFs are not copied into Git

Source examination PDFs are external application data.

The database should store paths **relative to a configurable data root**, rather than machine-specific absolute paths.

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

Mapping between versions should be stored explicitly rather than destroying the original classification.

---

# 3. Roadmap at a glance

| Phase | Objective | Current position |
|---|---|---|
| 1 | Project foundation and toolchain | Substantially complete |
| 2 | PDF viewing and question-region extraction | Substantially complete |
| 3 | Core question/answer model and persistence | Substantially complete |
| 4 | Curriculum model and Excel import | Substantially complete |
| 5 | Syllabus-version support and descriptor mapping | Substantially complete |
| 6 | Complete question metadata entry workflow | In progress / refinement |
| 7 | Question-bank browse, search and edit | Next major capability |
| 8 | Exam builder | Later major capability |
| 9 | HTML/PDF output and document finishing | Partial foundation exists; expand with builder |
| 10 | Legacy data migration | Incremental / as required |
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
- [x] Keep machine-specific configuration out of Git.
- [ ] Continue keeping the full test suite green before merges.

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
- [ ] Continue expanding persistence tests as fields are added.
- [ ] Decide deliberately which data belongs directly on `Question` and which belongs on related entities.
- [ ] Add edit/update workflows, not only creation.

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
- [ ] Ensure import validation gives useful error messages for malformed workbooks.
- [ ] Ensure imports are repeatable without silently duplicating data.
- [ ] Support other science subjects without code changes to the hierarchy logic.

## Excel's role

Excel is useful for:

- importing existing curriculum descriptors;
- importing data from the original application;
- preparing curriculum data outside the program;
- exchanging tabular data when convenient.

It should not become the primary runtime database again.

---

# 8. Phase 5 — Syllabus versions and curriculum mapping

## Goal

Allow old questions to remain correctly classified while a new syllabus becomes the active curriculum.

This is particularly important for the transition from the **2019 Chemistry syllabus to the 2025 Chemistry syllabus**.

## Tasks

- [x] Add syllabus/curriculum version as a first-class concept.
- [x] Allow multiple curriculum versions to coexist.
- [x] Allow a user to choose the relevant syllabus version.
- [x] Store descriptors at the bottom level of the hierarchy.
- [x] Persist mappings between curriculum versions.
- [x] Provide UI support for descriptor-level mapping.
- [x] Test and harden curriculum mapping persistence.
- [ ] Complete/verify the 2019 → 2025 Chemistry descriptor mapping dataset.
- [ ] Allow mapped classifications to assist searching across syllabus versions.
- [ ] Preserve the original classification even when a mapped classification is available.
- [ ] Decide how ambiguous mappings are represented:
  - one old descriptor → one new descriptor;
  - one → many;
  - many → one;
  - no direct equivalent.
- [ ] Record mapping confidence or notes if manual review is required.

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
- examination name/type;
- year;
- term/semester if relevant;
- source PDF;
- question number;
- total marks or marks for the question.

### Classification

- syllabus version;
- unit;
- topic;
- subtopic;
- descriptor(s).

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
- [x] Add descriptor-level curriculum handling.
- [ ] Finish the layout and UX so long entry sessions are comfortable.
- [ ] Ensure required fields are clearly distinguished from optional fields.
- [ ] Add useful validation before save.
- [ ] Make save behaviour obvious and safe.
- [ ] Clear/reset only fields that should change between consecutive questions.
- [ ] Consider "save and next" behaviour for batch entry.
- [ ] Support editing an already stored question from this workflow.
- [ ] Verify questions with multiple parts are represented cleanly rather than forcing the old Excel model onto the new design.

## Definition of done

A real exam can be processed from beginning to end without editing the database manually.

---

# 10. Phase 7 — Question-bank browse, search and edit

## Goal

Turn stored questions into a usable bank rather than merely an archive.

## Search/filter requirements

Users should eventually be able to find questions by combinations of:

- subject;
- syllabus version;
- unit;
- topic;
- subtopic;
- descriptor;
- mapped descriptor from another syllabus version;
- source examination;
- year;
- marks;
- question number;
- text/tag information if available later.

## Tasks

- [ ] Build a question-bank browser.
- [ ] Add filter controls.
- [ ] Show a preview of each candidate question.
- [ ] Allow a stored question to be opened for editing.
- [ ] Allow classification to be corrected.
- [ ] Allow source metadata to be corrected without corrupting region data.
- [ ] Allow questions to be selected for an exam/worksheet.
- [ ] Make it possible to inspect the original source examination.
- [ ] Consider saved searches/favourites only after the basic browser is effective.

## Definition of done

A teacher can answer: "Show me suitable questions for this part of the syllabus" without knowing where the original question came from.

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

## Sources likely to migrate

- subjects;
- examinations;
- source file information;
- question metadata;
- marks;
- old classification;
- question-part information;
- 2019 syllabus descriptors/classification.

## Approach

1. Inspect the old application's data model and workbook structure.
2. Identify concepts that genuinely belong in the new domain model.
3. Import data through a dedicated migration/import layer.
4. Do **not** reproduce old design limitations merely because the spreadsheet stored data that way.
5. Validate migrated records.
6. Keep migration code separate from normal runtime workflows where practical.

## Tasks

- [ ] Document the old workbook schema.
- [ ] Map old fields to new domain concepts.
- [ ] Import source examination metadata.
- [ ] Import question metadata where reliable.
- [ ] Import 2019 curriculum classifications.
- [ ] Report records that cannot be migrated automatically.
- [ ] Manually review ambiguous records.
- [ ] Retire migration code from day-to-day UI once migration is complete, while retaining it for reproducibility if useful.

---

# 14. Phase 11 — School deployment and shared use

## Goal

Make the program realistic in a school environment without requiring infrastructure that is unlikely to be available.

## Constraints already identified

- A dedicated PostgreSQL server may not be available.
- Faculty storage may be based on SharePoint or a network/shared folder.
- Multiple teachers may eventually use the bank.
- Examination PDFs may contain material that should not be committed to public/cloud source-control repositories.

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
- [ ] Suggest likely 2025 descriptors for questions classified against 2019.
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
Reliable stored Question
        ↓
Reliable metadata + curriculum classification
        ↓
Browse/search/edit question bank
        ↓
Select questions
        ↓
Exam builder
        ↓
Finished HTML/PDF export
```

Accordingly, the next major development focus should be:

1. **Finish and polish question entry**
   - make a complete question easy to enter;
   - verify answer handling;
   - verify classification;
   - verify validation and save/update behaviour.

2. **Build the question-bank browser**
   - filtering;
   - preview;
   - editing;
   - selection.

3. **Build the exam-selection model**
   - selected question list;
   - ordering;
   - total marks;
   - saved draft assessment.

4. **Build the exam document renderer**
   - numbering;
   - layout;
   - page breaks;
   - HTML;
   - PDF;
   - answer document.

5. **Then expand migration, deployment and assisted classification**
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
- classify the question to descriptor level;
- save the question;
- find the question later through the question-bank browser;
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
3. add/update tests where practical;
4. run the full Maven test suite;
5. inspect the UI manually when the change is visual;
6. commit the logical change;
7. push the branch;
8. merge only when the feature is stable;
9. update this roadmap when a phase changes materially.

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

