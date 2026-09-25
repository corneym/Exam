# Sprint 11 --- Release 0.1

> **Status:** PLANNED / ACTIVE DESIGN\
> **Branch:** `feature/sprint-11-release-0.1`\
> **Prepared:** 26 September 2026
>
> This document is the canonical Sprint 11 design and current-status
> record. During the sprint it should be updated as slices move from
> planned to implemented/verified. At closeout, durable history and
> architecture changes are folded into `docs/development-roadmap.md`.

## 1. Sprint objective

Move the Exam Question Bank from the completed Sprint 10 capture/output
foundation to a usable, documented, installable **version 0.1**.

Sprint 11 deliberately combines a small amount of integration hardening
with one new content-source capability, targeted UI/documentation
improvements and deployment/release work.

Bank-management expansion is not part of this sprint. Corpus Dashboard
and richer Question filtering are reserved for Sprint 12.

## 2. Starting state

Sprint 10 is complete and merged to protected `main` through pull
request #2.

Merge commit: `a3dfda7e`.

Post-merge GitHub Actions run 64: successful.

At Sprint 11 start:

-   Java/JavaFX/Maven application is operational;
-   SQLite is the runtime datastore;
-   latest supported schema version is 13;
-   Question/Answer PDF-region capture is operational;
-   historical/current curriculum mapping and retrieval are operational;
-   Revision HTML and SCORM output are operational;
-   backup/restore is operational;
-   Corpus Audit and Search are operational;
-   public API Javadoc passed strict generation at Sprint 10 closeout.

Git/GitHub remains authoritative for current branch head and CI state.

## 3. Scope and sequence

### 11.1 --- Composed integration regressions

**Status: PLANNED**

Add two integration regressions before new feature work.

#### A. SQLite -\> mappings/exclusions -\> corpus -\> HTML/SCORM

Use a temporary real SQLite database and production repository/service
boundaries to prove the persisted path works as one system.

The scenario should remain deliberately small. It should establish
historical/current curriculum data, persisted Questions, confirmed
mapping state and a Question-specific output exclusion; reopen through
real SQLite repositories; build the revision corpus; verify
included/excluded placements; and generate Revision HTML and SCORM from
that corpus.

The test is intended to protect the seams between already-tested
components, not duplicate every renderer/export assertion.

Repository inspection before Sprint 11 confirmed that the necessary
component-level fixtures and production paths already exist. This should
therefore be a test-only composition unless the regression exposes a
genuine production defect.

#### B. Pre-v13 backup -\> restore -\> migrate -\> reopen

Construct a valid populated pre-v13 database/backup, restore it through
the production restore path, then exercise normal database
initialisation/migration and reopen at the current schema.

Prefer schema v11 as the starting point so one composed scenario crosses
both recent migrations:

``` text
v11
  -> v12 question_output_exclusions
  -> v13 Shared Context physical-column rename
```

The test must respect the existing restore contract: restore preparation
verifies migration compatibility on a disposable copy; restoration
itself preserves the backed-up schema version; subsequent normal
database initialisation performs the real migration.

Verify important pre-existing data survives and the reopened database is
at the latest schema with the expected v12/v13 structures/semantics.

Again, this should be test-only unless it exposes a defect.

### 11.2 --- Clipboard / Snipping Tool Question capture

**Status: PLANNED**

Add support for capturing Question content from an image placed on the
system clipboard, with Windows Snipping Tool as the primary workflow.

Design requirements:

-   detect supported image content on the clipboard;
-   create Question content without requiring a durable source PDF;
-   persist the image as authoritative Question source content;
-   retain sufficient provenance to distinguish clipboard/image source
    from PDF-region source;
-   survive application restart/reload;
-   participate in backup/restore;
-   render consistently in Question preview, Revision HTML and SCORM;
-   preserve existing PDF-region behaviour unchanged;
-   define failure/unsupported-clipboard behaviour explicitly.

Storage representation must be designed against the live domain/schema
before implementation. Do not assume BLOB versus managed image file
without that inspection.

OCR is not part of Sprint 11.

### 11.3 --- Question Search Dialog redesign

**Status: PLANNED**

Redesign the existing Search dialog to reduce excessive vertical use
while preserving current Search/edit semantics.

Target layout:

``` text
LEFT
Question Search
Matching Questions
Question Details

RIGHT
Selected Question Classification
Revision Output Applicability
Question Preview
```

This is a layout/usability change. Do not use it to introduce richer
Search filters or redesign retrieval semantics.

Preserve existing dirty-state protection, edit transfer, classification
refinement, output-applicability behaviour, preview behaviour and
geometry handling.

Tests should verify structural/behavioural outcomes rather than brittle
pixel positions.

### 11.4 --- Application tooltips

**Status: PLANNED**

Add useful tooltips to application controls after the Sprint 11 Search
layout is stable.

Tooltips should explain purpose, consequence or non-obvious behaviour
rather than merely repeat visible labels.

This is suitable for a repository-wide assisted implementation/review
pass, followed by focused human review of ambiguous controls.

### 11.5 --- Documented Help system

**Status: PLANNED**

Establish an in-application Help system backed by maintained
documentation.

Requirements:

-   clear UI entry point;
-   documented user-facing workflows appropriate to version 0.1;
-   maintainable source rather than ad hoc hard-coded dialog text;
-   Help content and application behaviour kept aligned;
-   architecture chosen only after inspecting the existing
    documentation/resources and JavaFX application structure.

Help must include access to About, but version-source infrastructure is
handled explicitly in 11.6.

### 11.6 --- Help -\> About and authoritative version infrastructure

**Status: PLANNED**

Add Help -\> About and establish one authoritative application version
source.

Requirements:

-   About displays application version;
-   version is not independently hard-coded in the About UI;
-   packaging/release artefacts derive from the same authoritative
    version where practical;
-   tests protect version retrieval/display without depending on
    environment-specific packaging behaviour.

Sprint 11 release target is version **0.1**. The exact Maven version
representation is to be decided after inspecting the current `pom.xml`
and packaging approach.

### 11.7 --- Deployment/package build

**Status: PLANNED**

Add the Maven/POM and packaging configuration required to produce an
installable desktop application.

Investigate/use `jpackage` if it fits the live toolchain and application
layout.

Requirements include:

-   self-contained or appropriately bundled runtime strategy;
-   writable application data remains outside installed application
    files;
-   existing migration/backup safety remains intact;
-   repeatable build command;
-   installation and launch tested independently of Eclipse;
-   release/build procedure documented.

Do not treat a runnable development JAR alone as completion of this
slice.

### 11.8 --- Release 0.1

**Status: PLANNED**

Complete the first installable application release.

Release gate:

-   all Sprint 11 slices intended for 0.1 complete;
-   focused regressions green;
-   full configured Maven test suites green;
-   strict Javadoc generation green;
-   documentation aligned with implemented behaviour;
-   CI green on the final feature branch;
-   installer/package generated with the authoritative 0.1 version;
-   install and launch verified outside Eclipse;
-   basic post-install data-root/startup behaviour verified.

Protected-main merge follows the established pull-request workflow after
the final branch state is green.

## 4. Explicitly outside Sprint 11

The following are not Sprint 11 work:

-   Corpus Audit -\> Corpus Dashboard redesign;
-   richer Question Search filtering;
-   explicit resolved-but-intentionally-incomplete dispositions;
-   systematic mapping-review completion tooling;
-   speculative capture-workflow queue/productivity expansion;
-   Exam Builder;
-   printable/vector-preserving assessment/solution generation;
-   major import/reconciliation systems;
-   OCR;
-   multi-page Shared Context capture.

Corpus Dashboard and richer Question filtering are reserved for Sprint
12.

The remaining uncertain ideas belong in `docs/design/backlog.md` under
low-priority/some-day-maybe work.

Multi-page Shared Context is not backlog: it is a rejected design
direction.

## 5. Testing approach

Testing remains part of each slice rather than a final clean-up phase.

Implementation proceeds in small testable slices. Run focused tests
after each change. Broader non-UI/UI suites are checkpoints rather than
the default after every edit.

TestFX workflow tests must follow the deterministic control-activation
and modal-dialog rules in `docs/Working-Instructions.md`.

A passing test is evidence only for behaviour it actually exercises.

## 6. Documentation during Sprint 11

This document carries the current Sprint 11 implementation status.

When a slice completes:

-   change its status to IMPLEMENTED / VERIFIED only after relevant
    evidence exists;
-   record important design decisions and deviations from the plan;
-   record meaningful verification evidence without duplicating moving
    branch hashes unnecessarily;
-   update `docs/design/backlog.md` when work is deliberately
    deferred/rejected;
-   update `docs/development-roadmap.md` only when a durable
    architecture/history/forward-plan change occurs.

At Sprint 11 closeout, fold durable Sprint 11 history and architecture
changes into the roadmap. The Sprint 11 document then becomes the
immutable detailed final-state record, while the Sprint 12 document
becomes the current-status record.

## 7. Initial implementation order

Begin with **11.1A only**: compose the SQLite -\> mapping/exclusion -\>
corpus -\> output integration regression and run its focused test.

If green, proceed to **11.1B**.

Do not begin clipboard persistence design until 11.1 is complete or an
integration regression has exposed a defect that must first be resolved.
