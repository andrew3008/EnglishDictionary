# Official-прогон performance-матрицы на reference-лаборатории (Фаза 17).
#
#   .\run-official-matrix.ps1 [-DockerHost tcp://192.168.100.70:2375] [-RunsPerConfig 3]
#                             [-SkipSoak] [-SkipQueueSaturation]
#
# Порядок (ADR-performance-model):
#   1. M0 ×2 — калибровка шума Δ(M0,M0) ≤ 1%
#   2. Sign-off tier: m0,m4,m5,m5w,m6,m8a,m8b,m8c,m10 + queue-saturation ×2 (N прогонов)
#   3. M9 soak 60 мин (если не -SkipSoak)
#   4. Анализ: analyze-perf-run.ps1 + агрегация медианы
[CmdletBinding()]
param(
    [string]$DockerHost = "tcp://192.168.100.70:2375",
    [int]$RunsPerConfig = 3,
    [int]$Rate = 300,
    [int]$WarmupMin = 2,
    [int]$SteadyMin = 10,
    [switch]$SkipSoak,
    [switch]$SkipQueueSaturation,
    [string]$SessionStamp = ""
)

$ErrorActionPreference = "Stop"
$scriptDir = $PSScriptRoot
$moduleDir = Split-Path -Parent $scriptDir
$repoRoot = Split-Path -Parent $moduleDir
$runScript = Join-Path $scriptDir "run-perf-scenario.ps1"
$analyzeScript = Join-Path $scriptDir "analyze-perf-run.ps1"

if (-not $SessionStamp) {
    $SessionStamp = Get-Date -Format "yyyy-MM-dd_official"
}
$outRoot = Join-Path $repoRoot "docs\tracing\perf-results\$SessionStamp"
New-Item -ItemType Directory -Force -Path $outRoot | Out-Null

$logFile = Join-Path $outRoot "matrix-run.log"

function Get-LatestRunDir {
    param([string]$OutRoot, [string]$Scenario)
    Get-ChildItem $OutRoot -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Get-ChildItem $_.FullName -Directory -Filter $Scenario -ErrorAction SilentlyContinue } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
}

function Log($msg) {
    $line = "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $msg"
    Write-Host $line
    $line | Out-File -Append -Encoding utf8 $logFile
}

Log "=== Official matrix session: $SessionStamp"
Log "DockerHost=$DockerHost Rate=$Rate Warmup=${WarmupMin}m Steady=${SteadyMin}m RunsPerConfig=$RunsPerConfig"

# --- Фаза 1: калибровка шума M0/M0 -------------------------------------------------------
Log "--- Phase 1: M0/M0 noise calibration (2 runs) ---"
$m0NoiseRuns = @()
for ($i = 1; $i -le 2; $i++) {
    Log "M0 noise run $i/2"
    & $runScript -Scenario m0 -Rate $Rate -WarmupMin $WarmupMin -SteadyMin $SteadyMin `
        -DockerHost $DockerHost -OutRoot $outRoot
        if ($LASTEXITCODE -ne 0) { Log "WARN: M0 noise run $i exit=$LASTEXITCODE (продолжаем)" }
    $latest = Get-LatestRunDir -OutRoot $outRoot -Scenario "m0"
    & $analyzeScript -RunDir $latest.FullName
    $m0NoiseRuns += $latest.FullName
}

# Сравнение шума между двумя M0
$s0 = Get-Content (Join-Path $m0NoiseRuns[0] "summary.json") -Raw | ConvertFrom-Json
$s1 = Get-Content (Join-Path $m0NoiseRuns[1] "summary.json") -Raw | ConvertFrom-Json
$cpu0 = $s0.analysis.run.cpuUtilization
$cpu1 = $s1.analysis.run.cpuUtilization
$noisePct = if ($cpu0 -gt 0) { [math]::Abs(($cpu1 - $cpu0) / $cpu0) * 100 } else { 0 }
Log "M0/M0 noise: cpuUtil $cpu0 vs $cpu1 => delta ${noisePct}% (threshold 1%)"
if ($noisePct -gt 1.0) {
    Log "WARN: лаборатория непригодна для 3%-SLA (шум > 1%). Продолжаем с предупреждением."
}

# Baseline M0 = медиана из RunsPerConfig прогонов (включая noise runs)
$baselineDir = $m0NoiseRuns[0]  # будет обновлён после фазы 2

# --- Фаза 2: sign-off tier ----------------------------------------------------------------
$signOffScenarios = @(
    @{ id = "m0";  runs = $RunsPerConfig; jfr = $false; steady = $SteadyMin },
    @{ id = "m4";  runs = $RunsPerConfig; jfr = $false; steady = $SteadyMin },
    @{ id = "m5";  runs = $RunsPerConfig; jfr = $true;  steady = $SteadyMin },
    @{ id = "m5w"; runs = 1;             jfr = $false; steady = $SteadyMin },
    @{ id = "m6";  runs = $RunsPerConfig; jfr = $true;  steady = $SteadyMin },
    @{ id = "m8a"; runs = $RunsPerConfig; jfr = $false; steady = $SteadyMin },
    @{ id = "m8b"; runs = $RunsPerConfig; jfr = $false; steady = $SteadyMin },
    @{ id = "m8c"; runs = $RunsPerConfig; jfr = $false; steady = $SteadyMin },
    @{ id = "m10"; runs = $RunsPerConfig; jfr = $false; steady = $SteadyMin }
)
if (-not $SkipQueueSaturation) {
    $signOffScenarios += @(
        @{ id = "queue-saturation";          runs = $RunsPerConfig; jfr = $false; steady = 5 },
        @{ id = "queue-saturation-upstream";  runs = $RunsPerConfig; jfr = $false; steady = 5 }
    )
}

$allResults = @{}  # scenario -> list of run dirs

foreach ($sc in $signOffScenarios) {
    Log "--- Scenario $($sc.id): $($sc.runs) run(s) ---"
    $dirs = @()
    for ($r = 1; $r -le $sc.runs; $r++) {
        Log "$($sc.id) run $r/$($sc.runs)"
        $jfrFlag = if ($sc.jfr) { @{ Jfr = $true } } else { @{} }
        & $runScript -Scenario $sc.id -Rate $Rate -WarmupMin $WarmupMin -SteadyMin $sc.steady `
            -DockerHost $DockerHost -OutRoot $outRoot @jfrFlag
        if ($LASTEXITCODE -ne 0) {
            Log "FAIL: $($sc.id) run $r exit=$LASTEXITCODE"
        }
        $latest = Get-LatestRunDir -OutRoot $outRoot -Scenario $sc.id
        if ($sc.id -eq "m0") {
            & $analyzeScript -RunDir $latest.FullName
        } else {
            & $analyzeScript -RunDir $latest.FullName -BaselineDir $baselineDir
        }
        $dirs += $latest.FullName
    }
    $allResults[$sc.id] = $dirs
}

# M0 baseline = медиана cpuUtil из всех m0 прогонов
$m0Analyses = $allResults["m0"] | ForEach-Object {
    (Get-Content (Join-Path $_ "summary.json") -Raw | ConvertFrom-Json).analysis.run
}
$baselineDir = $allResults["m0"][[int][math]::Floor($m0Analyses.Count / 2)]
Log "Baseline M0 (median run): $baselineDir"

# --- Фаза 3: M9 soak -----------------------------------------------------------------------
if (-not $SkipSoak) {
    Log "--- M9 soak 60 min ---"
    & $runScript -Scenario m9 -Rate $Rate -WarmupMin $WarmupMin -SteadyMin 60 -Jfr `
        -DockerHost $DockerHost -OutRoot $outRoot
    $latest = Get-LatestRunDir -OutRoot $outRoot -Scenario "m9"
    & $analyzeScript -RunDir $latest.FullName -BaselineDir $baselineDir
    $allResults["m9"] = @($latest.FullName)
}

# --- Фаза 4: агрегация session-summary.json ----------------------------------------------
function Median($values) {
    $sorted = $values | Sort-Object
    $n = $sorted.Count
    if ($n -eq 0) { return $null }
    if ($n % 2 -eq 1) { return $sorted[[int]($n / 2)] }
    return ($sorted[$n / 2 - 1] + $sorted[$n / 2]) / 2
}

$sessionSummary = [ordered]@{
    sessionStamp = $SessionStamp
    dockerHost   = $DockerHost
    rate         = $Rate
    warmupMin    = $WarmupMin
    steadyMin    = $SteadyMin
    runsPerConfig = $RunsPerConfig
    m0NoisePct   = [math]::Round($noisePct, 2)
    baselineDir  = $baselineDir
    scenarios    = @{}
}

foreach ($scId in $allResults.Keys) {
    $metrics = $allResults[$scId] | ForEach-Object {
        $s = Get-Content (Join-Path $_ "summary.json") -Raw | ConvertFrom-Json
        [ordered]@{
            dir = $_
            runValid = $s.runValid
            cpuUtil = $s.analysis.run.cpuUtilization
            rssAvgMb = $s.analysis.run.rssAvgMb
            rssSlope = $s.analysis.run.rssSlopeMbPerMin
            p99Ms = $s.analysis.run.p99Ms
            deltaCpuPct = $s.analysis.delta.cpuPct
            deltaRssPct = $s.analysis.delta.rssPct
            deltaP99Ms = $s.analysis.delta.p99Ms
        }
    }
    $valid = @($metrics | Where-Object { $_.runValid })
    $sessionSummary.scenarios[$scId] = [ordered]@{
        runs = $metrics
        median = [ordered]@{
            cpuUtil = Median ($valid.cpuUtil)
            rssAvgMb = Median ($valid.rssAvgMb)
            rssSlope = Median ($valid.rssSlope)
            p99Ms = Median ($valid.p99Ms)
            deltaCpuPct = Median ($valid.deltaCpuPct)
            deltaRssPct = Median ($valid.deltaRssPct)
            deltaP99Ms = Median ($valid.deltaP99Ms)
        }
        validRuns = $valid.Count
        totalRuns = $metrics.Count
    }
}

$sessionSummary | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 (Join-Path $outRoot "session-summary.json")
Log "=== Session complete: $outRoot\session-summary.json"
