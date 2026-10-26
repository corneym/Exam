# Exam Question Bank — documentation

This directory contains engineering and workflow documentation for [Exam Question Bank](../README.md). It is separate from the user-facing Help packaged in the application.

**Source of truth:** Repository code, tests, GitHub Issues, pull requests and GitHub Releases take precedence over dated explanatory documents. Do not infer current feature or release status solely from an older sprint report.

## Key documents

| Document | Purpose |
| --- | --- |
| [`Working-Instructions.md`](Working-Instructions.md) | Mandatory working rules for repository-guided development and verification. |
| [`code-signing-policy.md`](code-signing-policy.md) | Code signing policy, signing roles, privacy, verifiable build requirements and current unsigned-release status. |
| [`release-build.md`](release-build.md) | Windows build, MSI packaging, versioning, archival and manual release checks. **Some version-specific examples are historical**; use current scripts and GitHub Releases for current behaviour. |
| [`data-layout-migration.md`](data-layout-migration.md) | Safeguarded migration of earlier data-root layouts to subject-first managed storage. |
| [`performance-benchmarking.md`](performance-benchmarking.md) | Performance benchmarking approach and commands. |
| [`performance-diagnostics.md`](performance-diagnostics.md) | Runtime diagnostics and performance investigation. |
| [`development-roadmap.md`](development-roadmap.md) | Historical architecture and development direction; older sprint/status passages are retained as history, not as an up-to-date release register. |
| [`design/backlog.md`](design/backlog.md) | Deliberately deferred design work; active work belongs in GitHub Issues. |
| [`design/`](design/) | Historical numbered sprint design, implementation and verification records. |

## Current release and active work

As verified on **26 October 2026**, the latest published Windows installer is [v0.7](https://github.com/corneym/Exam/releases/tag/v0.7). For the most recent version, always consult [GitHub Releases](https://github.com/corneym/Exam/releases/latest) rather than a version embedded in this document.

Feature requests, defects, implementation decisions and acceptance evidence are recorded in [GitHub Issues](https://github.com/corneym/Exam/issues). In particular, [Issue #217](https://github.com/corneym/Exam/issues/217) tracks investigation of zero-cost trusted Windows signing. The v0.7 MSI is unsigned, and no signed replacement of that historical release is authorised.

## Current architecture at a glance

- **Runtime:** Java 25, JavaFX desktop UI, Maven-based build, SQLite persistence, PDFBox and Apache POI.
- **Windows distribution:** `jpackage` application image and per-user WiX-backed MSI, containing a private Java runtime.
- **Application home:** Corpus Dashboard with authoritative Working Subject; Exam/Assets, capture, search, curriculum and revision-output workflows.
- **Local data:** Configurable authoritative SQLite/data root separate from program files. Managed source assets use a subject-first layout. The repository-root `data/` directory is not an authoritative runtime data store and is excluded from source tracking.
- **Data safety:** Backup, restore, validation and migration protection for existing banks.
- **Output:** Static revision HTML and SCORM 1.2 packages.

For actual user controls and workflow instructions, use **Help** in the installed application. For changing code, inspect the relevant source and tests on the current branch.

## Local verification

The project uses the Maven Wrapper. From Git Bash on Windows:

```bash
./mvnw.cmd test
./mvnw.cmd -Pheadless-ui-tests test
./mvnw.cmd spotless:check
./mvnw.cmd javadoc:javadoc
```

The GitHub Actions pipeline runs automated non-UI and headless JavaFX/UI coverage. The formal Windows release gate is `scripts/build-release.ps1`; it includes additional checks and packaging stages. The installer signing process is **not yet implemented**.

## Documentation upkeep

- Treat GitHub Issues as the durable record for changes, design decisions and verification outcomes.
- Maintain user-facing workflow guidance through the application's Help files.
- Update stable reference documents when behaviour or operating procedures change; do not rewrite completed sprint histories to make them appear current.
- Keep sensitive local configuration, examination material, collected questions and production databases outside the source repository and Windows release artefacts. Review separately licensed test fixtures before publication; do not assume that publicly accessible curriculum documents are copyright-free.
