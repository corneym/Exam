# Exam Question Bank

**Exam Question Bank** is a Windows desktop application for collecting, organising and reusing examination questions and associated marking material. It was developed for secondary-school science teaching, initially with Chemistry, while keeping the data model and workflows subject-neutral.

The application is written in Java 25 with JavaFX. It maintains a local SQLite question bank and packages its own Java runtime in a Windows MSI installer.

## Download and installation

**Release 1.0 is complete** (maintainer confirmation, 1 November 2026). See [GitHub Releases](https://github.com/corneym/Exam/releases/latest) for the most recently published Windows installer and its release notes. The [v1.0 release](https://github.com/corneym/Exam/releases/tag/v1.0) is the first completed major version.

The application is distributed as a per-user Windows MSI. You do not need to install Java separately to run the packaged application.

**Code signing status:** The Windows installer build currently has no trusted publisher-signing step. Windows may display an unknown-publisher or application-reputation warning. A free, trusted signing route is being investigated in [Issue #217](https://github.com/corneym/Exam/issues/217). Do not disable Windows security controls or circumvent organisational security policies to install it. Check the individual release notes for its actual signing and verification details.

**Code signing policy:** See the [Code signing policy](docs/code-signing-policy.md) for maintainer responsibilities, privacy, release approval and verification requirements. Trusted signing is not yet active in the build process.

An installation contains the application and required runtime components, **not** examination papers, question-bank records, syllabus PDFs, curriculum workbooks or user databases. Users provide and manage their own source material.

## What the application does

- Register and manage examination papers, question booklets, answer booklets and expected capture counts.
- Capture questions and answers from PDFs, including multipart questions, shared context, multiple-choice answers and written responses.
- Track corpus completeness and outstanding capture/correction work from a dashboard.
- Classify questions against syllabus structures and review historical-to-current curriculum mappings.
- Search, inspect and correct captured questions; control their applicability to revision output.
- Generate static HTML revision resources and SCORM 1.2 packages for learning platforms.
- Back up and restore the local question bank and managed source assets.

The **Corpus Dashboard** is the main application entry point. Its Working Subject selection governs relevant curriculum, capture, search and export workflows. Built-in Help describes the main workflows.

## Data and privacy

All application data is held locally under a configurable data root; the SQLite database is the authoritative store. Installation files and writable user data are kept separate, so ordinary application uninstall is not intended to remove the question bank.

The default Windows configuration and data locations are:

```text
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties
%LOCALAPPDATA%\Exam Question Bank Data\data
```

The data root can be configured within the application. Back up your data before significant changes and follow the application's backup/restore workflow.

**The Windows installer does not include examination papers, curriculum source datasets or user question-bank data.** The repository-root `data/` directory is excluded from Git tracking; a small number of curriculum workbook fixtures remain under `src/test/resources/` for testing and are not part of the installer. Users are responsible for ensuring that their use of third-party educational material complies with applicable permissions and licences.

## Building from source

Prerequisites for development:

- Windows for MSI packaging; JDK 25 for compilation and development.
- Git and the included Maven Wrapper.
- WiX Toolset for Windows MSI creation.

From the repository root in Git Bash on Windows:

```bash
./mvnw.cmd test
./mvnw.cmd -Pheadless-ui-tests test
./mvnw.cmd spotless:check
./mvnw.cmd javadoc:javadoc
```

Windows release builds use the PowerShell entry point `scripts/build-release.ps1`, which applies additional validation and packaging gates. Consult [release-build documentation](docs/release-build.md) and the actual scripts before producing a release. The current script uses two-component versions (`major.minor`); do not assume that it yet accepts a three-component semantic version such as `1.0.1`.

## Documentation and project tracking

- [Documentation index](docs/README.md) — architecture, operating instructions and development references.
- [GitHub Issues](https://github.com/corneym/Exam/issues) — authoritative requirements, ongoing work and implementation decisions.
- [GitHub Releases](https://github.com/corneym/Exam/releases) — versioned Windows installers.

## Licence

The original Exam Question Bank application source code is provided under the [MIT Licence](LICENSE). The MIT grant does **not** confer rights in third-party examination papers, syllabus documents, curriculum datasets or other materials that are not part of the licensed source code.

Exam Question Bank is an independent educational project and is not an official product of any examination provider or publisher.
