# Windows Release Build and Upgrade

> **Latest published release (28 October 2026):** [v0.7](https://github.com/corneym/Exam/releases/tag/v0.7)
>
> **Current Maven version:** `0.7`
>
> **Release entry point:** `scripts/build-release.ps1` (Windows)
>
> **Status of 1.0:** Planned, not yet built, tagged or published

This guide describes the current Windows MSI release process and the specific checks for the planned 1.0 release. For current release availability, use [GitHub Releases](https://github.com/corneym/Exam/releases/latest). Source code, the Maven POM and the scripts are authoritative if a document becomes outdated.

## 1. Release architecture

The release pipeline is:

~~~text
Maven source and dependencies
    ↓
Spotless, non-UI tests, headless JavaFX tests, strict Javadoc
    ↓
application JAR and dependencies
    ↓
Java 25 jpackage app image with private Java runtime
    ↓
per-user Windows MSI (currently unsigned)
    ↓
immutable local version archive
    ↓
manual verification, tag and GitHub Release
~~~

The final distributable is the MSI, not the development JAR. The release does not contain examination PDFs, curriculum source documents, user databases or collected Questions.

| File | Role |
| --- | --- |
| `scripts/build-release.ps1` | Formal release gates, version and archive management |
| `scripts/package-windows-app-image.ps1` | Fresh Java 25 application image |
| `scripts/package-windows-installer.ps1` | MSI from the app image; may reuse an existing image |
| `.github/workflows/ci.yml` | Ubuntu non-UI and partitioned headless JavaFX tests |
| `.github/workflows/codeql.yml` | CodeQL Java security and quality scan |
| `.github/workflows/windows-msi-check.yml` | Manually triggered **unsigned** Windows MSI packaging test |

The unsigned Windows CI workflow preserves the Maven version, checks that the application launcher remains byte-for-byte unchanged during MSI packaging, checks the version and signing status, and records SHA-256 hashes and the source commit. Its build artefacts are temporary (three-day retention); they are **not** official GitHub Releases.

## 2. Release prerequisites

- Windows with Git and the repository Maven Wrapper.
- Java 25 JDK with `jpackage` and jmods.
- WiX available to `jpackage` (`wix.exe` or the supported `candle.exe` / `light.exe` toolchain). The Windows CI test pins WiX 7.0.0 and its Util extension.
- A clean Git working tree on the intended release source commit, with the relevant PRs merged and GitHub CI reviewed.
- An independently retained **manual full backup** of the authoritative data, plus a separate, isolated data copy for installation and upgrade checks.

Do not point Eclipse/development and production installations at the same active data root. Do not run unverified release candidates against the authoritative production data.

## 3. Version, source and upgrade identity

The top-level Maven version in `pom.xml` is the version source for the application, About dialog, backup metadata and generated installer. The version scheme accepted by `build-release.ps1` is `major.minor` (for example, `0.7` or `1.0`), **not** `1.0-rc1`.

When `-Version` is omitted, the release script increments the *minor* part of the version already in the POM. Thus, from `0.7` it would select `0.8`; after the POM is changed to `1.0`, it would select `1.1`. **Always pass `-Version 1.0` explicitly when making version 1.0.**

For 1.0, first put the agreed version change through a separate PR, merge it and verify that `main` records `1.0`. Then run the formal build from that exact clean revision:

~~~powershell
.\scripts\build-release.ps1 -Version 1.0
~~~

A failed release gate restores the original POM if the script changed it. A successful gate leaves the selected release version in the POM. The formal release should not depend on an uncommitted version change; the future `v1.0` tag must identify source whose POM already records `1.0`.

The Windows MSI has a fixed upgrade UUID:

~~~text
29eeeeb7-cbe8-5d98-a67f-36240572d76c
~~~

Do **not** change that identity between versions. The installer uses `--win-per-user-install` and creates a Start Menu entry.

## 4. Formal release gates

`build-release.ps1` performs, in this order:

1. `spotless:check`;
2. the non-UI Maven test suite;
3. the `-Pheadless-ui-tests` suite;
4. strict Javadoc, with `failOnWarnings=true`;
5. the Windows MSI packaging process.

The corresponding Maven commands are:

~~~powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dsurefire.skipAfterFailureCount=1" test
.\mvnw.cmd -Pheadless-ui-tests "-Dsurefire.skipAfterFailureCount=1" test
.\mvnw.cmd javadoc:javadoc
~~~

The build rejects a dirty working tree unless `-AllowDirty` is explicitly used for development of the build process. Do **not** use `-AllowDirty` for a published release.

The app-image script builds the JAR, collects runtime dependencies and packages `au.edu.eq.questionbank.Launcher` with Java 25 `jpackage --type app-image`. The MSI script packages that app image with `jpackage --type msi` and verifies that the launcher hash is unchanged.

By default, the MSI script creates a fresh app image. `-UseExistingAppImage` can package an already-built image of the same Maven version without rebuilding it. This preserves a future executable-signing boundary but **does not sign** anything today.

## 5. Immutable archives and provenance

The final MSI under `target/installer/` is copied after successful gates to:

~~~text
release-artifacts/<version>/<generated MSI filename>
~~~

The `release-artifacts/` directory is ignored by Git and lies outside Maven's disposable `target/` tree.

An archived MSI for a version prevents another formal build of that version. Never silently replace a historical MSI. MSI bytes are not guaranteed to be reproducible even from equivalent source.

Inspect the *actual generated filename*: previously published assets have sometimes used dots instead of spaces in their names. The release script checks that the filename ends in `-<version>.msi`.

From PowerShell, calculate the SHA-256 digest of the archived 1.0 MSI (after it has been built):

~~~powershell
Get-ChildItem -LiteralPath '.\release-artifacts\1.0' -Filter '*.msi' |
    Get-FileHash -Algorithm SHA256 |
    Format-List Path, Hash
~~~

Record the exact digest and release-source commit. Confirm that the subsequently uploaded GitHub Release asset has the same bytes. A checksum detects mismatch when compared with an independently obtained expected value; it does **not** provide a trusted publisher identity.

### Rejected unpublished candidate

`build-release.ps1` has no special release-candidate version suffix or candidate-archive lifecycle. An unsuccessful *manual acceptance test after a successful build* does not automatically undo or remove its archive. **Do not rebuild over the same archive or publish a failed candidate.** Resolve the defect and make an explicit archive/version decision before generating another candidate; never overwrite an already published release.

## 6. Writable data, backup and recovery

The installed application keeps writable data separate from its program files. Default Windows paths are:

~~~text
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties
%LOCALAPPDATA%\Exam Question Bank Data\data
~~~

The data root is configurable, and `ApplicationPaths` provides a user-home fallback if `LOCALAPPDATA` is unavailable.

- **Automatic backups on normal application exit** are *database-only*. They contain no managed PDFs or curriculum assets, and the default retention policy keeps the ten newest automatic backups in the data root's `backups/automatic/` directory.
- **Manual full backups** created using **File → Backup Now...** contain the database and complete managed `subjects/` hierarchy, including stored Exam/Answer PDFs, curriculum source files and other Subject-managed assets.
- A full backup does **not** include the separate `questionbank.properties` configuration file, unmanaged files outside `subjects/`, or other unrelated files beneath the data root. Preserve any required settings and independently sourced originals separately.
- Before a destructive restore, the application creates a full pre-restore safety backup at `backups/pre-restore/`; it attempts rollback if replacement fails. Abrupt interruption cannot be assumed to trigger that rollback. Keep an independent full backup outside the active data root.

The source of truth remains the SQLite database together with its referenced managed assets. Do not treat synchronisation between computers as an alternative to independently retained backups. The installed Help provides user-facing backup and recovery guidance.

## 7. Version 1.0 acceptance — planned, not yet performed

Existing operational evidence is already substantial: the maintainer has used HTML and SCORM outputs through QLearn, exercised the core workflows extensively, upgraded and uninstalled releases 0.2–0.7 on two Windows computers, and repeatedly transferred a growing corpus using backup and restore. These are accepted historical checks; do not repeat the entire programme simply to publish 1.0.

For the **exact** 1.0 MSI, complete the following additional checks:

1. Confirm successful release gates on the intended release-source commit, and save the archived MSI's SHA-256.
2. With **0.7** installed on a test computer and an **isolated restored corpus**, close the application and install the 1.0 MSI over it.
3. Confirm About reports 1.0, the Dashboard displays the expected Subjects/Exams, representative Question and Answer PDFs load, and the application closes normally.
4. Uninstall the test installation and confirm that the test configuration and user data remain intact. Test fresh installation if the release package or installation procedure changed materially.
5. Verify the exact archived MSI and the eventual published asset share a digest. Record results and any known limitations.

Never use the authoritative production corpus for a candidate upgrade check. No new tests, features or migration code are implied unless a concrete failure is found.

## 8. Publication

After the exact archived MSI passes acceptance:

1. Confirm `v1.0` will refer to the source commit actually used for the build and that `pom.xml` at that commit records `1.0`.
2. Create the `v1.0` tag and GitHub Release manually. The build script does not tag, publish or upload artefacts.
3. Upload the **unchanged archived MSI**. Publish its SHA-256, source commit, a brief version history, install/upgrade guidance and known limitations.
4. Verify the public download's digest matches the accepted archive. Keep the original archive immutable.

### Unsigned installer limitation

As of 28 October 2026 the published 0.7 MSI and existing release packaging process are **unsigned**; 1.0 must also be described as unsigned unless independently implemented signing has been verified. Windows may show unknown-publisher, reputation or organisation-policy warnings. No trusted free signing mechanism has been approved. Do not tell recipients to disable Windows security controls or bypass an employer's policies.

See [Code signing policy](code-signing-policy.md) and [Issue #217](https://github.com/corneym/Exam/issues/217). Zero-cost signing remains a constraint and Microsoft Store/MSIX is not planned.

For historical Release 0.2 details, retain [Sprint 12 release record](design/sprint-12-release-0.2.md) rather than treating those older instructions as the active release procedure.
