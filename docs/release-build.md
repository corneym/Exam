# Windows Release Build

> **Current published release:** 0.1  
> **Next release candidate:** 0.2 (Sprint 12; issue #64 pending)  
> **Release entry point:** `scripts/build-release.ps1`  
> **Packaging platform:** Windows  
> **Updated:** 5 October 2026

This document describes the repeatable Exam Question Bank Windows release process. The scripts and `pom.xml` remain authoritative if this document and implementation ever disagree.

## 1. Release architecture

```text
Maven project
    ↓
release gates
    ↓
clean application JAR + runtime dependencies
    ↓
jpackage application image + private Java runtime
    ↓
per-user Windows MSI
```

The final distributable artifact is the MSI, not the development JAR.

Packaging scripts:

```text
scripts/build-release.ps1
scripts/package-windows-app-image.ps1
scripts/package-windows-installer.ps1
```

`build-release.ps1` is the normal formal entry point. Lower-level scripts are for packaging development/diagnosis.

## 2. Prerequisites

The tested Windows packaging architecture uses:

- JDK 25 with `jpackage`;
- repository Maven Wrapper;
- WiX Toolset 7.0.0;
- `WixToolset.Util.wixext` 7.0.0 where required by the installed WiX distribution;
- Git.

The package scripts reject a non-Java-25 `jpackage`.

## 3. Authoritative version

The top-level Maven version in `pom.xml` is authoritative. Maven filters the same value into the packaged application properties; About, Version Information, backup metadata, application-image metadata and MSI metadata therefore share one source.

At the Sprint 12 PR-preparation checkpoint `pom.xml` deliberately remains:

```xml
<version>0.1</version>
```

Issue #64 owns Release 0.2 closeout. The normal release command advances the minor version from 0.1 to 0.2. Documentation must not describe 0.2 as released before that gate succeeds.

Release versions use `major.minor`, for example `0.1`, `0.10`, `1.0`.

When `build-release.ps1` runs without `-Version`, it treats the current Maven version as the previous successful release identity and increments the minor component:

```text
0.1 → 0.2
0.9 → 0.10
1.4 → 1.5
```

Major transitions are explicit:

```powershell
.\scripts\build-release.ps1 -Version 1.0
```

If the release script changes `pom.xml` and a later gate fails, it restores the original POM/version.

## 4. Clean-tree rule

A formal release requires a clean Git working tree.

Normal command:

```powershell
.\scripts\build-release.ps1
```

`-AllowDirty` exists for development/validation of release infrastructure and is not the normal release procedure.

## 5. Release gates

The release script runs these gates in order:

1. `spotless:check`;
2. non-UI tests;
3. headless UI tests;
4. strict Javadoc;
5. Windows installer packaging.

Equivalent commands for the first four gates include:

```powershell
.\mvnw.cmd spotless:check
.\mvnw.cmd "-Dsurefire.skipAfterFailureCount=1" test
.\mvnw.cmd -Pheadless-ui-tests "-Dsurefire.skipAfterFailureCount=1" test
.\mvnw.cmd javadoc:javadoc
```

Javadoc runs with doclint and `failOnWarnings=true`; warnings are release failures, not advisory output.

No MSI is accepted unless all preceding gates pass.

## 6. Application-image packaging

`package-windows-app-image.ps1` performs a fresh package build, collects runtime dependencies and invokes Java 25 `jpackage --type app-image`.

Packaged main class:

```text
au.edu.eq.questionbank.Launcher
```

The application is packaged as a class-path application with its own stripped Java runtime.

Output:

```text
target\package\Exam Question Bank\Exam Question Bank.exe
```

## 7. MSI packaging

`package-windows-installer.ps1` rebuilds a fresh app image and creates a per-user Start-menu MSI.

The Windows Installer upgrade UUID is:

```text
29eeeeb7-cbe8-5d98-a67f-36240572d76c
```

Do not change this UUID for later releases.

Output pattern:

```text
target\installer\Exam Question Bank-<version>.msi
```

For the pending Sprint 12 release candidate, successful issue #64 execution is expected to produce:

```text
target\installer\Exam Question Bank-0.2.msi
```

Only describe that artifact as produced after the release gate has actually completed.

## 8. Writable configuration and application data

Installed program files do not own mutable user configuration/bank data.

Windows configuration:

```text
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties
```

Default first-run data root:

```text
%LOCALAPPDATA%\Exam Question Bank Data\data
```

The writable directory remains a sibling of the installer-owned application directory so uninstall cannot remove user-owned configuration/data.

If `LOCALAPPDATA` is unavailable, `ApplicationPaths` falls back beneath the user home directory.

## 9. Post-build smoke test

After the release gate produces the intended MSI, install and launch outside Eclipse.

Verify at minimum:

- application starts normally;
- expected existing bank data is visible with an existing configured data root;
- Corpus Dashboard opens as the normal application home for the selected Working Subject;
- Help opens;
- About reports the intended release version;
- application closes normally.

Then uninstall and verify the user configuration and configured data root survive unchanged.

Release 0.1 passed this install/launch/uninstall/configuration-survival check. Release 0.2 must repeat the check under issue #64 before it is considered released.

## 10. Release 0.2 closeout checklist — #64

Release 0.2 is ready for protected-main merge/release closeout only when:

- Sprint 12 implementation is in the pull request candidate;
- release script completes successfully;
- Maven/application/MSI version is 0.2;
- generated MSI is present at the expected versioned path;
- install/launch smoke test passes outside Eclipse;
- Dashboard, Help and existing data are usable from the installed package;
- uninstall preserves user configuration/data;
- final feature-branch CI is green;
- pull-request review comments are resolved;
- the Sprint 12 document records the final PR/merge/CI/release identifiers after they exist.

After merge, preserve `docs/design/sprint-12-release-0.2.md` as the detailed historical record and update the roadmap/README with actual merge/release evidence rather than predictions.
