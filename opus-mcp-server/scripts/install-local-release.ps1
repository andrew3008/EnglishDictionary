<#
.SYNOPSIS
    Optional, offline local-release installer (Phase 6C).

.DESCRIPTION
    Copies the release fat-jar, its SHA-256 checksum, and the release manifest into a versioned local
    distribution directory and updates a 'current' pointer copy. Prints the jar path to reference from
    Cursor mcp.json.

    Safety: no admin rights, no network, no credentials, no Cursor config modification, no deletion of
    existing releases. Use -DryRun to preview without writing anything. Build the artifacts first:
        .\gradlew.bat releasePackageCheck

.PARAMETER TargetRoot
    Local distribution root. Default: E:\Platform_Tools\opus-mcp-server

.PARAMETER DryRun
    Print planned actions without copying.

.PARAMETER Help
    Show this help and exit.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/install-local-release.ps1 -DryRun

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/install-local-release.ps1 -TargetRoot D:\tools\opus-mcp
#>
param(
    [string]$TargetRoot = 'E:\Platform_Tools\opus-mcp-server',
    [switch]$DryRun,
    [switch]$Help
)

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    return
}

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $root 'build\distributions\release-manifest.json'

if (-not (Test-Path $manifestPath)) {
    Write-Host "Release manifest not found: $manifestPath" -ForegroundColor Yellow
    Write-Host 'Build the release package first:' -ForegroundColor Yellow
    Write-Host '  .\gradlew.bat releasePackageCheck'
    exit 1
}

$manifest = Get-Content -Raw -Path $manifestPath | ConvertFrom-Json
$version  = $manifest.version
$artifact = $manifest.artifact

$jarPath = Join-Path $root ("build\libs\" + $artifact)
$checksumPath = Join-Path $root ("build\distributions\checksums\" + $artifact + ".sha256")

foreach ($p in @($jarPath, $checksumPath)) {
    if (-not (Test-Path $p)) {
        Write-Host "Required artifact missing: $p" -ForegroundColor Red
        Write-Host '  Run: .\gradlew.bat releasePackageCheck'
        exit 1
    }
}

$releaseDir = Join-Path $TargetRoot ("releases\" + $version)
$currentDir = Join-Path $TargetRoot 'current'
$currentJar = Join-Path $currentDir 'opus-mcp-server-current.jar'

Write-Host "opus-mcp-server local release install" -ForegroundColor Cyan
Write-Host "  version    : $version"
Write-Host "  artifact   : $artifact"
Write-Host "  releaseDir : $releaseDir"
Write-Host "  currentJar : $currentJar"
if ($DryRun) { Write-Host "  mode       : DRY-RUN (no files written)" -ForegroundColor Yellow }

$plan = @(
    @{ src = $jarPath;      dst = (Join-Path $releaseDir $artifact) },
    @{ src = $checksumPath; dst = (Join-Path $releaseDir ($artifact + '.sha256')) },
    @{ src = $manifestPath; dst = (Join-Path $releaseDir 'release-manifest.json') }
)

if ($DryRun) {
    Write-Host 'Planned actions:'
    Write-Host "  mkdir $releaseDir"
    $plan | ForEach-Object { Write-Host ("  copy {0} -> {1}" -f $_.src, $_.dst) }
    Write-Host "  mkdir $currentDir"
    Write-Host ("  copy {0} -> {1}" -f $jarPath, $currentJar)
    Write-Host ''
    Write-Host "Cursor mcp.json should reference: $currentJar (or the versioned jar)."
    return
}

New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null
$plan | ForEach-Object { Copy-Item -Path $_.src -Destination $_.dst -Force }
New-Item -ItemType Directory -Force -Path $currentDir | Out-Null
Copy-Item -Path $jarPath -Destination $currentJar -Force

Write-Host 'Installed.' -ForegroundColor Green
Write-Host "Reference this jar from Cursor mcp.json (-jar argument):"
Write-Host "  $currentJar"
Write-Host 'Then restart the MCP server in Cursor. Cursor config is NOT modified automatically.'
