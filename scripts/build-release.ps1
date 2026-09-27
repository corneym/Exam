param (
    [switch] $AllowDirty,

    [string] $Version
)

$ErrorActionPreference = "Stop"

$repositoryRoot =
    Split-Path -Parent $PSScriptRoot

$mavenWrapper =
    Join-Path $repositoryRoot "mvnw.cmd"

$pomPath =
    Join-Path $repositoryRoot "pom.xml"

$installerScript =
    Join-Path $PSScriptRoot "package-windows-installer.ps1"

Set-Location $repositoryRoot

function Invoke-ReleaseCommand {
    param (
        [Parameter(Mandatory = $true)]
        [string] $Description,

        [Parameter(Mandatory = $true)]
        [scriptblock] $Command
    )

    Write-Host ""
    Write-Host "============================================================"
    Write-Host $Description
    Write-Host "============================================================"

    & $Command

    if ($LASTEXITCODE -ne 0) {
        throw "$Description failed"
    }
}

function Test-ReleaseVersion {
    param (
        [Parameter(Mandatory = $true)]
        [string] $Candidate
    )

    # Release versions use a deliberately simple major.minor scheme. Each
    # component is either zero or a positive integer without leading zeroes.
    return $Candidate -match '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
}

function Get-NextMinorVersion {
    param (
        [Parameter(Mandatory = $true)]
        [string] $CurrentVersion
    )

    if (-not (Test-ReleaseVersion $CurrentVersion)) {
        throw "Current Maven version is not a valid release version: $CurrentVersion"
    }

    $parts =
        $CurrentVersion.Split(".")

    $major =
        [long] $parts[0]

    $minor =
        [long] $parts[1]

    # Ordinary releases advance the minor component while retaining the current
    # major version. Major-version changes are selected explicitly with -Version.
    return "$major.$($minor + 1)"
}

function Set-ProjectVersion {
    param (
        [Parameter(Mandatory = $true)]
        [string] $NewVersion
    )

    if (-not (Test-ReleaseVersion $NewVersion)) {
        throw "Invalid release version: $NewVersion"
    }

    $pomText =
        [System.IO.File]::ReadAllText(
            $pomPath)

    $pattern =
        '(<artifactId>exam-question-bank</artifactId>\s*<version>)([^<]+)(</version>)'

    $matches =
        [regex]::Matches(
            $pomText,
            $pattern)

    if ($matches.Count -ne 1) {
        throw "Could not identify the Exam Question Bank project version uniquely in pom.xml"
    }

    # Replace only the top-level Exam Question Bank version. Dependency and
    # plugin versions elsewhere in the POM must remain untouched.
    $updatedPom =
        [regex]::Replace(
            $pomText,
            $pattern,
            {
                param($match)

                return $match.Groups[1].Value `
                    + $NewVersion `
                    + $match.Groups[3].Value
            },
            1)

    # Preserve UTF-8 without introducing a byte-order mark or reformatting the
    # XML document through an XML serializer.
    $utf8WithoutBom =
        New-Object System.Text.UTF8Encoding($false)

    [System.IO.File]::WriteAllText(
        $pomPath,
        $updatedPom,
        $utf8WithoutBom)
}

function Get-ProjectVersion {

    [xml] $pom =
        Get-Content $pomPath

    $versionNode =
        $pom.SelectSingleNode(
            "/*[local-name()='project']/*[local-name()='version']")

    if ($null -eq $versionNode) {
        throw "Could not read version from pom.xml"
    }

    $currentVersion =
        $versionNode.InnerText.Trim()

    if (-not (Test-ReleaseVersion $currentVersion)) {
        throw "Maven project version is not a valid release version: $currentVersion"
    }

    return $currentVersion
}

$currentVersion =
    Get-ProjectVersion

if (-not $AllowDirty) {
    $gitStatus =
        git status --porcelain

    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect Git working tree"
    }

    if ($gitStatus) {
        throw "Release build requires a clean Git working tree. Commit or revert changes first, or use -AllowDirty while testing the release process."
    }
}

if ([string]::IsNullOrWhiteSpace($Version)) {

    # Once a release has been completed, the next ordinary release advances its
    # minor number automatically.
    $releaseVersion =
        Get-NextMinorVersion $currentVersion
}
else {

    if (-not (Test-ReleaseVersion $Version)) {
        throw "Invalid release version '$Version'. Expected major.minor, for example 0.2 or 1.0."
    }

    $releaseVersion =
        $Version
}

$originalPom =
    [System.IO.File]::ReadAllText(
        $pomPath)

$versionChanged =
    $releaseVersion -ne $currentVersion

Write-Host ""
Write-Host "Exam Question Bank release build"
Write-Host "Current version: $currentVersion"
Write-Host "Release version: $releaseVersion"

try {
    if ($versionChanged) {

        Write-Host "Updating Maven project version to $releaseVersion..."

        Set-ProjectVersion `
            -NewVersion $releaseVersion

        $updatedVersion =
            Get-ProjectVersion

        if ($updatedVersion -ne $releaseVersion) {
            throw "Version update failed; expected $releaseVersion but found $updatedVersion"
        }
    }

    Invoke-ReleaseCommand `
        -Description "1/5 Checking source formatting" `
        -Command {
            & $mavenWrapper spotless:check
        }

    Invoke-ReleaseCommand `
    -Description "2/5 Running non-UI test suite" `
    -Command {

        # A release candidate is already invalid after the first test failure, so
        # stop the suite rather than spending time collecting additional failures.
        & $mavenWrapper `
            "-Dsurefire.skipAfterFailureCount=1" `
            test
    }
    Invoke-ReleaseCommand `
    -Description "3/5 Running headless UI test suite" `
    -Command {

        # Release validation stops at the first UI regression because no later
        # release gate can make a failing test suite acceptable.
        & $mavenWrapper `
            -Pheadless-ui-tests `
            "-Dsurefire.skipAfterFailureCount=1" `
            test
    }

    Invoke-ReleaseCommand `
        -Description "4/5 Generating strict Javadoc" `
        -Command {
            & $mavenWrapper javadoc:javadoc
        }

    Invoke-ReleaseCommand `
        -Description "5/5 Building Windows release installer" `
        -Command {
            & $installerScript
        }

    $installerDirectory =
        Join-Path $repositoryRoot "target\installer"

    $installers =
        @(Get-ChildItem `
            -Path $installerDirectory `
            -Filter "*.msi" `
            -File)

    if ($installers.Count -ne 1) {
        throw "Release build expected exactly one MSI installer; found $($installers.Count)"
    }

    $expectedInstallerVersion =
        "-$releaseVersion.msi"

    if (-not $installers[0].Name.EndsWith(
        $expectedInstallerVersion)) {

        throw "Installer filename does not contain release version $releaseVersion`: $($installers[0].Name)"
    }

    Write-Host ""
    Write-Host "============================================================"
    Write-Host "RELEASE BUILD PASSED"
    Write-Host "============================================================"
    Write-Host "Version: $releaseVersion"
    Write-Host "Installer:"
    Write-Host $installers[0].FullName

    if ($versionChanged) {
        Write-Host ""
        Write-Host "pom.xml now records version $releaseVersion."
    }
}
catch {

    if ($versionChanged) {

        # A failed release must not advance the authoritative project version.
        $utf8WithoutBom =
            New-Object System.Text.UTF8Encoding($false)

        [System.IO.File]::WriteAllText(
            $pomPath,
            $originalPom,
            $utf8WithoutBom)

        Write-Host ""
        Write-Host "Release failed. pom.xml restored to version $currentVersion."
    }

    throw
}
