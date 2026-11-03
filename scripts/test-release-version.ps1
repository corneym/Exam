$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "release-version.ps1")

function Assert-Equal {
    param (
        $Expected,
        $Actual,
        [string] $Description
    )

    if ($Expected -ne $Actual) {
        throw "$Description`: expected '$Expected', received '$Actual'"
    }
}

function Assert-Throws {
    param (
        [scriptblock] $Action,
        [string] $Description
    )

    $threw = $false

    try {
        & $Action | Out-Null
    }
    catch {
        $threw = $true
    }

    if (-not $threw) {
        throw "$Description`: expected an exception"
    }
}

$validVersions = @(
    "0.0.0",
    "1.0.0",
    "1.0.1",
    "1.1.0",
    "2.0.0",
    "255.255.65535"
)

foreach ($version in $validVersions) {
    Assert-Equal $true `
        (Test-ReleaseVersion -Candidate $version) `
        "Valid release $version"
}

$invalidVersions = @(
    "1.0",
    "1.0.0.0",
    "01.0.1",
    "1.00.1",
    "1.0.01",
    "256.0.0",
    "1.256.0",
    "1.0.65536",
    "1.0.1-SNAPSHOT",
    "1.0.1+build",
    "1..1"
)

foreach ($version in $invalidVersions) {
    Assert-Equal $false `
        (Test-ReleaseVersion -Candidate $version) `
        "Invalid release $version"
}

Assert-Equal $true `
    (Test-ProjectVersion -Candidate "1.0") `
    "Historical Maven version"

Assert-Equal $true `
    (Test-ProjectVersion -Candidate "1.0.1") `
    "Three-component Maven version"

Assert-Equal $false `
    (Test-ProjectVersion -Candidate "01.0") `
    "Invalid historical version"

Assert-Equal "1.0.0" `
    (ConvertTo-VersionTriplet -Version "1.0") `
    "Historical version normalisation"

Assert-Equal "1.0.1" `
    (Get-NextPatchVersion -CurrentVersion "1.0") `
    "First patch release after 1.0"

Assert-Equal "1.0.2" `
    (Get-NextPatchVersion -CurrentVersion "1.0.1") `
    "Successive patch release"

Assert-Equal "1.1.1" `
    (Get-NextPatchVersion -CurrentVersion "1.1.0") `
    "Patch release after minor change"

Assert-Equal 1 `
    (Compare-ReleaseVersions -Left "1.0.1" -Right "1.0") `
    "Patch upgrade"

Assert-Equal 1 `
    (Compare-ReleaseVersions -Left "1.1.0" -Right "1.0.99") `
    "Minor upgrade"

Assert-Equal 1 `
    (Compare-ReleaseVersions -Left "2.0.0" -Right "1.255.65535") `
    "Major upgrade"

Assert-Equal 0 `
    (Compare-ReleaseVersions -Left "1.0.0" -Right "1.0") `
    "Equivalent historical version"

Assert-Equal -1 `
    (Compare-ReleaseVersions -Left "1.0.0" -Right "1.0.1") `
    "Downgrade"

Assert-Throws `
    { Get-NextPatchVersion -CurrentVersion "1.0.65535" } `
    "Patch overflow protection"

Write-Host "Release version regression tests passed."