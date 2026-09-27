function Remove-DirectoryWithRetry {
    param (
        [Parameter(Mandatory = $true)]
        [string] $Path,

        [int] $MaximumAttempts = 15,

        [int] $DelaySeconds = 1
    )

    if (-not (Test-Path $Path)) {
        return
    }

    for ($attempt = 1; $attempt -le $MaximumAttempts; $attempt++) {
        try {
            # A previously launched packaged executable can remain briefly locked
            # by Windows after the application itself has exited.
            Remove-Item `
                -Recurse `
                -Force `
                -ErrorAction Stop `
                $Path

            return
        }
        catch {
            if ($attempt -eq $MaximumAttempts) {
                throw "Could not remove old application image after $MaximumAttempts attempts: $Path`n$($_.Exception.Message)"
            }

            Write-Host `
                "Old application image is still locked; retrying removal ($attempt/$MaximumAttempts)..."

            Start-Sleep `
                -Seconds $DelaySeconds
        }
    }
}

$ErrorActionPreference = "Stop"

$repositoryRoot =
    Split-Path -Parent $PSScriptRoot

$mavenWrapper =
    Join-Path $repositoryRoot "mvnw.cmd"

$pomPath =
    Join-Path $repositoryRoot "pom.xml"

$targetDirectory =
    Join-Path $repositoryRoot "target"

$inputDirectory =
    Join-Path $targetDirectory "package-input"

$packageDirectory =
    Join-Path $targetDirectory "package"

# Read the authoritative artifact name and version from Maven rather than
# duplicating release metadata in this packaging script.
[xml] $pom =
    Get-Content $pomPath

$artifactIdNode =
    $pom.SelectSingleNode(
        "/*[local-name()='project']/*[local-name()='artifactId']")

$versionNode =
    $pom.SelectSingleNode(
        "/*[local-name()='project']/*[local-name()='version']")

if ($null -eq $artifactIdNode -or $null -eq $versionNode) {
    throw "Could not read artifactId or version from pom.xml"
}

$artifactId =
    $artifactIdNode.InnerText.Trim()

$version =
    $versionNode.InnerText.Trim()

$applicationJarName =
    "$artifactId-$version.jar"

$applicationJar =
    Join-Path $targetDirectory $applicationJarName

# Use the same JDK installation selected for the project where possible.
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

# Remove the previous packaged image before Maven clean traverses target. Windows
# may retain a short-lived handle on a recently launched executable.
Remove-DirectoryWithRetry `
    -Path $packageDirectory

Write-Host "Building Exam Question Bank $version..."

# Produce a clean release JAR. Automated regression suites remain separate
# release-gate steps and are not redundantly rerun by every packaging attempt.
& $mavenWrapper clean package "-DskipTests"

if ($LASTEXITCODE -ne 0) {
    throw "Maven package failed"
}

if (-not (Test-Path $applicationJar)) {
    throw "Application JAR was not produced: $applicationJar"
}

New-Item `
    -ItemType Directory `
    -Force `
    -Path $inputDirectory |
    Out-Null

# Copy runtime dependencies beside the application JAR. Pin the dependency
# plugin version so packaging does not depend on Maven plugin-version discovery.
& $mavenWrapper `
    "org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies" `
    "-DincludeScope=runtime" `
    "-DoutputDirectory=$inputDirectory"

if ($LASTEXITCODE -ne 0) {
    throw "Runtime dependency collection failed"
}

Copy-Item `
    -Path $applicationJar `
    -Destination $inputDirectory

New-Item `
    -ItemType Directory `
    -Path $packageDirectory |
    Out-Null

Write-Host "Creating Windows application image..."

# Package as a class-path application. Every runtime JAR in package-input is
# included by jpackage, while the generated image supplies its own Java runtime.
& $jpackage `
    --type app-image `
    --name "Exam Question Bank" `
    --app-version $version `
    --description "Exam Question Bank" `
    --input $inputDirectory `
    --main-jar $applicationJarName `
    --main-class "au.edu.eq.questionbank.Launcher" `
    --dest $packageDirectory `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --jlink-options "--strip-native-commands --strip-debug --no-man-pages --no-header-files --bind-services"

if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed"
}

$applicationExecutable =
    Join-Path `
        $packageDirectory `
        "Exam Question Bank\Exam Question Bank.exe"

if (-not (Test-Path $applicationExecutable)) {
    throw "Packaged executable was not produced: $applicationExecutable"
}

Write-Host ""
Write-Host "Application image created:"
Write-Host $applicationExecutable
