$ErrorActionPreference = "Stop"

$repositoryRoot =
    Split-Path -Parent $PSScriptRoot

$pomPath =
    Join-Path $repositoryRoot "pom.xml"

$appImageScript =
    Join-Path $PSScriptRoot "package-windows-app-image.ps1"

$appImageDirectory =
    Join-Path `
        $repositoryRoot `
        "target\package\Exam Question Bank"

$installerDirectory =
    Join-Path `
        $repositoryRoot `
        "target\installer"

# Keep this UUID unchanged for future releases. Windows Installer uses it to
# recognise later Exam Question Bank packages as upgrades of this product.
$upgradeUuid =
    "29eeeeb7-cbe8-5d98-a67f-36240572d76c"

[xml] $pom =
    Get-Content $pomPath

$versionNode =
    $pom.SelectSingleNode(
        "/*[local-name()='project']/*[local-name()='version']")

if ($null -eq $versionNode) {
    throw "Could not read version from pom.xml"
}

$version =
    $versionNode.InnerText.Trim()

# jpackage delegates Windows MSI generation to WiX. Accept either the
# traditional WiX 3 candle/light toolchain or the newer wix executable.
$wixCommand =
    Get-Command "wix.exe" -ErrorAction SilentlyContinue

$candleCommand =
    Get-Command "candle.exe" -ErrorAction SilentlyContinue

$lightCommand =
    Get-Command "light.exe" -ErrorAction SilentlyContinue

$hasWix =
    $null -ne $wixCommand `
    -or (
        $null -ne $candleCommand `
        -and $null -ne $lightCommand
    )

if (-not $hasWix) {
    throw "WiX Toolset is required to create the Windows MSI installer."
}

$jpackage =
    $null

if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $candidate =
        Join-Path $env:JAVA_HOME "bin\jpackage.exe"

    if (Test-Path $candidate) {
        $jpackage =
            $candidate
    }
}

if ($null -eq $jpackage) {
    $jpackageCommand =
        Get-Command "jpackage.exe" -ErrorAction Stop

    $jpackage =
        $jpackageCommand.Source
}

$jpackageVersion =
    (& $jpackage --version).Trim()

if (-not $jpackageVersion.StartsWith("25")) {
    throw "Java 25 jpackage is required; found version $jpackageVersion"
}

Set-Location $repositoryRoot

Write-Host "Building fresh application image..."

# Always rebuild the tested application-image structure so the installer cannot
# accidentally package stale application classes or dependencies.
& $appImageScript

if ($LASTEXITCODE -ne 0) {
    throw "Application image build failed"
}

if (-not (Test-Path $appImageDirectory)) {
    throw "Application image was not produced: $appImageDirectory"
}

if (Test-Path $installerDirectory) {
    Remove-Item `
        -Recurse `
        -Force `
        $installerDirectory
}

New-Item `
    -ItemType Directory `
    -Path $installerDirectory |
    Out-Null

Write-Host "Creating Exam Question Bank $version MSI installer..."

# Package the already-tested application image. The fixed upgrade UUID allows
# future releases to replace this application rather than appearing as unrelated
# products in Windows Installer.
& $jpackage `
    --type msi `
    --name "Exam Question Bank" `
    --app-version $version `
    --description "Exam Question Bank" `
    --app-image $appImageDirectory `
    --dest $installerDirectory `
    --win-per-user-install `
    --win-menu `
    --win-menu-group "Exam Question Bank" `
    --win-upgrade-uuid $upgradeUuid

if ($LASTEXITCODE -ne 0) {
    throw "jpackage MSI creation failed"
}

$installers =
    @(Get-ChildItem `
        -Path $installerDirectory `
        -Filter "*.msi" `
        -File)

if ($installers.Count -ne 1) {
    throw "Expected exactly one MSI installer; found $($installers.Count)"
}

Write-Host ""
Write-Host "Windows installer created:"
Write-Host $installers[0].FullName
