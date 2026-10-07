# Repository Guidelines

## Project Purpose

This project is an Exam Question Bank for school science subjects.

It is being developed initially using Chemistry, but the design must support
other science subjects such as Physics and Biology without Chemistry-specific
assumptions in the core architecture.

The central workflow is:

1. Read existing examination PDFs.
2. Identify individual question regions within those PDFs.
3. Store question metadata and source regions.
4. Allow questions to be selected and assembled into new documents.
5. Export selected questions to HTML and PDF.

A future JavaFX interface will allow exam pages to be displayed and question
regions to be selected visually. Assisted or automatic recognition of question
boundaries may be added later.

## Project Structure & Module Organization

This is a single-module Maven application.

Production code lives under:

`src/main/java/au/edu/eq/questionbank`

and is grouped by responsibility:

- `model` — domain objects and value types
- `pdf` — PDFBox rendering and question extraction
- `repository` — question lookup and persistence abstractions
- `output` — HTML/PDF document generation
- `service` — application workflows and orchestration
- `ui` — JavaFX user interface
- `Main` — current proof-of-concept application entry point

Place corresponding tests under `src/test/java` using the same package layout.

Maven output is generated in `target/` and must not be committed.

Source examination PDFs are external application data and must not be
committed to Git.

## Toolchain

Use:

- Java 25
- Maven
- JUnit 6
- Apache PDFBox
- JavaFX for the desktop GUI

Do not downgrade or replace these technologies unless explicitly instructed.

Use the Maven wrapper so contributors use the configured Maven version.

Windows commands:

- `.\mvnw.cmd clean test` — compile and run the non-UI suite
- `.\mvnw.cmd -Pheadless-ui-tests test` — run the headless JavaFX suite
- `.\mvnw.cmd -Pui-tests test` — run the JavaFX suite with a display
- `.\mvnw.cmd package` — run the non-UI suite and build the JAR
- `.\mvnw.cmd compile exec:java -Dexec.mainClass=au.edu.eq.questionbank.Main`
  — run the current proof of concept

Equivalent `./mvnw` commands may be used on Unix-like systems.

## Development Approach

Develop incrementally and keep the code understandable.

Prefer straightforward Java over unnecessary frameworks, abstraction, or
premature generalisation.

Do not introduce major architectural changes unless there is a demonstrated
need.

Maintain clear separation between:

- domain model
- PDF handling
- repositories/persistence
- output/rendering
- application services
- user interface

Do not put PDFBox-specific implementation details into the domain model.

Keep diffs focused on the requested task.

### Readability and decomposition

Treat readability and maintainability as part of correctness.

Prefer small methods with one clear responsibility over large methods that
configure, calculate, persist, render, and coordinate unrelated concerns.

When a method becomes difficult to scan or contains several distinct areas of
concern, refactor it into well-named private methods.

Avoid anonymous and nested implementation classes when a small named top-level
class would make the behaviour clearer and reusable.

Do not allow incremental feature work to continually enlarge already complex
methods. Refactor surrounding code when necessary to keep responsibilities
clear.

Do not dismiss a readability refactor merely because existing code is
functionally correct. Refactoring is appropriate when it materially improves
clarity, responsibility boundaries, testability, or future maintainability.

## Question Extraction Model

The original PDF is the authoritative source for an examination question.

A question is represented by one or more `QuestionRegion` values referring to
regions of its source PDF.

A question may:

- occupy part of one page
- occupy most or all of one page
- contain multiple regions
- span multiple PDF pages

`QuestionRegion` coordinates are proportional page coordinates rather than
rendered pixel coordinates.

For example:

- `x = 0.10` means 10% from the left edge
- `y = 0.20` means 20% from the top edge
- `width = 0.80` means 80% of page width

This keeps stored regions independent of rendering DPI and display resolution.

Page numbers in the domain model are human-readable and one-based.

Convert them to zero-based page indexes only at the PDFBox boundary.

Extracted PNG files and generated HTML/PDF files are derived artifacts. They
are not authoritative question-bank data and should normally be generated
under `target/`.

## PDF Storage

The application has one authoritative data root.

New managed Exam assets use the Subject-first hierarchy:

`subjects/<Subject>/exams/<Provider>/<Year>/<Assessment>/...`

Question-booklet PDFs and Answer PDFs belonging to an Exam share that Exam
directory.

New `SourceDocument.relativePath` values are portable paths relative to the
application data root. Do not store machine-specific absolute paths.

The historical dedicated `pdf/` root remains temporarily supported only for
existing-data compatibility and migration. Code that participates in the
transition must resolve both path generations through `PdfStore`; it must not
construct persisted PDF paths directly.

New writes must not create new legacy `pdf/...` persisted paths.

`ManagedDataLayout` owns canonical managed directory construction. Do not
reconstruct Subject, curriculum, legacy-import or Exam directory fragments in
callers.

Treat every persisted path as untrusted input:

- require a relative persisted representation;
- normalise before use;
- reject traversal or paths outside their applicable managed root;
- never use persisted path text directly for unrestricted filesystem access.

Exam metadata correction must relocate all managed Question and Answer sources
when Provider, Year or Assessment changes, and persist the replacement paths in
the same SQLite transaction as the metadata correction.

Never commit examination source PDFs or other restricted source material.

## Curriculum Storage

New curriculum assets use the Subject-first hierarchy:

`subjects/<Subject>/curriculum/<Version>/`

with:

- `workbooks/` for retained curriculum-import workbooks;
- `sources/` for authoritative syllabus source PDFs.

External files may be selected from anywhere. Successful imports or
attachments retain an application-managed copy in the owning Subject/version
directory.

New persisted curriculum source-PDF paths are relative to the application data
root.

The historical dedicated `curriculum/` root remains temporarily readable for
existing data until the migration slice rewrites those files and references.

`ManagedDataLayout` owns Subject/version directory construction. Do not
sanitise Subject or version names independently in new storage code; invalid
managed directory components must be rejected consistently by the shared
layout boundary.

A failed source-PDF metadata update must delete only the unpublished new copy
and preserve the previously authoritative file and database reference.

## Legacy Import Storage

Legacy Question Excel workbooks may be selected from any accessible location,
but successful intake first retains an application-managed copy beneath:

`subjects/<Subject>/legacy/<SyllabusVersion>/`

Preflight, structural Recheck and final metadata import all use that retained
managed copy. The external source path is no longer part of the pending import
transaction after retention succeeds.

Byte-identical same-name workbooks may reuse the existing managed file.
Different workbooks with the same filename must not overwrite each other.

The retained workbook is source/provenance material only. SQLite remains
authoritative for imported Questions, Exams, classifications, Answers, capture
state and other corpus relationships.

`ManagedDataLayout` owns the Subject and syllabus-version directory identity.
Distributed worker/coordinator packages are a separate future feature.

## Coding Style & Naming Conventions

Follow the existing Java style:

- tabs for indentation where existing files use tabs
- braces on the declaration line
- explicit imports
- one public top-level type per file
- lowercase package names
- `PascalCase` type names
- `camelCase` methods and variables
- `UPPER_SNAKE_CASE` constants

Prefer immutable domain objects.

Use records for genuine value types where appropriate.

No formatter or linter is currently assumed, so match surrounding code rather
than creating style-only diffs.

## Testing Guidelines

Use JUnit 6.

Run the Maven test suite after production-code changes.

Test classes should normally be named `*Test.java`.

`NonUITests` discovers tests recursively under `au.edu.eq.questionbank` and
excludes the `ui` tag. `UITests` discovers tests tagged `ui`; the `ui-tests`
and `headless-ui-tests` Maven profiles select that suite. Run both suites for
complete automated regression verification.
New tests must follow that package structure and the `*Test.java` naming
convention; tag JavaFX tests with `@Tag("ui")`.

Use descriptive behavior-oriented test names.

Prefer temporary directories and programmatically generated test PDFs rather
than committing binary PDF fixtures.

Important areas to test include:

- proportional coordinate handling
- invalid region coordinates
- page-number boundaries
- path normalization and containment
- questions containing one region
- questions containing multiple regions
- preservation of region order
- multi-page questions
- output generation

When fixing a defect, add or update a test where practical.

Do not distort production design merely to satisfy a poorly designed test.

## Git and Change Management

Do not commit changes unless explicitly asked.

Do not commit:

- `target/`
- generated PNG files
- generated HTML/PDF output
- local examination PDFs
- machine-specific configuration

Use short, imperative commit subjects, for example:

`Implement PDF question extraction and HTML rendering`

Keep commits scoped to one logical change.

For UI or rendered-output changes, screenshots may be useful when reviewing
changes.

Call out changes involving coordinate systems, page numbering, source-document
paths, or file formats explicitly.

## Code Review Priorities

When reviewing code, prioritize:

1. correctness
2. maintainability
3. clear responsibilities
4. testability
5. readability

Pay particular attention to:

- resource management
- path safety
- PDFBox usage
- page-number conversions
- proportional coordinate calculations
- multi-region ordering
- coupling between UI and PDF implementation
- decisions that would make visual region selection unnecessarily difficult

Avoid purely cosmetic refactors that create noisy diffs without improving the
code. Refactor working code when doing so materially improves readability,
responsibility boundaries, testability, or maintainability.

For significant architectural changes, explain the reason before implementing
them unless explicitly instructed otherwise.
