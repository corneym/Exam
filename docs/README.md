# Exam Question Bank — documentation

This directory contains engineering and workflow documentation for [Exam Question Bank](../README.md). It is separate from the user-facing Help packaged in the application.

**Source of truth:** Repository code, tests, GitHub Issues, pull requests and GitHub Releases take precedence over dated explanatory documents. Do not infer current feature or release status solely from an older sprint report.

## Key documents

| Document | Purpose |
| --- | --- |
| [`Working-Instructions.md`](Working-Instructions.md) | Mandatory working rules for repository-guided development and verification. |
| [`code-signing-policy.md`](code-signing-policy.md) | Code signing policy, signing roles, privacy, verifiable build requirements and current unsigned-release status. |
| [`release-build.md`](release-build.md) | Windows build, MSI packaging, three-component versioning, archival and manual release checks. Some version-specific instructions are historical; consult the scripts for current behaviour. |
| [`data-layout-migration.md`](data-layout-migration.md) | Safeguarded migration of earlier data-root layouts to subject-first managed storage. |
| [`offline-exchange.md`](offline-exchange.md) | Current coordinator, contributor and HoD package workflows, authority, backup and accepted limitations. |
| [`performance-benchmarking.md`](performance-benchmarking.md) | Performance benchmarking approach and commands. |
| [`performance-diagnostics.md`](performance-diagnostics.md) | Runtime diagnostics and performance investigation. |
| [`development-roadmap.md`](development-roadmap.md) | Durable architecture, development history and forward direction. |
| [`design/backlog.md`](design/backlog.md) | Deliberately deferred design work; active work belongs in GitHub Issues. |
| [`design/sprint-24-offline-exchange.md`](design/sprint-24-offline-exchange.md) | Sprint 24 implementation, acceptance, independent audit and verification evidence. |
| [`design/`](design/) | Historical numbered sprint design, implementation and verification records. |

## Current release and active work

At the 26 November 2026 Sprint 24 verification checkpoint, [v1.0.1](https://github.com/corneym/Exam/releases/tag/v1.0.1) is the latest published release. Release 1.0 is historical. See [GitHub Releases](https://github.com/corneym/Exam/releases/latest) for the current published assets and release notes. The audited Sprint 24 candidate retains Maven version `1.0.1` pending the formal next-release build. No new release number or publication is asserted here.

Sprint 24 offline exchange ([#276](https://github.com/corneym/Exam/issues/276)) is functionally complete with accepted limitations. All 22 CI checks passed on audited commit `fea74e90cccf6332d1c2a3bb43ed70d5e79bd97e`; the maintainer confirmed strict Javadoc was clean. Audit branch integration, documentation, final PR/merge and formal release remain separate steps. Deferred findings are tracked in #301–#309.

Feature requests, defects, implementation decisions and acceptance evidence are recorded in [GitHub Issues](https://github.com/corneym/Exam/issues). [Issue #217](https://github.com/corneym/Exam/issues/217) tracks investigation of zero-cost trusted Windows signing. The existing installer build process has no trusted publisher-signing step; consult the applicable release notes for asset-specific status.

## Current architecture at a glance

- **Runtime:** Java 25, JavaFX desktop UI, Maven-based build, SQLite persistence, PDFBox and Apache POI.
- **Windows distribution:** `jpackage` application image and per-user WiX-backed MSI, containing a private Java runtime.
- **Application home:** Corpus Dashboard with authoritative Working Subject; Exam/Assets, capture, search, curriculum and revision-output workflows.
- **Local data:** Configurable authoritative SQLite/data root separate from program files. Managed source assets use a subject-first layout. The repository-root `data/` directory is not an authoritative runtime data store and is excluded from source tracking.
- **Data safety:** Backup, restore, validation and sequential migration protection for existing banks; Sprint 24 schema v49.
- **Offline exchange:** `.examwork` packages with portable identities, validated lineage and managed assets; one-way whole-Exam assignment/final return and whole-Subject snapshot/refresh workflows.
- **Authority:** Imported Subjects are read-only; ordinary mutations are guarded at UI, service and persistence boundaries. Coordinator-side Exam locks and contributor baseline protection limit assigned work. Approved import/refresh is the authorised Subject replacement path.
- **Output:** Static revision HTML and SCORM 1.2 packages.

For actual user controls and workflow instructions, use **Help** in the installed application and the [offline exchange guide](offline-exchange.md). For changing code, inspect the relevant source and tests on the current branch.

## Local verification

The project uses the Maven Wrapper. From Git Bash on Windows:

```bash
./mvnw.cmd test
./mvnw.cmd -Pheadless-ui-tests test
./mvnw.cmd spotless:check
./mvnw.cmd javadoc:javadoc
```

The GitHub Actions pipeline runs automated non-UI and JavaFX/UI coverage in bounded suites (11 non-UI shards, 10 UI shards and the non-UI aggregate check at the Sprint 24 checkpoint). The formal Windows release gate is `scripts/build-release.ps1`; it includes additional checks and packaging stages. The installer signing process is **not yet implemented**.

## Documentation upkeep

- Treat GitHub Issues as the durable record for changes, design decisions and verification outcomes.
- Maintain user-facing workflow guidance through the application's Help files and stable operational guides.
- Update stable reference documents when behaviour or operating procedures change; do not rewrite completed sprint histories to make them appear current.
- Keep sensitive local configuration, examination material, collected questions and production databases outside the source repository and Windows release artefacts. Review separately licensed test fixtures before publication; do not assume that publicly accessible curriculum documents are copyright-free.
