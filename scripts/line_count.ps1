param(
    [switch]$Detailed
)

$ErrorActionPreference = "Stop"

# Resolve the repository root from the script location so that this script
# works regardless of the caller's current PowerShell working directory.
$RepositoryRoot = Split-Path -Parent $PSScriptRoot

$ProductionJavaPath = Join-Path $RepositoryRoot "src\main\java"
$TestJavaPath = Join-Path $RepositoryRoot "src\test\java"

function Test-TripleQuote {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,

        [Parameter(Mandatory = $true)]
        [int]$Index
    )

    # A Java text block delimiter consists of three consecutive quote
    # characters. Ensure enough characters remain before comparing them.
    return (
        $Index + 2 -lt $Text.Length -and
        $Text.Substring($Index, 3) -eq '"""'
    )
}

function Test-IsEscaped {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Text,

        [Parameter(Mandatory = $true)]
        [int]$Index
    )

    # Count consecutive backslashes immediately before the character.
    # An odd number means the character is escaped.
    $backslashCount = 0
    $position = $Index - 1

    while (
        $position -ge 0 -and
        $Text[$position] -eq [char]92
    ) {
        $backslashCount++
        $position--
    }

    return ($backslashCount % 2 -eq 1)
}

function Measure-JavaFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$File
    )

    $lines = [System.IO.File]::ReadAllLines($File.FullName)

    $codeLines = 0
    $commentOnlyLines = 0
    $blankLines = 0
    $mixedCodeCommentLines = 0

    # These states can continue from one physical source line to the next.
    $inBlockComment = $false
    $inTextBlock = $false

    foreach ($line in $lines) {

        # A physically empty or whitespace-only source line is always counted
        # as blank so the three primary categories remain easy to interpret.
        if ([string]::IsNullOrWhiteSpace($line)) {
            $blankLines++
            continue
        }

        $hasCode = $false
        $hasComment = $false
        $index = 0

        while ($index -lt $line.Length) {

            # Text block contents are Java source, even when they contain text
            # that resembles // or /* comments.
            if ($inTextBlock) {
                $hasCode = $true

                if (
                    (Test-TripleQuote -Text $line -Index $index) -and
                    -not (Test-IsEscaped -Text $line -Index $index)
                ) {
                    $inTextBlock = $false
                    $index += 3
                    continue
                }

                $index++
                continue
            }

            # Everything remains comment text until the closing block-comment
            # delimiter is encountered.
            if ($inBlockComment) {
                $hasComment = $true

                if (
                    $index + 1 -lt $line.Length -and
                    $line[$index] -eq '*' -and
                    $line[$index + 1] -eq '/'
                ) {
                    $inBlockComment = $false
                    $index += 2
                    continue
                }

                $index++
                continue
            }

            $character = $line[$index]

            # Whitespace outside literals and comments carries no source-code
            # meaning for this line classification.
            if ([char]::IsWhiteSpace($character)) {
                $index++
                continue
            }

            # A Java line comment consumes the remainder of the physical line.
            if (
                $index + 1 -lt $line.Length -and
                $line[$index] -eq '/' -and
                $line[$index + 1] -eq '/'
            ) {
                $hasComment = $true
                break
            }

            # A Java block comment may end on this line or on a later line.
            if (
                $index + 1 -lt $line.Length -and
                $line[$index] -eq '/' -and
                $line[$index + 1] -eq '*'
            ) {
                $hasComment = $true
                $inBlockComment = $true
                $index += 2
                continue
            }

            # Detect a Java text block before detecting an ordinary string,
            # because both begin with a quote character.
            if (Test-TripleQuote -Text $line -Index $index) {
                $hasCode = $true
                $inTextBlock = $true
                $index += 3
                continue
            }

            # Scan an ordinary Java string as a unit. Escaped characters are
            # skipped so comment markers inside strings are never misread.
            if ($character -eq '"') {
                $hasCode = $true
                $index++

                while ($index -lt $line.Length) {
                    if ($line[$index] -eq [char]92) {
                        $index += 2
                        continue
                    }

                    if ($line[$index] -eq '"') {
                        $index++
                        break
                    }

                    $index++
                }

                continue
            }

            # Scan a Java character literal in the same way as a string so
            # escaped quote or slash characters cannot affect classification.
            if ($character -eq "'") {
                $hasCode = $true
                $index++

                while ($index -lt $line.Length) {
                    if ($line[$index] -eq [char]92) {
                        $index += 2
                        continue
                    }

                    if ($line[$index] -eq "'") {
                        $index++
                        break
                    }

                    $index++
                }

                continue
            }

            # Any remaining non-whitespace token is Java source code. This
            # includes declarations, annotations, braces and punctuation.
            $hasCode = $true
            $index++
        }

        if ($hasCode) {
            $codeLines++

            # Keep this as a secondary statistic. Mixed lines remain part of
            # the Code category rather than being counted twice.
            if ($hasComment) {
                $mixedCodeCommentLines++
            }
        }
        elseif ($hasComment) {
            $commentOnlyLines++
        }
        else {
            # This is a defensive fallback. In normal Java source a non-empty
            # line should have been recognised as either code or comment.
            $blankLines++
        }
    }

    return [PSCustomObject]@{
        PhysicalLines         = $lines.Count
        CodeLines             = $codeLines
        CommentOnlyLines      = $commentOnlyLines
        BlankLines            = $blankLines
        MixedCodeCommentLines = $mixedCodeCommentLines
        Bytes                 = $File.Length
    }
}

function Get-JavaStatistics {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return [PSCustomObject]@{
            Files                 = 0
            PhysicalLines         = 0
            CodeLines             = 0
            CommentOnlyLines      = 0
            BlankLines            = 0
            MixedCodeCommentLines = 0
            Bytes                 = 0
        }
    }

    $files = @(Get-ChildItem -Path $Path -Recurse -File -Filter "*.java")

    $physicalLines = 0
    $codeLines = 0
    $commentOnlyLines = 0
    $blankLines = 0
    $mixedCodeCommentLines = 0
    $bytes = 0

    foreach ($file in $files) {
        # Measure each Java file independently, while allowing lexical state
        # such as block comments to span physical lines within that file.
        $statistics = Measure-JavaFile -File $file

        $physicalLines += $statistics.PhysicalLines
        $codeLines += $statistics.CodeLines
        $commentOnlyLines += $statistics.CommentOnlyLines
        $blankLines += $statistics.BlankLines
        $mixedCodeCommentLines += $statistics.MixedCodeCommentLines
        $bytes += $statistics.Bytes
    }

    return [PSCustomObject]@{
        Files                 = $files.Count
        PhysicalLines         = $physicalLines
        CodeLines             = $codeLines
        CommentOnlyLines      = $commentOnlyLines
        BlankLines            = $blankLines
        MixedCodeCommentLines = $mixedCodeCommentLines
        Bytes                 = $bytes
    }
}

function Get-JUnitAnnotationCount {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        return 0
    }

    # Count source-level JUnit test annotations. This is a count of annotated
    # test definitions, not necessarily the number of runtime test executions.
    $annotationPattern =
        '^\s*@(?:[\w.]+\.)?' +
        '(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b'

    $count = 0

    foreach ($file in Get-ChildItem -Path $Path -Recurse -File -Filter "*.java") {
        foreach ($line in [System.IO.File]::ReadAllLines($file.FullName)) {
            if ($line -match $annotationPattern) {
                $count++
            }
        }
    }

    return $count
}

function Format-Number {
    param(
        [Parameter(Mandatory = $true)]
        [long]$Value
    )

    return "{0:N0}" -f $Value
}

$production = Get-JavaStatistics -Path $ProductionJavaPath
$tests = Get-JavaStatistics -Path $TestJavaPath
$junitAnnotations = Get-JUnitAnnotationCount -Path $TestJavaPath

$totalFiles = $production.Files + $tests.Files
$totalPhysicalLines =
    $production.PhysicalLines + $tests.PhysicalLines
$totalCodeLines =
    $production.CodeLines + $tests.CodeLines
$totalCommentOnlyLines =
    $production.CommentOnlyLines + $tests.CommentOnlyLines
$totalBlankLines =
    $production.BlankLines + $tests.BlankLines
$totalMixedCodeCommentLines =
    $production.MixedCodeCommentLines + $tests.MixedCodeCommentLines
$totalBytes =
    $production.Bytes + $tests.Bytes

# The categories must account for every physical Java source line. Fail
# immediately if a future change to the scanner violates that invariant.
$classifiedTotal =
    $totalCodeLines +
    $totalCommentOnlyLines +
    $totalBlankLines

if ($classifiedTotal -ne $totalPhysicalLines) {
    throw (
        "Line classification error: physical lines = {0}, " +
        "classified lines = {1}" -f
        $totalPhysicalLines,
        $classifiedTotal
    )
}

Write-Host ""
Write-Host "Exam Question Bank - Project Statistics"
Write-Host "======================================="
Write-Host ""

$rows = @(
    [PSCustomObject]@{
        Category    = "Production Java"
        Files       = Format-Number $production.Files
        Physical    = Format-Number $production.PhysicalLines
        Code        = Format-Number $production.CodeLines
        Comments    = Format-Number $production.CommentOnlyLines
        Blank       = Format-Number $production.BlankLines
        "Code+comment" = Format-Number $production.MixedCodeCommentLines
    },
    [PSCustomObject]@{
        Category    = "Test Java"
        Files       = Format-Number $tests.Files
        Physical    = Format-Number $tests.PhysicalLines
        Code        = Format-Number $tests.CodeLines
        Comments    = Format-Number $tests.CommentOnlyLines
        Blank       = Format-Number $tests.BlankLines
        "Code+comment" = Format-Number $tests.MixedCodeCommentLines
    },
    [PSCustomObject]@{
        Category    = "TOTAL"
        Files       = Format-Number $totalFiles
        Physical    = Format-Number $totalPhysicalLines
        Code        = Format-Number $totalCodeLines
        Comments    = Format-Number $totalCommentOnlyLines
        Blank       = Format-Number $totalBlankLines
        "Code+comment" = Format-Number $totalMixedCodeCommentLines
    }
)

$rows | Format-Table -AutoSize

Write-Host "JUnit test definitions : $(Format-Number $junitAnnotations)"
Write-Host "Java source size       : $("{0:N2}" -f ($totalBytes / 1MB)) MB"

if ($totalCodeLines -gt 0) {
    $productionCodePercentage =
        ($production.CodeLines / $totalCodeLines) * 100

    $testCodePercentage =
        ($tests.CodeLines / $totalCodeLines) * 100

    Write-Host (
        "Production code share : {0:N1}% of Java code lines" -f
        $productionCodePercentage
    )

    Write-Host (
        "Test code share       : {0:N1}% of Java code lines" -f
        $testCodePercentage
    )
}

if ($totalPhysicalLines -gt 0) {
    $commentPercentage =
        ($totalCommentOnlyLines / $totalPhysicalLines) * 100

    $blankPercentage =
        ($totalBlankLines / $totalPhysicalLines) * 100

    Write-Host (
        "Comment-only share    : {0:N1}% of physical lines" -f
        $commentPercentage
    )

    Write-Host (
        "Blank-line share      : {0:N1}% of physical lines" -f
        $blankPercentage
    )
}

if ($Detailed) {
    Write-Host ""
    Write-Host "Largest Java files"
    Write-Host "------------------"

    $allJavaFiles = @(
        Get-ChildItem `
            -Path $ProductionJavaPath `
            -Recurse `
            -File `
            -Filter "*.java"

        Get-ChildItem `
            -Path $TestJavaPath `
            -Recurse `
            -File `
            -Filter "*.java"
    )

    $largestFiles = foreach ($file in $allJavaFiles) {
        # Use the same classifier for the detailed report so per-file figures
        # have exactly the same meaning as the project totals above.
        $statistics = Measure-JavaFile -File $file

        [PSCustomObject]@{
            Physical = $statistics.PhysicalLines
            Code     = $statistics.CodeLines
            Comments = $statistics.CommentOnlyLines
            Blank    = $statistics.BlankLines
            File     = $file.FullName.Substring(
                $RepositoryRoot.Length + 1
            )
        }
    }

    $largestFiles |
        Sort-Object Physical -Descending |
        Select-Object -First 20 |
        Format-Table -AutoSize
}

Write-Host ""
