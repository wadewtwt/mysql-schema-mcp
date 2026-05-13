param(
    [string]$Path = (Get-Location).Path,
    [switch]$Apply
)

$ErrorActionPreference = "Stop"

function Get-JavaMajorVersion {
    if ($env:JAVA_HOME) {
        $releaseFile = Join-Path $env:JAVA_HOME "release"
        if (Test-Path $releaseFile) {
            $line = Get-Content -Path $releaseFile | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
            if ($line -match '"1\.(?<legacy>\d+)') {
                return [int]$matches['legacy']
            }
            if ($line -match '"(?<v>\d+)') {
                return [int]$matches['v']
            }
        }
    }

    $versionOutput = @()
    try {
        $versionOutput = & java -version 2>&1 | ForEach-Object { "$_" }
    } catch {
        return $null
    }
    if (-not $versionOutput) {
        return $null
    }

    $firstLine = [string]($versionOutput | Select-Object -First 1)
    if ($firstLine -match '"1\.(?<legacy>\d+)') {
        return [int]$matches['legacy']
    }
    if ($firstLine -match '"(?<v>\d+)') {
        return [int]$matches['v']
    }

    return $null
}

function Resolve-RepoRoot {
    if ($PSScriptRoot) {
        return Split-Path -Parent $PSScriptRoot
    }
    $scriptPath = $MyInvocation.MyCommand.Path
    if ($scriptPath) {
        $scriptDir = Split-Path -Parent $scriptPath
        return Split-Path -Parent $scriptDir
    }
    throw "Unable to resolve repo root."
}

function Get-ExpectedJavaForPath {
    param(
        [string]$RepoRoot,
        [string]$TargetPath
    )

    $platformPath = Join-Path $RepoRoot "chimeta-platform"
    if ($TargetPath.StartsWith($platformPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        return 17
    }
    return 8
}

function Get-SuggestedJavaHome {
    param(
        [int]$Major
    )

    if ($Major -eq 17) {
        return "D:\Scoop\apps\openjdk17\current"
    }
    return "D:\Scoop\apps\openjdk8-redhat\current"
}

$repoRoot = Resolve-RepoRoot
$targetPath = [System.IO.Path]::GetFullPath($Path)
$expectedMajor = Get-ExpectedJavaForPath -RepoRoot $repoRoot -TargetPath $targetPath
$detectedMajor = Get-JavaMajorVersion
$javaHome = $env:JAVA_HOME

Write-Host "Repo root: $repoRoot"
Write-Host "Target path: $targetPath"
Write-Host "Expected Java: $expectedMajor"
Write-Host "Detected Java: $detectedMajor"
Write-Host "JAVA_HOME: $javaHome"

if ($detectedMajor -eq $expectedMajor) {
    Write-Host "JAVA_OK=TRUE"
    exit 0
}

$targetJavaHome = Get-SuggestedJavaHome -Major $expectedMajor
Write-Host "JAVA_OK=FALSE"
Write-Host "Suggested switch commands:"
Write-Host "`$env:JAVA_HOME='$targetJavaHome'"
Write-Host "`$env:Path='$targetJavaHome\bin;' + (`$env:Path -split ';' | Where-Object { `$_ -notmatch 'openjdk17\\\\current\\\\bin|openjdk8-redhat\\\\current\\\\bin' } | ForEach-Object { `$_ }) -join ';'"

if ($Apply) {
    $pathParts = $env:Path -split ';' | Where-Object { $_ -and ($_ -notmatch 'openjdk17\\current\\bin|openjdk8-redhat\\current\\bin') }
    $env:JAVA_HOME = $targetJavaHome
    $env:Path = "$targetJavaHome\bin;" + ($pathParts -join ';')
    $afterMajor = Get-JavaMajorVersion
    Write-Host "Applied switch in current shell. New detected Java: $afterMajor"
    if ($afterMajor -ne $expectedMajor) {
        Write-Error "Apply failed: expected Java $expectedMajor, got $afterMajor"
        exit 1
    }
    exit 0
}

exit 1
