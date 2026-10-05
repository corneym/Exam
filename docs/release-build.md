# Windows Release Build

> **Current published release:** 0.2  
> **Release source:** Sprint 12 / PR #88\
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

After the successful Release 0.2 gate, `pom.xml` records:

```xml
<version>0.2</version>
```

Maven, the application and the successfully produced MSI use **0.2**. PR #88 merged to protected `main` as `8ba714e2ef7ff07eebb2375021e1e751d4876e47`; post-merge CI run #164 and CodeQL run #15 passed on that commit, and issue #64 is closed. Release 0.2 is the current published release.

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

The successful Sprint 12 Release 0.2 gate produced:

```text
target\installer\Exam Question Bank-0.2.msi
```

The artifact passed the manual installed-MSI checks below and was subsequently merged to protected `main` through PR #88.

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

Release 0.1 passed this install/launch/uninstall/configuration-survival check. Release 0.2 has now also passed manual installed-MSI verification outside Eclipse: Dashboard startup, existing persisted data readable, Help opens, About reports 0.2, normal shutdown, successful uninstall, and configuration/data survival.

## 10. Release 0.2 closeout checklist — #64

Release 0.2 closeout is complete:

- Sprint 12 merged through PR [#88](https://github.com/corneym/Exam/pull/88).
- Merge commit: `8ba714e2ef7ff07eebb2375021e1e751d4876e47`.
- Final PR-head CI was green before merge.
- Post-merge CI run **#164** passed on the merge commit.
- Post-merge CodeQL run **#15** passed on the merge commit.
- `scripts/build-release.ps1` completed all automated gates and produced the Release 0.2 MSI.
- Maven/application/MSI version is **0.2**.
- The installed-MSI checks in section 9 passed, including uninstall and configuration/data survival.
- The formal gate exposed hard-coded Release 0.1 expectations in `ApplicationVersionTest` and the About-dialog `ApplicationLifecycleWorkflowTest`; both release-independent test defects were fixed before the successful gate.
- Issue #64 was closed as completed after merge and post-merge CI evidence were recorded.

Preserve `docs/design/sprint-12-release-0.2.md` as the detailed historical record.