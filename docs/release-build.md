````markdown
# Windows Release Build

> **Current published release:** 0.2  
> **Release source:** Sprint 12 / PR #88  
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
application JAR + runtime dependencies
    ↓
jpackage application image + private Java runtime
    ↓
per-user Windows MSI
    ↓
local immutable release archive
    ↓
GitHub Release asset
```

The final distributable artefact is the MSI, not the development JAR.

Packaging scripts:

```text
scripts/build-release.ps1
scripts/package-windows-app-image.ps1
scripts/package-windows-installer.ps1
```

`build-release.ps1` is the normal formal entry point. Lower-level scripts are for packaging development and diagnosis.

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

Maven, the application and the successfully produced MSI use **0.2**. PR #88 merged to protected `main` as `8ba714e2ef7ff07eebb2375021e1e751d4876e47`; post-merge CI and CodeQL passed, and Release 0.2 was subsequently published through GitHub Releases.

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

`-AllowDirty` exists for development and validation of release infrastructure and is not the normal release procedure.

## 5. Release archive protection

Successful installers are preserved outside Maven's disposable `target` directory.

The archive structure is:

```text
release-artifacts\
    0.1\
        Exam Question Bank-0.1.msi
    0.2\
        Exam Question Bank-0.2.msi
    0.3\
        Exam Question Bank-0.3.msi
```

`release-artifacts` is deliberately excluded from Git by `.gitignore`.

The release script creates the required archive and version directories automatically. No manual directory creation is required for a normal release.

Before changing `pom.xml` or running any release gates, `build-release.ps1` checks whether the requested version already contains an archived MSI. If one exists, the script fails immediately.

For example:

```text
Release archive already contains an MSI for version 0.2:
D:\git\Exam\release-artifacts\0.2\Exam Question Bank-0.2.msi.
Use a new release version.
```

An archived release installer is immutable. Do not overwrite an archived MSI with a later rebuild of the same version.

Testing demonstrated that MSI output is not byte-for-byte reproducible across equivalent builds, so a newly generated MSI must not silently replace the historical artefact for an already released version.

## 6. Release gates

For a version that is not already archived, the release script runs these gates in order:

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

## 7. Application-image packaging

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

Everything beneath `target` is generated build output and may be removed by Maven `clean`.

## 8. MSI packaging and local archival

`package-windows-installer.ps1` rebuilds a fresh app image and creates a per-user Start-menu MSI.

The Windows Installer upgrade UUID is:

```text
29eeeeb7-cbe8-5d98-a67f-36240572d76c
```

Do not change this UUID for later releases.

Temporary build output follows this pattern:

```text
target\installer\Exam Question Bank-<version>.msi
```

After every release gate and installer-version check succeeds, `build-release.ps1` copies the installer to:

```text
release-artifacts\<version>\Exam Question Bank-<version>.msi
```

For Release 0.2:

```text
release-artifacts\0.2\Exam Question Bank-0.2.msi
```

The local archived copy is the durable local release artefact. The copy under `target` remains disposable.

## 9. GitHub Releases

Published installers belong in **GitHub Releases**, not as committed binary files in the Git repository.

Each formal release should have:

```text
Git tag:        v<version>
Release title:  Exam Question Bank <version>
Release asset:  Exam Question Bank-<version>.msi
```

For example:

```text
Tag:            v0.2
Release title:  Exam Question Bank 0.2
Asset:          Exam Question Bank-0.2.msi
```

The tag must identify the source for that release rather than whatever happens to be the current `main` commit when the GitHub Release is created.

After the local release build and installed-MSI verification succeed:

1. ensure the appropriate `v<version>` Git tag identifies the release source;
2. create or open the corresponding GitHub Release;
3. upload the archived MSI from `release-artifacts\<version>`;
4. publish the Release;
5. confirm the MSI appears under the Release's downloadable assets.

The script does not currently create the Git tag, GitHub Release or upload the MSI automatically. Those remain explicit release-management steps.

GitHub Releases for **0.1** and **0.2** have been created and their reconstructed/preserved MSI installers have been uploaded.

## 10. Reconstructing a historical installer

If a historical installer has been lost but its release source remains in Git, it may be reconstructed from a detached worktree without changing the current development checkout.

For example, Release 0.2 may be rebuilt from its historical release source in a separate worktree and the resulting MSI copied into:

```text
release-artifacts\0.2\
```

A reconstructed MSI represents the same application source and release version, but it must not be assumed to have the same binary hash as the originally generated installer. MSI packaging has been observed to produce different bytes across equivalent builds.

Once a historical version has been archived and published through GitHub Releases, later rebuilds must not replace that preserved artefact.

## 11. Writable configuration and application data

Installed program files do not own mutable user configuration or bank data.

Windows configuration:

```text
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties
```

Default first-run data root:

```text
%LOCALAPPDATA%\Exam Question Bank Data\data
```

The writable directory remains a sibling of the installer-owned application directory so uninstall cannot remove user-owned configuration or data.

If `LOCALAPPDATA` is unavailable, `ApplicationPaths` falls back beneath the user home directory.

## 12. Post-build smoke test

After the release gate produces and archives the intended MSI, install and launch it outside Eclipse.

Verify at minimum:

- application starts normally;
- expected existing bank data is visible with an existing configured data root;
- Corpus Dashboard opens as the normal application home for the selected Working Subject;
- Help opens;
- About reports the intended release version;
- application closes normally.

Then uninstall and verify the user configuration and configured data root survive unchanged.

Only after these checks should the archived MSI be treated as the release artefact to publish through GitHub Releases.

Release 0.1 passed the install/launch/uninstall/configuration-survival check.

Release 0.2 also passed manual installed-MSI verification outside Eclipse: Dashboard startup, existing persisted data readable, Help opens, About reports 0.2, normal shutdown, successful uninstall, and configuration/data survival.

## 13. Release 0.2 closeout

Release 0.2 closeout is complete.

Sprint 12 merged through PR #88. The merge commit was `8ba714e2ef7ff07eebb2375021e1e751d4876e47`. Final PR-head CI was green before merge, and post-merge CI and CodeQL passed.

`scripts/build-release.ps1` completed all automated release gates and produced the Release 0.2 MSI. Maven, the application and the MSI all reported version **0.2**.

The installed-MSI checks in section 12 passed, including uninstall and configuration/data survival.

The formal gate exposed hard-coded Release 0.1 expectations in `ApplicationVersionTest` and the About-dialog `ApplicationLifecycleWorkflowTest`; both release-independent test defects were fixed before the successful Release 0.2 gate.

Release 0.2 is now preserved both locally under `release-artifacts\0.2` and as a downloadable GitHub Release asset.

Release 0.1 has also been reconstructed/preserved and published as a GitHub Release.

Preserve `docs/design/sprint-12-release-0.2.md` as the detailed historical Release 0.2 record.
````