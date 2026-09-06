# Sprint Design: SCORM Package Generation and QLearn Validation

## Branch

`feature/scorm-output`

## Status

**Design phase. No production implementation has begun.**

Repository baseline:

`main` and `feature/scorm-output` are both at Sprint 05 completion commit:

`15cbe8397958b64dcfc81c091983b179f8776409`

Sprint 05 static revision HTML/assets are the content input boundary for this sprint.

---

# 1. Sprint Goal

Generate a QLearn-compatible SCORM ZIP directly from the Exam Question Bank application using the existing Sprint 05 static revision export, then prove compatibility through a real QLearn import and rendering exercise.

The intended pipeline is:

```text
existing database/curriculum/questions
        ↓
existing Sprint 05 RevisionExportService
        ↓
validated static revision directory
        ↓
new SCORM packaging layer
        ↓
SCORM manifest/profile files
        ↓
package validation
        ↓
ZIP creation
        ↓
ZIP validation
        ↓
real QLearn import and browser acceptance
```

SCORM packaging is a new layer around the existing static revision output. It is not a replacement for that output.

---

# 2. Mandatory QLearn Evidence Gate

Before the SCORM profile or manifest contract is fixed, inspect the known-working QLearn SCORM ZIP supplied for this sprint.

The inspection must establish, from the package itself:

- declared SCORM profile/version;
- `imsmanifest.xml` namespaces and metadata;
- manifest `schema` / `schemaversion` values;
- organisation/item/resource structure;
- launch resource and launch `href`;
- resource type and any SCORM-specific attributes;
- whether one or several SCO/resources are used;
- whether XSD/DTD/schema-support files are included;
- whether the ZIP has `imsmanifest.xml` directly at its root;
- file declaration rules;
- directory/path conventions;
- JavaScript or SCORM API interaction, if any;
- other metadata or files that appear QLearn-specific.

Do not execute JavaScript or other active content from the reference package during inspection.

Observed features should be classified as:

1. required by the declared SCORM profile;
2. apparently required for QLearn compatibility;
3. incidental content or generator artefacts.

QLearn acceptance, not speculation, determines whether an uncertain feature is genuinely required.

No production implementation slice starts until this evidence has been incorporated into the Sprint 06 design.

---

# 3. Existing Production Boundary

Sprint 06 builds principally on:

## Static corpus

Package:

`au.edu.eq.questionbank.service.revision`

Files:

```text
RevisionCorpus.java
RevisionCorpusBuilder.java
RevisionCorpusNode.java
RevisionCorpusStatistics.java
RevisionQuestionPlacement.java
```

These remain unchanged unless QLearn exposes a concrete content defect unrelated to packaging.

## Static revision output

Package:

`au.edu.eq.questionbank.output.revision`

Files:

```text
RevisionExportService.java
RevisionExportRequest.java
RevisionExportResult.java
RevisionExportValidator.java
RevisionHtmlRenderer.java
RevisionQuestionAssetRenderer.java
RevisionAnswerAssetRenderer.java
RevisionQuestionAsset.java
RevisionAnswerAsset.java
RevisionExportProgressListener.java
```

`RevisionExportService` already supplies the useful boundary:

```text
Subject
    ↓
build corpus
    ↓
stage export
    ↓
render question/answer assets
    ↓
render HTML/CSS
    ↓
validate static output
    ↓
publish complete static directory
```

Sprint 06 should call this service rather than reproduce its corpus, PDF rendering, HTML generation or reference-validation logic.

## UI

Package:

`au.edu.eq.questionbank.ui`

Relevant files:

```text
RevisionExportDialog.java
QuestionBankApplication.java
```

The existing HTML export remains available as an independent application command.

---

# 4. Architecture Decision

Create a separate package:

`au.edu.eq.questionbank.output.scorm`

The critical separation is:

```text
RevisionExportService
        |
        | produces a fully validated static site
        v
SCORM packaging layer
        |
        v
SCORM ZIP
```

The SCORM layer should not:

- build `RevisionCorpus`;
- query curriculum mappings;
- render PDF regions;
- generate question numbering;
- decide question placement;
- reproduce HTML navigation;
- modify persisted data.

The existing static site should be treated as an opaque, already-validated content tree as far as practical.

A Sprint 06 application export may generate that site privately in a temporary workspace and delete it after the ZIP has been safely produced.

This permits both outputs to coexist:

```text
Export
    Revision HTML...
    Revision SCORM...
```

---

# 5. Proposed New Responsibilities

Exact manifest-specific names may be refined after the QLearn ZIP inspection.

Likely package:

`src/main/java/au/edu/eq/questionbank/output/scorm/`

### `ScormExportRequest`

Carries the Subject and final ZIP destination.

It should not duplicate curriculum or question configuration.

### `ScormExportResult`

Carries:

- completed ZIP path;
- existing `RevisionCorpusStatistics`;
- any small package summary that proves useful.

### `ScormManifestWriter`

Writes `imsmanifest.xml` according to the empirically established QLearn profile.

The exact namespaces, metadata, organisation/resource structure and identifiers are deliberately not fixed until the known-good package is inspected.

Use Java's XML facilities rather than hand-built XML escaping.

This is expected to require:

`src/main/java/module-info.java`

with:

```text
requires java.xml;
```

No third-party XML or ZIP dependency is expected.

### `ScormPackageValidator`

Validates the package staging tree before ZIP creation.

At minimum, independent of the final profile details:

- required manifest exists;
- manifest parses as XML;
- manifest identifiers are unique where required;
- launch target exists;
- every manifest-local file reference resolves inside the package;
- no package reference escapes the root;
- no absolute local filesystem path appears;
- no duplicate package path exists;
- expected static HTML/assets are retained;
- source PDFs have not been accidentally included.

Profile-specific validation is added after the reference ZIP establishes the contract.

### `ScormZipWriter`

Creates the ZIP from a validated package tree.

Rules:

- relative ZIP entry names only;
- `/` separators;
- deterministic entry ordering;
- no duplicate entries;
- no path traversal;
- no temporary/staging files.

Whether the manifest must sit directly at ZIP root, and whether profile-support files accompany it there, is confirmed from the known-working package before implementation.

### `ScormExportService`

Coordinates the complete operation without changing `RevisionExportService`.

Conceptual flow:

```text
validate SCORM request
        ↓
create private temporary workspace
        ↓
RevisionExportService.export(...)
        ↓
receive validated static site
        ↓
add SCORM manifest/profile files
        ↓
validate package directory
        ↓
create staging ZIP
        ↓
validate ZIP
        ↓
publish final ZIP
        ↓
clean temporary workspace
        ↓
return ScormExportResult
```

An invalid or partially written ZIP must never be presented as a successful export.

---

# 6. Manifest Design Boundary

The following are intentionally **not yet decided**:

```text
SCORM profile/version
manifest namespace set
schemaVersion value
schema/XSD files included
one SCO versus multiple SCOs
SCORM organisation hierarchy
resource grouping
launch resource attributes
SCORM-specific JavaScript/API calls
completion/tracking behaviour
identifier format
QLearn-specific metadata
```

The preferred architectural bias, subject to the reference package, is to preserve Sprint 05 navigation rather than create a second curriculum hierarchy inside SCORM.

For example, a single launch resource pointing at the existing Subject `index.html` would preserve the tested static hierarchy particularly cleanly.

However, even that manifest layout is not adopted until the known-working QLearn package has been inspected.

No score reporting, completion tracking, sequencing or SCORM runtime API code is added merely because SCORM supports it. Such functionality enters this sprint only if the QLearn evidence demonstrates that it is required for this package to import and function correctly.

---

# 7. Implementation Slices

## Slice 0 — QLearn package characterisation

No production code.

Inspect the known-working ZIP and record the empirical SCORM contract in this design.

Deliverable:

```text
QLearn package evidence
→ confirmed profile
→ confirmed manifest skeleton
→ confirmed package root/layout
→ confirmed launch behaviour
→ required support files/runtime behaviour
```

No automated application tests yet.

This slice must be agreed before Slice 1.

## Slice 1 — Manifest model/writer

Package:

`au.edu.eq.questionbank.output.scorm`

Add only the small set of classes needed to generate the empirically confirmed manifest.

Modify:

`src/main/java/module-info.java`

only if `java.xml` is required as expected.

Tests should prove:

- correct profile namespaces/metadata;
- deterministic identifiers;
- correct organisation/resource linkage;
- correct launch target;
- XML escaping;
- deterministic output for identical inputs;
- behaviour matches the relevant structure of the known-good QLearn package.

Focused test:

```bash
mvn -Dtest=ScormManifestWriterTest test
```

Stop and review results before continuing.

## Slice 2 — Package validation

Add `ScormPackageValidator`.

Tests should cover:

- valid minimal package;
- missing manifest;
- invalid XML;
- missing launch file;
- manifest reference to missing asset;
- absolute reference;
- `..` path escape;
- duplicate identifiers where prohibited;
- manifest/package file mismatch according to the confirmed QLearn contract;
- accidental source PDF inclusion if appropriate to enforce here.

Focused test:

```bash
mvn -Dtest=ScormPackageValidatorTest test
```

Stop and review results.

## Slice 3 — ZIP creation

Add the ZIP writer.

Tests should prove:

- expected package files become ZIP entries;
- entry paths are portable;
- entries are deterministic in order;
- duplicate/path-escape entries cannot be emitted;
- package root matches the known-good QLearn layout;
- no enclosing application staging directory leaks into the ZIP;
- failed ZIP creation does not produce a completed destination.

Focused tests:

```bash
mvn -Dtest=ScormZipWriterTest test
```

Stop and review results.

## Slice 4 — End-to-end SCORM export service

Add:

```text
ScormExportRequest.java
ScormExportResult.java
ScormExportService.java
```

Compose the existing `RevisionExportService`; do not replicate its implementation.

Tests should prove:

- static revision output is successfully packaged;
- `index.html`, CSS, question assets and answer assets survive unchanged;
- manifest points to real generated content;
- package validation occurs before successful publication;
- a static-rendering failure produces no SCORM ZIP;
- a manifest/package failure produces no completed ZIP;
- temporary static content and staging ZIPs are cleaned up;
- existing revision statistics reach the result.

Use the existing Sprint 05 PDF-region integration-test pattern rather than inventing a second content fixture architecture.

Focused tests:

```bash
mvn -Dtest=ScormExportServiceTest,ScormExportServiceIntegrationTest test
```

Stop and review results.

The backlog item for a new SQLite-backed Sprint 05 export integration test remains backlog work unless Sprint 06 exposes a direct reason to promote it. Sprint 06 should not silently absorb that hardening task.

## Slice 5 — JavaFX workflow

Package:

`au.edu.eq.questionbank.ui`

Likely add:

`ScormExportDialog.java`

Modify:

`QuestionBankApplication.java`

Add a separate application command such as:

```text
Export
    Revision HTML...
    Revision SCORM...
```

Workflow:

1. select Subject;
2. select destination;
3. application generates SCORM in a background `Task`;
4. prevent duplicate simultaneous SCORM export;
5. present meaningful progress;
6. report ZIP location and revision statistics;
7. display controlled failures.

Do not replace or generalise `RevisionExportDialog` merely to remove a small amount of duplication.

Focused tests:

```bash
mvn -Dtest=ScormExportDialogTest,QuestionBankApplicationWorkflowTest test
```

Stop and review results.

## Slice 6 — Real QLearn acceptance

Generate a representative Chemistry package using the application.

Import the application-created ZIP into a genuine QLearn test course.

Acceptance must establish:

- QLearn accepts the ZIP as the expected SCORM type;
- no package-structure/import error occurs;
- the intended launch page opens;
- Subject/Unit/Topic/Subtopic navigation works;
- representative Descriptor-mode content works;
- CSS loads correctly;
- question images load;
- answer-region images load;
- native answer disclosure works;
- internal links remain inside the package;
- direct-current and historical-mapped representative questions display correctly;
- no local Windows/application/data-root path is required;
- reopening/relaunching the activity still works;
- no source exam PDF is required by the package.

If QLearn fails, first compare the generated package with the known-good reference package.

Change Sprint 05 HTML only when a concrete QLearn rendering or sandbox restriction proves that static content itself must change.

Any QLearn-specific rule discovered here is recorded explicitly in the Sprint 06 design/outcome.

## Slice 7 — Final quality and merge boundary

Before merge:

- run all focused SCORM tests;
- rerun relevant Sprint 05 revision-export tests;
- run the complete Maven test suite;
- manually inspect the final application-generated ZIP;
- repeat QLearn acceptance after any compatibility change;
- push `feature/scorm-output`;
- compare it with current `main`;
- inspect the complete GitHub diff for unrelated/generated files;
- run Codex merge-readiness review;
- independently verify findings;
- correct genuine blockers;
- re-review;
- update current-status/roadmap documentation;
- record implementation deviations/outcomes without rewriting Sprint 05 history.

---

# 8. Persistence and Dependency Decisions

No SQLite schema migration is expected.

SCORM state is derived output and is not persisted in the database.

Use JDK facilities where possible:

```text
java.xml
java.util.zip
java.nio.file
```

No Maven dependency should be added merely for ZIP creation.

---

# 9. Explicit Non-Goals

Sprint 06 does not include:

- redesigning `RevisionCorpus`;
- changing retrieval/mapping semantics;
- solving multiple original classifications;
- solving shared-context/multipart persistence;
- mapping-workbook reconciliation;
- capture-required queues;
- Exam Builder;
- printable PDF output;
- clipboard image questions;
- deployment/jpackage;
- general TestFX stabilisation;
- SCORM scoring or learner analytics unless QLearn compatibility proves them necessary.

Deferred work remains in `docs/design/backlog.md`.

---

# 10. Sprint Acceptance Boundary

Sprint 06 is not complete merely because a ZIP can be created.

It is complete only when all three layers have succeeded:

```text
1. Automated package correctness
       +
2. application-created real Chemistry SCORM ZIP
       +
3. successful import, launch and navigation in QLearn
```

The real QLearn exercise is therefore a release boundary, not an optional manual smoke test.

A generated ZIP that passes local structural tests but cannot be imported and used in QLearn does not satisfy Sprint 06.