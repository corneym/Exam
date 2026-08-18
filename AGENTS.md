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

- `.\mvnw.cmd clean test` — compile and run the complete test suite
- `.\mvnw.cmd package` — run tests and build the JAR
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

The application has a configurable data root containing the existing exam PDF
directory hierarchy.

Source documents store paths relative to that data root.

Do not store machine-specific absolute paths in question or source-document
data.

Do not infer a PDF path solely from subject, year, or examination type; preserve
the actual relative path within the existing data hierarchy.

Treat stored relative paths as untrusted input:

- require relative paths
- normalize them
- ensure resolved paths remain within the configured data root

Never commit examination source PDFs or other restricted source material.

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

`AllTests` discovers tests recursively under `au.edu.eq.questionbank`.
New tests must follow that package structure and the `*Test.java` naming
convention so they are included in the suite.

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

Do not refactor working code merely for stylistic reasons.

For significant architectural changes, explain the reason before implementing
them unless explicitly instructed otherwise.