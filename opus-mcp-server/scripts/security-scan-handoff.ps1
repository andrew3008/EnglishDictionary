<#
.SYNOPSIS
    Offline corporate-scanner handoff helper (Phase 6B).

.DESCRIPTION
    Validates that the dependency INVENTORY artifacts exist and prints guidance for submitting them
    to the approved corporate CVE/SCA scanner. This script does NOT call any scanner, requires NO
    network and NO credentials, and does NOT compute or claim a CVE verdict.

    Inventory is produced by:  .\gradlew.bat dependencySecurityReport   (or securityHandoff / releaseCheck)

.PARAMETER Help
    Show this help text and exit.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts/security-scan-handoff.ps1
#>
param([switch]$Help)

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    return
}

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$reportDir = Join-Path $root 'build\reports\supply-chain'
$jsonPath  = Join-Path $reportDir 'runtime-dependencies.json'
$txtPath   = Join-Path $reportDir 'runtime-dependencies.txt'

Write-Host 'Security scan handoff (INVENTORY ONLY - not a CVE verdict)' -ForegroundColor Cyan

$missing = @()
if (-not (Test-Path $jsonPath)) { $missing += $jsonPath }
if (-not (Test-Path $txtPath))  { $missing += $txtPath }

if ($missing.Count -gt 0) {
    Write-Host 'Inventory not found. Generate it first:' -ForegroundColor Yellow
    Write-Host '  .\gradlew.bat dependencySecurityReport'
    $missing | ForEach-Object { Write-Host "  missing: $_" -ForegroundColor Yellow }
    exit 1
}

try {
    $model = Get-Content -Raw -Path $jsonPath | ConvertFrom-Json
} catch {
    Write-Host "Inventory JSON is not valid JSON: $jsonPath" -ForegroundColor Red
    exit 1
}

$depCount = @($model.dependencies).Count
Write-Host "Inventory OK: $depCount runtime dependencies (schemaVersion $($model.schemaVersion))." -ForegroundColor Green
Write-Host "  JSON: $jsonPath"
Write-Host "  TXT : $txtPath"
Write-Host ''
Write-Host 'Next: submit runtime-dependencies.json to the approved corporate scanner for a CVE verdict.'
Write-Host 'Policy (FAIL/WARN/PASS) and suppression format: docs/SECURITY-SCAN-CONTRACT.md'
