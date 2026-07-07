# PR-9H-D — Remote/local Docker k6 smoke runner
#
#   .\run-perf-smoke.ps1 [-DockerHost tcp://192.168.100.70:2375] `
#                        [-BaseUrl http://192.168.x.x:8080] `
#                        [-Duration 30s] [-TargetRps 5] [-SpringProfile smoke]
#
# Scenarios are baked into the image (COPY) — required for remote Docker daemon.
[CmdletBinding()]
param(
    [string]$DockerHost = "",
    [string]$BaseUrl = "http://host.docker.internal:8080",
    [string]$Duration = "30s",
    [int]$TargetRps = 5,
    [string]$SpringProfile = "smoke",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$moduleDir = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleDir

if ($DockerHost) { $env:DOCKER_HOST = $DockerHost }

$runStamp = Get-Date -Format "yyyyMMdd-HHmmss"
if (-not $OutDir) {
    $OutDir = Join-Path $moduleDir "build\perf-results\smoke\$runStamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$gitSha = "unknown"
try {
    $gitSha = (git -C $repoRoot rev-parse --short HEAD 2>$null)
    if (-not $gitSha) { $gitSha = "unknown" }
} catch { $gitSha = "unknown" }

Write-Host "=== perf-harness SMOKE (Docker) profile=$SpringProfile baseUrl=$BaseUrl duration=$Duration rps=$TargetRps"
Write-Host "=== Artifacts: $OutDir"

& (Join-Path $repoRoot "gradlew.bat") ":platform-tracing-perf-harness:prepareK6SmokeDockerContext" -q
if ($LASTEXITCODE -ne 0) { throw "prepareK6SmokeDockerContext failed" }

$contextDir = Join-Path $moduleDir "build\docker\k6-smoke"
$imageTag = "platform-tracing-perf-harness-k6-smoke:local"
docker build -t $imageTag $contextDir
if ($LASTEXITCODE -ne 0) { throw "docker build failed" }

$k6SummaryInContainer = "/artifacts/k6-summary.json"
$createArgs = @(
    "create",
    "-e", "BASE_URL=$BaseUrl",
    "-e", "DURATION=$Duration",
    "-e", "TARGET_RPS=$TargetRps",
    "-e", "SCENARIO=smoke",
    "-e", "SPRING_PROFILE=$SpringProfile",
    $imageTag,
    "run", "/scenarios/smoke.js",
    "--summary-export", $k6SummaryInContainer
)
$cid = docker @createArgs
if ($LASTEXITCODE -ne 0) { throw "docker create failed" }
$cid = $cid.Trim()

try {
    docker start -a $cid
    if ($LASTEXITCODE -ne 0) { throw "k6 smoke failed (exit $LASTEXITCODE)" }
    docker cp "${cid}:${k6SummaryInContainer}" (Join-Path $OutDir "k6-summary.json")
    if ($LASTEXITCODE -ne 0) { throw "docker cp k6-summary failed" }
} finally {
    docker rm -f $cid 2>$null | Out-Null
}

$runner = if ($DockerHost) { "docker-remote" } else { "docker-local" }
$summary = @{
    evidenceTier     = "SMOKE"
    nonAuthoritative = $true
    w004Eligible     = $false
    profileId        = "local-dev"
    runId            = $runStamp
    scenario         = "smoke"
    springProfile    = $SpringProfile
    baseUrl          = $BaseUrl
    duration         = $Duration
    targetRps        = $TargetRps
    k6Runner         = $runner
    gitSha           = $gitSha
    k6Summary        = "k6-summary.json"
    note             = "Functional smoke only — not W-004 or pre-prod evidence"
}
$summary | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $OutDir "summary.json")

@"
docker build -t $imageTag $contextDir
docker create -e BASE_URL=$BaseUrl -e DURATION=$Duration -e TARGET_RPS=$TargetRps $imageTag run /scenarios/smoke.js --summary-export $k6SummaryInContainer
"@ | Set-Content -Encoding UTF8 (Join-Path $OutDir "command.txt")

Write-Host "=== SMOKE complete (non-authoritative): $OutDir"
