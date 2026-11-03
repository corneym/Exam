# Windows Release Build and Upgrade

> **Completed release:** [v1.0](https://github.com/corneym/Exam/releases/tag/v1.0) — completion confirmed by the maintainer on 1 November 2026.
>
> **Current Maven version on `main`:** `1.0`
>
> **Release entry point:** `scripts/build-release.ps1` (Windows)
>
> **Next release:** `1.0.1` is the default next release version under the new `MAJOR.MINOR.PATCH` scheme. It has not yet been built or published. The current Maven version remains `1.0` until a release build is performed.

Release 1.0 is complete, not planned. This guide describes the Windows MSI build procedure and retains the 1.0-specific acceptance and publication checks as a historical reference. For release assets and the latest published version, use [GitHub Releases](https://github.com/corneym/Exam/releases/latest). Source code, the Maven POM and the scripts remain authoritative when explanatory documents become outdated.

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
per-user Windows MSI (existing process has no trusted signing step)
    ↓
immutable local version archive
    ↓
manual verification, tag and GitHub Release
~~~

The final distributable is the MSI, not the development JAR. The release does not contain examination PDFs, curriculum source documents, user databases or collected Questions.

| File | Role |
| --- | --- |
| `scripts/build-release.ps1` | Formal release gates, version and archive management |
| `scripts/release-version.ps1` | Version validation, comparison, normalisation and patch increment logic |
| `scripts/test-release-version.ps1` | PowerShell regression tests for release-versioning rules |
| `scripts/package-windows-app-image.ps1` | Fresh Java 25 application image |
| `scripts/package-windows-installer.ps1` | MSI from the app image; may reuse an existing image |
| `.github/workflows/ci.yml` | Ubuntu non-UI and partitioned headless JavaFX tests |
| `.github/workflows/codeql.yml` | CodeQL Java security and quality scan |
| `.github/workflows/windows-msi-check.yml` | Manually triggered unsigned Windows MSI packaging test |

The unsigned Windows CI workflow preserves the Maven version, checks that the application launcher remains byte-for-byte unchanged during MSI packaging, checks the version and signing status, and records SHA-256 hashes and the source commit. Its build artefacts are temporary (three-day retention); they are not official GitHub Releases.

## 2. Release prerequisites

- Windows with Git and the repository Maven Wrapper.
- Java 25 JDK with `jpackage` and jmods.
- WiX available to `jpackage` (`wix.exe` or the supported `candle.exe` / `light.exe` toolchain). The Windows CI test pins WiX 7.0.0 and its Util extension.
- A clean Git working tree on the intended release source commit, with the relevant PRs merged and GitHub CI reviewed.
- An independently retained **manual full backup** of the authoritative data, plus a separate, isolated data copy for installation and upgrade checks.

Do not point Eclipse/development and production installations at the same active data root. Do not run unverified release candidates against the authoritative production data.


## 3. Version, source and upgrade identity

The top-level Maven version in `pom.xml` is authoritative for the application, About dialog, backup metadata and generated installer.

### Version format

New releases use `MAJOR.MINOR.PATCH`.

| Component | Purpose | Example |
| --- | --- | --- |
| MAJOR | Incompatible changes | `2.0.0` |
| MINOR | New backward-compatible functionality | `1.1.0` |
| PATCH | Bug fixes and maintenance | `1.0.1` |

The release script accepts exactly three numeric components for new release versions. Leading zeroes, prerelease suffixes and build metadata are not supported.

Windows Installer imposes these numeric limits:

- MAJOR: 0–255
- MINOR: 0–255
- PATCH: 0–65535

### Compatibility with historical releases

Published releases such as `0.7` and `1.0` retain their original version numbers, filenames, Git tags and release archives.

For comparison purposes, the release-versioning functions interpret the existing `1.0` Maven version as equivalent to `1.0.0`.

The historical `1.0` release must not be renamed or rebuilt as `1.0.0`.

The first three-component release following `1.0` is therefore `1.0.1`.

### Selecting the next release

When `-Version` is omitted, `build-release.ps1` automatically increments PATCH.

| Current Maven version | Selected release |
| --- | --- |
| `1.0` | `1.0.1` |
| `1.0.1` | `1.0.2` |
| `1.1.0` | `1.1.1` |
| `2.0.0` | `2.0.1` |

To create a MINOR or MAJOR release, specify the intended version explicitly.

**Next PATCH release:**

~~~powershell
.\scripts\build-release.ps1
~~~

**Explicit MINOR release:**

~~~powershell
.\scripts\build-release.ps1 -Version 1.1.0
~~~

**Explicit MAJOR release:**

~~~powershell
.\scripts\build-release.ps1 -Version 2.0.0
~~~

These are real release-build commands, not dry-run examples. Do not execute them merely to test version validation.

### Release version safeguards

The release script:

1. Validates the requested version against the three-component format and Windows Installer limits.
2. Rejects downgrades.
3. Rejects an equivalent version expressed differently, such as selecting `1.0.0` while the Maven version is `1.0`.
4. Allows the exact current Maven version to be selected explicitly when no MSI has been archived for it.
5. Rejects a release version whose archive already contains an MSI.
6. Rejects automatic PATCH increments beyond 65535, requiring an explicitly selected higher MINOR or MAJOR version.

A successful release build leaves the selected version in `pom.xml`.

If a release gate fails after the script has modified the POM, the original POM contents are restored.

The release source must be committed appropriately so the published Git tag identifies the source corresponding to the accepted installer and records its release version.

### Historical release 1.0

The completed 1.0 release was produced under the original two-component convention:

~~~powershell
.\scripts\build-release.ps1 -Version 1.0
~~~

This command is historical documentation and is no longer accepted as a new release version by the updated script.

### Windows Installer upgrade identity

The Windows MSI retains the fixed upgrade UUID:

~~~text
29eeeeb7-cbe8-5d98-a67f-36240572d76c
~~~

Do **not** change this identity between releases.

The installer uses `--win-per-user-install` and creates a Start Menu entry.

The first `1.0.1` installer must be verified to upgrade an existing `1.0` installation using isolated test data. Version-validation tests do not establish successful MSI upgrade behaviour.


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


### Testing release-versioning logic

Version validation can be tested without executing the release pipeline.

From Git Bash:

~~~bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/test-release-version.ps1
~~~

Expected output:

~~~text
Release version regression tests passed.
~~~

These regression tests cover accepted and rejected versions, historical two-component compatibility, version comparisons, automatic PATCH increments and PATCH overflow protection.

The existing installer-script contract tests and formatting checks are:

~~~bash
./mvnw.cmd "-Dtest=PerformanceInstalledLauncherTest" test
./mvnw.cmd spotless:check
~~~

All three checks passed locally when the three-component versioning implementation was prepared.

These checks do not build an MSI or validate Windows upgrade behaviour. The full release gates remain mandatory when producing an actual release.

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

For the completed 1.0 archive, the historical checksum command was:

~~~powershell
Get-ChildItem -LiteralPath '.\release-artifacts\1.0' -Filter '*.msi' |
    Get-FileHash -Algorithm SHA256 |
    Format-List Path, Hash
~~~

For future releases, substitute the exact version in the archive path. Record the exact digest and release-source commit. Confirm that the uploaded GitHub Release asset has the same bytes. A checksum detects mismatch when compared with an independently obtained expected value; it does **not** provide a trusted publisher identity.

### Rejected unpublished candidate

`build-release.ps1` has no special release-candidate version suffix or candidate-archive lifecycle. An unsuccessful manual acceptance test after a successful build does not automatically undo or remove its archive. **Do not rebuild over the same archive or publish a failed candidate.** Resolve the defect and make an explicit archive/version decision before generating another candidate; never overwrite an already published release.

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

## 7. Completed release 1.0 — historical acceptance reference

**Release 1.0 has been completed**, as confirmed by the maintainer. The following requirements formed its release-specific acceptance plan; individual test results and exact MSI hashes must be taken from the corresponding release records and build artefacts, not inferred from this guide.

Established operational evidence included use of HTML and SCORM output through QLearn, core capture and correction workflows, upgrade/uninstall exercises for releases 0.2–0.7 on two Windows computers, and transfer of a growing corpus using backup and restore.

The historical 1.0 acceptance plan required:

1. Successful release gates for the intended source commit and recording the MSI SHA-256.
2. Installing the 1.0 MSI over 0.7 using an isolated restored corpus.
3. Checking About version, existing Subjects/Exams, representative Question and Answer PDF loading and normal shutdown.
4. Testing the uninstall/data-preservation behaviour and fresh installation where the package or procedure had materially changed.
5. Checking that the released MSI digest matched the accepted archived artefact.

The completed release is historical; **do not re-run these steps against production data merely to document 1.0**. For future releases, carry out appropriate acceptance checks against an isolated data copy.

## 8. Publication and subsequent releases

The maintainer confirms that version 1.0 has been completed. The [v1.0 release page](https://github.com/corneym/Exam/releases/tag/v1.0) is the historical reference; use the published release record for exact tag, asset and digest details.

For each subsequent release:

1. Confirm the release tag will refer to the source commit actually used for the build and that `pom.xml` at that commit records the correct version.
2. Publish the tag and GitHub Release after approval. The build script itself does not tag, publish or upload artefacts.
3. Upload the unchanged archived MSI and publish its SHA-256, source commit, version history, install/upgrade guidance and known limitations.
4. Verify that the public download's digest matches the accepted archive. Preserve the original immutable artefact.

For each release, record whether it is a PATCH, MINOR or MAJOR version increment and summarise the corresponding changes in the GitHub Release notes.

For the first three-component release (`1.0.1`), acceptance must include installing the new MSI over the completed `1.0` release, verifying that the installed application reports `1.0.1`, and confirming that existing application data and configuration remain accessible. Perform these tests using an isolated restored data copy, never the authoritative production database.

### Unsigned installer limitation

The source-controlled build process has no trusted installer-signing implementation as of 1 November 2026. Windows may show unknown-publisher, reputation or organisation-policy warnings for unsigned packages. Consult the release-specific artefact and notes for its signing state. No trusted free signing mechanism has been approved. Do not tell recipients to disable Windows security controls or bypass an employer's policies.

See [Code signing policy](code-signing-policy.md) and [Issue #217](https://github.com/corneym/Exam/issues/217). Zero-cost signing remains a constraint and Microsoft Store/MSIX is not planned.

For historical Release 0.2 details, retain [Sprint 12 release record](design/sprint-12-release-0.2.md) rather than treating those older instructions as the active release procedure.
