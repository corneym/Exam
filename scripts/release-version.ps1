function Test-ReleaseVersion {
    param (
        [string] $Candidate
    )

    # Windows Installer compares exactly three numeric components.
    # Reject leading zeroes, suffixes and out-of-range values.
    if ($Candidate -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
        return $false
    }

    $segments = $Candidate.Split(".")
    $numbers = @()

    foreach ($segment in $segments) {
        $number = 0

        if (-not [int]::TryParse($segment, [ref] $number)) {
            return $false
        }

        $numbers += $number
    }

    return (
        $numbers[0] -le 255 `
        -and $numbers[1] -le 255 `
        -and $numbers[2] -le 65535
    )
}

function Test-ProjectVersion {
    param (
        [string] $Candidate
    )

    if (Test-ReleaseVersion -Candidate $Candidate) {
        return $true
    }

    # Historical releases such as 1.0 remain valid as current POM versions.
    # Future release targets must nevertheless contain three components.
    if ($Candidate -cmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
        return (Test-ReleaseVersion -Candidate "$Candidate.0")
    }

    return $false
}

function ConvertTo-VersionTriplet {
    param (
        [Parameter(Mandatory = $true)]
        [string] $Version
    )

    if (-not (Test-ProjectVersion -Candidate $Version)) {
        throw "Invalid project version: $Version"
    }

    if ($Version.Split(".").Count -eq 2) {
        return "$Version.0"
    }

    return $Version
}

function Compare-ReleaseVersions {
    param (
        [Parameter(Mandatory = $true)]
        [string] $Left,

        [Parameter(Mandatory = $true)]
        [string] $Right
    )

    $leftParts =
        (ConvertTo-VersionTriplet -Version $Left).Split(".")

    $rightParts =
        (ConvertTo-VersionTriplet -Version $Right).Split(".")

    for ($index = 0; $index -lt 3; $index++) {
        $leftNumber = [int] $leftParts[$index]
        $rightNumber = [int] $rightParts[$index]

        if ($leftNumber -lt $rightNumber) {
            return -1
        }

        if ($leftNumber -gt $rightNumber) {
            return 1
        }
    }

    return 0
}

function Get-NextPatchVersion {
    param (
        [Parameter(Mandatory = $true)]
        [string] $CurrentVersion
    )

    $parts =
        (ConvertTo-VersionTriplet -Version $CurrentVersion).Split(".")

    $major = [int] $parts[0]
    $minor = [int] $parts[1]
    $patch = [int] $parts[2]

    if ($patch -ge 65535) {
        throw "Patch version has reached the Windows Installer limit. Select a higher minor or major version explicitly."
    }

    # The default release operation increments PATCH.
    # MINOR and MAJOR releases must be selected explicitly.
    return "$major.$minor.$($patch + 1)"
}
