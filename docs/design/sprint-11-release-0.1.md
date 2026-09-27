# Sprint 11 --- Release 0.1

> **Status:** IMPLEMENTED / VERIFIED --- final branch CI and protected-main merge pending\
> **Branch:** `feature/sprint-11-release-0.1`\
> **Prepared:** 26 September 2026\
> **Closeout updated:** 27 September 2026
>
> This document is the detailed Sprint 11 final-state record. Durable
> architecture and forward-plan changes are also reflected in
> `docs/development-roadmap.md`.

## 1. Sprint objective

Move the Exam Question Bank from the completed Sprint 10 capture/output
foundation to a usable, documented, installable **version 0.1**.

The implemented sprint combines integration hardening, clipboard image
Question capture, targeted Search/UI improvements, maintained in-application
Help, authoritative version infrastructure and a repeatable Windows release
pipeline.

Bank-management expansion remains outside this sprint. Corpus Dashboard and
richer Question filtering remain reserved for Sprint 12.

## 2. Starting state

Sprint 10 was complete and merged to protected `main` through pull request #2.

Merge commit: `a3dfda7e`.

Post-merge GitHub Actions run 64: successful.

At Sprint 11 start:

- Java/JavaFX/Maven application was operational;
- SQLite was the runtime datastore;
- latest supported schema version was 13;
- Question/Answer PDF-region capture was operational;
- historical/current curriculum mapping and retrieval were operational;
- Revision HTML and SCORM output were operational;
- backup/restore was operational;
- Corpus Audit and Search were operational;
- public API Javadoc passed strict generation at Sprint 10 closeout.

Git/GitHub remains authoritative for moving branch heads and CI state.

## 3. Implemented scope

### 11.1 --- Composed integration regressions

**Status: IMPLEMENTED / VERIFIED**

Two cross-boundary regressions were added before feature work.

#### A. SQLite -> mappings/exclusions -> corpus -> HTML/SCORM

`SqliteRevisionOutputPipelineIntegrationTest` builds a real temporary SQLite
fixture containing historical/current curriculum, a persisted Question,
confirmed one-to-many mapping and one Question-specific output exclusion. It
reopens the database through production repositories, proves retrieval retains
both current applicability placements, then proves the persisted exclusion
removes only the intended revision-output placement.

The same corpus is exported through production Revision HTML and SCORM services.
The regression verifies the included Descriptor is represented and the excluded
Descriptor is absent from generated output.

No production defect was required to implement this regression.

#### B. Pre-v13 backup -> restore -> migrate -> reopen

`DefaultRestoreExecutorTest#restoresVersion11BackupThenMigratesAndReopensAtLatestSchema`
constructs a valid schema-v11 database and backup, prepares and applies the
restore through production restore services, verifies the restored database
remains at v11 until normal application initialisation, then performs the real
sequential migration and reopens the result.

The regression now reaches schema **v14** and verifies:

- pre-existing data survives;
- v12 `question_output_exclusions` exists;
- v13 Shared Context physical-column renames are present and retain values;
- v14 `question_images` and `question_content_parts` exist;
- final schema and integrity verification pass.

### 11.2 --- Clipboard / Snipping Tool Question capture

**Status: IMPLEMENTED / VERIFIED**

Question content now supports an authoritative ordered sequence of mixed content
parts:

``` text
PDF_REGION
IMAGE
```

Schema v14 adds:

- `question_images`, storing authoritative PNG image bytes as SQLite BLOBs;
- `question_content_parts`, storing the authoritative assembly order across
  PDF regions and pasted images.

Existing PDF-only Questions are migrated by creating one `PDF_REGION` content
part for each existing `question_regions` row in its existing order.

Implemented domain/runtime boundaries include:

- `QuestionContentPart`;
- `PdfQuestionContentPart`;
- `ImageQuestionContentPart`;
- `QuestionContentRenderer`;
- `QuestionClipboardImageReader`;
- SQLite writer/repository/capture-service support for mixed content.

Question capture can paste supported image content from the system clipboard,
including Windows Snipping Tool images. Clipboard images can be interleaved with
PDF regions in capture order. Persisted order is authoritative and survives
database reload.

Preview, Revision HTML and SCORM use the mixed-content rendering path. Database
backup/restore naturally includes image BLOBs because they are part of the
SQLite database.

`LegacyClipboardCaptureWorkflowIntegrationTest` verifies a real imported legacy
Question can be completed with an image followed by a PDF region, saved,
reloaded, removed from the unresolved capture queue and rendered in the same
authoritative order.

OCR is not implemented.

### 11.3 --- Question Search Dialog redesign

**Status: IMPLEMENTED / VERIFIED**

Question Search was redesigned into a two-column working layout that reduces
vertical growth while retaining the existing Search/edit semantics.

The left side carries Search, matching Questions and Question details. The right
side carries selected-Question classification, Revision Output Applicability
and preview.

The redesign preserved:

- current Search/retrieval semantics;
- dirty-state protection;
- selected-Question edit transfer;
- classification refinement;
- Question-specific output-applicability editing;
- preview behaviour;
- geometry persistence/hardening.

Search implementation was also decomposed with focused helper types including
`BackgroundTask`, `DisplayListCell`, `QuestionClassificationPath`,
`QuestionOutputApplicabilityRow` and `SubjectNavigation`.

Richer Search filtering was deliberately not added and remains Sprint 12 work.

### 11.4 --- Application tooltips

**Status: IMPLEMENTED / VERIFIED**

Purpose/consequence tooltips were added across the main capture, curriculum,
audit, export, PDF and Search surfaces after the Search layout stabilised.

Tooltips were kept focused on non-obvious purpose or consequence rather than
repeating visible labels. Focused UI regressions cover representative controls.

### 11.5 --- Documented Help system

**Status: IMPLEMENTED / VERIFIED**

A maintained in-application Help system was added using packaged HTML/CSS
resources displayed in JavaFX `WebView`.

Implemented resources cover:

- Help index/navigation;
- getting started;
- Question capture;
- Question Search;
- curriculum;
- revision output;
- data safety.

`HelpDialog` loads packaged `/au/edu/eq/questionbank/help/index.html`, is owned
by the application window, resizable and independently closable.

JavaFX WebView support was added through `javafx-web` and module configuration.

As part of the same maintainability work, large output CSS blocks were removed
from Java source:

- Revision HTML uses packaged `output/revision/revision.css`;
- single-file Question HTML loads packaged `output/question/question.css` and
  embeds it into the generated document.

`HelpResourcesTest`, `HelpDialogTest`, lifecycle Help-menu coverage and output
CSS regressions verify the packaged resources and entry points.

### 11.6 --- Help -> About and authoritative version infrastructure

**Status: IMPLEMENTED / VERIFIED**

Maven project version **0.1** is the authoritative application version.

`src/main/resources/au/edu/eq/questionbank/application.properties` is Maven
filtered during the build and receives:

``` text
application.version=${project.version}
```

`ApplicationVersion.current()` loads and validates the packaged value.
Application About, Version Information, backup metadata and packaging therefore
share the same Maven-derived release identity rather than independent hard-coded
versions.

The Help menu provides:

- Help Contents;
- About;
- Version Information.

About displays the application description and authoritative version.
Version Information also reports Java, JavaFX, operating system, SQLite and
database schema information.

`ApplicationVersionTest` and application lifecycle UI regressions protect
version retrieval/display.

### 11.7 --- Deployment/package build

**Status: IMPLEMENTED / VERIFIED**

Windows packaging is implemented through three PowerShell scripts:

- `scripts/package-windows-app-image.ps1`;
- `scripts/package-windows-installer.ps1`;
- `scripts/build-release.ps1`.

#### Application image

`package-windows-app-image.ps1`:

- reads artifact/version metadata from `pom.xml`;
- requires Java 25 `jpackage`;
- runs a clean Maven package build with tests skipped because release testing is
  a separate gate;
- collects runtime dependencies;
- packages `au.edu.eq.questionbank.Launcher` as a class-path application;
- creates a private Java runtime;
- strips native launcher commands, debug data, man pages and headers;
- retries removal of a previously launched app image when Windows retains a
  short-lived executable handle.

The generated runtime was verified independently of Eclipse. The bundled
`runtime/release` reports Java 25.0.4 and the private JVM library is present.

#### MSI installer

`package-windows-installer.ps1` always rebuilds a fresh app image before creating
the MSI.

The MSI is:

- per-user;
- added to the Windows Start menu;
- versioned from the Maven project version;
- built with a fixed Windows Installer upgrade UUID
  `29eeeeb7-cbe8-5d98-a67f-36240572d76c`.

That UUID is release infrastructure and must remain unchanged for future
upgrades.

The tested packaging toolchain is Java 25 `jpackage` plus WiX Toolset 7.0.0 with
`WixToolset.Util.wixext` 7.0.0 available.

#### Writable configuration/data separation

Installed application files and user-owned mutable state are deliberately
separate.

On Windows:

``` text
Configuration:
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties

Default data root:
%LOCALAPPDATA%\Exam Question Bank Data\data
```

The sibling `Exam Question Bank Data` directory is intentionally distinct from
the per-user jpackage installation directory. This was introduced after an MSI
uninstall test exposed that storing configuration under the installer-owned
product directory allowed uninstall to remove the configuration file.

`ApplicationPaths` owns these locations. `ApplicationConfig.loadOrCreate(...)`
loads the user-specific configuration, migrates a legacy working-directory
`questionbank.properties` when appropriate, or creates a first-run configuration
and default data directories.

After the fix, install/launch/uninstall testing verified the configuration file
and configured external data root survive MSI uninstall.

### 11.8 --- Release 0.1

**Status: IMPLEMENTED / VERIFIED LOCALLY --- final CI/PR merge pending**

`build-release.ps1` is the release gate.

Version rules:

- accepted release form is `major.minor`;
- leading zeroes are rejected except the literal `0`;
- an explicit `-Version` selects a release version;
- without `-Version`, the normal release path increments the current minor
  version automatically;
- a failed release restores `pom.xml` if the script changed the version;
- a real release requires a clean Git working tree;
- `-AllowDirty` exists for release-script development/validation only.

The Release 0.1 candidate was built explicitly with:

``` powershell
.\scripts\build-release.ps1 -AllowDirty -Version 0.1
```

The complete gate passed:

1. Spotless source-format check;
2. non-UI Maven suite;
3. headless UI Maven suite;
4. strict Javadoc generation with doclint and warnings treated as failures;
5. Windows app-image/MSI generation.

Release test gates use Surefire first-failure stopping so an already-invalid
candidate does not continue through the rest of a large suite.

During release-gate validation, one suite-only TestFX race was found in
`AnswerCaptureWorkflowTest`. The test was changed to use the shared
native-window/DialogPane helper rather than traversing the scene graph while a
modal dialog was being constructed. The focused class and full headless UI suite
then passed.

The generated `Exam Question Bank-0.1.msi` was installed independently of
Eclipse, launched from the Start menu and manually verified for:

- existing question-bank data;
- Help;
- About/version 0.1;
- normal shutdown.

The exact MSI was then uninstalled and the user configuration was verified to
remain present with its existing `data.root`.

The remaining closeout step is final feature-branch CI followed by the
protected-main pull-request merge.

## 4. Explicitly outside Sprint 11

The following remain outside Sprint 11:

- Corpus Audit -> Corpus Dashboard redesign;
- richer Question Search filtering;
- explicit resolved-but-intentionally-incomplete dispositions;
- systematic mapping-review completion tooling;
- speculative capture-workflow queue/productivity expansion;
- Exam Builder;
- printable/vector-preserving assessment/solution generation;
- major import/reconciliation systems;
- OCR;
- multi-page Shared Context capture.

Corpus Dashboard and richer Question filtering are reserved for Sprint 12.

The remaining uncertain ideas stay in `docs/design/backlog.md` under
low-priority/some-day-maybe work.

Multi-page Shared Context is not backlog: it is a rejected design direction.

## 5. Verification summary

Sprint 11 verification includes focused regressions for each slice plus broader
checkpoints.

Final local release verification established:

- Spotless formatting check: green;
- full non-UI Maven suite: green;
- full headless UI Maven suite: green;
- strict Javadoc generation: green;
- application image creation: green;
- MSI creation: green;
- install/launch outside Eclipse: green;
- uninstall: green;
- user configuration survival after uninstall: green.

GitHub CI remains the final branch gate before protected-main merge.

## 6. Durable design decisions from Sprint 11

1. Question content may be an ordered mixture of authoritative PDF regions and
   authoritative clipboard images.
2. Clipboard images are persisted in SQLite as PNG BLOBs and participate in the
   same backup/restore boundary as the rest of the database.
3. Schema v14 owns mixed Question-content persistence.
4. Search layout and Search semantics remain separate concerns.
5. Help is maintained as packaged HTML/CSS, not large hard-coded dialog text.
6. Maven `project.version` is the single authoritative application/release
   version source.
7. Release versions use a validated `major.minor` scheme; ordinary future
   releases automatically advance the minor component.
8. A release build verifies source state; it does not auto-format source.
9. Windows release packaging uses a self-contained jpackage application image
   and per-user MSI.
10. Mutable user configuration/data must never live in an installer-owned
    application directory.
11. The MSI upgrade UUID is stable release infrastructure and must not change
    between releases.
12. Multi-page Shared Context remains rejected.

## 7. Documentation closeout

Durable Sprint 11 architecture/history is reflected in
`docs/development-roadmap.md`.

The repeatable Windows release procedure is documented in
`docs/release-build.md`.

`docs/design/backlog.md` remains the canonical deliberately deferred-work list.

After final feature-branch CI and protected-main merge, this Sprint 11 document
is an immutable detailed record apart from adding final merge/CI identifiers if
desired.
