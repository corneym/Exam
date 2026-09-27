# Windows Release Build

> **Current release:** 0.1\
> **Release entry point:** `scripts/build-release.ps1`\
> **Packaging platform:** Windows\
> **Updated:** 27 September 2026

This document describes the repeatable Exam Question Bank Windows release
process. The scripts and `pom.xml` remain authoritative if this document and the
implementation ever disagree.

## 1. Release architecture

The release consists of:

``` text
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

The packaging scripts are:

``` text
scripts/build-release.ps1
scripts/package-windows-app-image.ps1
scripts/package-windows-installer.ps1
```

`build-release.ps1` is the normal release entry point. The lower-level scripts
exist so packaging can be tested independently while it is being developed.

## 2. Prerequisites

The tested Release 0.1 Windows toolchain is:

- JDK 25 with `jpackage`;
- Maven Wrapper from the repository;
- WiX Toolset 7.0.0;
- `WixToolset.Util.wixext` 7.0.0 available to WiX;
- Git.

The WiX installation must be usable by `jpackage`. Where the installed WiX
distribution requires acceptance of its own licensing/EULA before extensions
can be installed or used, complete that tool setup before attempting MSI
creation.

`package-windows-app-image.ps1` and `package-windows-installer.ps1` reject a
`jpackage` version that is not Java 25.

## 3. Authoritative version

The authoritative application version is the top-level Maven version in
`pom.xml`.

For Release 0.1:

``` xml
<version>0.1</version>
```

Maven filters the same value into:

``` text
src/main/resources/au/edu/eq/questionbank/application.properties
```

`ApplicationVersion.current()` loads that packaged value. About, Version
Information, backup metadata, application-image metadata and MSI metadata
therefore share one version source.

### Version format

Release versions must match:

``` text
major.minor
```

Examples:

``` text
0.1
0.10
1.0
2.4
```

Invalid examples include:

``` text
01.2
0.1.1
v1.0
1.0-SNAPSHOT
```

Each component is either `0` or a positive integer without leading zeroes.

### Automatic increment

When `build-release.ps1` is run without `-Version`, it treats the current Maven
version as the previous successful release identity and increments the minor
component.

Example:

``` text
0.1 -> 0.2
0.9 -> 0.10
1.4 -> 1.5
```

Major-version changes are explicit:

``` powershell
.\scripts\build-release.ps1 -Version 1.0
```

If the script changes `pom.xml` and a later release gate fails, it restores the
original POM/version.

Release 0.1 was deliberately built with an explicit version because the POM
already contained the intended first release number:

``` powershell
.\scripts\build-release.ps1 -Version 0.1
```

## 4. Clean-tree rule

A formal release requires a clean Git working tree.

Run:

``` powershell
git status
```

before starting a formal release.

The normal release command is:

``` powershell
.\scripts\build-release.ps1
```

During development of the release infrastructure only, `-AllowDirty` bypasses
the clean-tree check:

``` powershell
.\scripts\build-release.ps1 -AllowDirty -Version 0.1
```

Do not use `-AllowDirty` as the normal release procedure.

## 5. Release gates

`build-release.ps1` runs five gates in order.

### 1/5 --- Source formatting

``` powershell
.\mvnw.cmd spotless:check
```

A release build checks formatting; it does not silently modify source.

If this gate fails during development, correct the source or run:

``` powershell
.\mvnw.cmd spotless:apply
```

then start the release gate again.

### 2/5 --- Non-UI tests

Equivalent release command:

``` powershell
.\mvnw.cmd "-Dsurefire.skipAfterFailureCount=1" test
```

The release gate stops the suite after the first reported test failure/error so
an already-invalid candidate does not waste time completing the remaining
suite.

### 3/5 --- Headless UI tests

Equivalent release command:

``` powershell
.\mvnw.cmd -Pheadless-ui-tests "-Dsurefire.skipAfterFailureCount=1" test
```

The same first-failure rule applies.

### 4/5 --- Strict Javadoc

``` powershell
.\mvnw.cmd javadoc:javadoc
```

The Maven Javadoc Plugin is configured with doclint enabled and
`failOnWarnings=true`, so warnings fail the release gate.

### 5/5 --- Windows installer

The release script invokes:

``` text
scripts/package-windows-installer.ps1
```

No MSI is accepted unless the preceding release gates pass.

## 6. Application-image packaging

`package-windows-app-image.ps1` performs a fresh package build and constructs the
self-contained application image.

It:

1. reads Maven artifact/version metadata from `pom.xml`;
2. locates Java 25 `jpackage`;
3. removes the previous application image, retrying when Windows briefly retains
   a handle on a previously launched executable;
4. runs:

   ``` powershell
   .\mvnw.cmd clean package "-DskipTests"
   ```

5. collects runtime dependencies with Maven Dependency Plugin 3.11.0;
6. copies the application JAR into the package input directory;
7. calls `jpackage --type app-image`.

The packaged main class is:

``` text
au.edu.eq.questionbank.Launcher
```

The application is packaged as a class-path application. This keeps runtime
dependency packaging straightforward even though the source application is
modular.

The generated image contains its own Java runtime. `--strip-native-commands`
means `runtime\bin\java.exe` is intentionally absent; runtime verification
should use `runtime\release` and the bundled JVM library rather than expecting a
Java launcher executable.

Output:

``` text
target\package\Exam Question Bank\Exam Question Bank.exe
```

## 7. MSI packaging

`package-windows-installer.ps1` always rebuilds a fresh application image before
creating the installer.

The generated MSI is:

- per-user;
- added to the Windows Start menu;
- placed in the `Exam Question Bank` Start-menu group;
- versioned from the Maven project version.

The Windows Installer upgrade UUID is:

``` text
29eeeeb7-cbe8-5d98-a67f-36240572d76c
```

**Do not change this UUID for future releases.** Windows Installer uses it to
recognise later packages as upgrades of the same application.

Output:

``` text
target\installer\Exam Question Bank-<version>.msi
```

For Release 0.1:

``` text
target\installer\Exam Question Bank-0.1.msi
```

## 8. Writable configuration and application data

Installed program files are not the owner of user configuration or bank data.

On Windows:

``` text
%LOCALAPPDATA%\Exam Question Bank Data\questionbank.properties
```

stores user configuration.

A fresh installation defaults its `data.root` to:

``` text
%LOCALAPPDATA%\Exam Question Bank Data\data
```

The actual data root may instead point elsewhere through Options.

The `Exam Question Bank Data` directory is deliberately a sibling of the
installer-owned application directory. Do not move writable configuration/data
back beneath the jpackage product directory: MSI uninstall testing proved that
installer-owned directories can be removed during uninstall.

When the user-specific configuration does not yet exist,
`ApplicationConfig.loadOrCreate(...)` can migrate an existing legacy
working-directory `questionbank.properties`. Otherwise it creates the
first-run configuration and required managed data directories.

On systems without `LOCALAPPDATA`, `ApplicationPaths` falls back to:

``` text
<user.home>\.exam-question-bank
```

## 9. Post-build smoke test

The release gate proves build/test/package state. The generated MSI must also be
tested as an installed application outside Eclipse.

### Install

From the repository root:

``` powershell
$msi = (Resolve-Path ".\target\installer\Exam Question Bank-0.1.msi").Path

Start-Process `
    msiexec.exe `
    -Wait `
    -ArgumentList "/i `"$msi`""
```

Launch **Exam Question Bank** from the Start menu.

Verify:

- application starts normally;
- expected existing bank data is visible when using an existing configured data
  root;
- Help opens;
- About reports the expected release version;
- application closes normally.

### Uninstall

Close the application, then run:

``` powershell
Start-Process `
    msiexec.exe `
    -Wait `
    -ArgumentList "/x `"$msi`""
```

Verify user configuration survived:

``` powershell
$cfg = Join-Path `
    $env:LOCALAPPDATA `
    "Exam Question Bank Data\questionbank.properties"

Test-Path $cfg
Get-Content $cfg
```

`Test-Path` must remain `True`.

If the configured `data.root` is external to the default location, also verify
that directory remains present and unchanged.

Release 0.1 passed this install/launch/uninstall/configuration-survival check.

## 10. Release closeout

A release is ready for protected-main merge only when:

- release script completed successfully;
- generated MSI has the intended version;
- install/launch smoke test passed outside Eclipse;
- uninstall preserves user configuration/data;
- documentation matches the implemented release procedure;
- feature-branch CI is green;
- final pull request is reviewed/resolved according to repository settings.

After merge, preserve the sprint document as detailed historical evidence and
record any final merge/CI identifiers required by the project documentation.
