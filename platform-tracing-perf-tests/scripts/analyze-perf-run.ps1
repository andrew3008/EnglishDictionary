# Анализ артефактов перф-прогона (Фаза 17, PR-4): вычисление метрик гейтов.
#
#   .\analyze-perf-run.ps1 -RunDir <...>\2026-06-10_120000\m5 [-BaselineDir <...>\m0]
#
# Вычисляет:
#   - RSS: avg/max за steady-окно + линейный slope (метод наименьших квадратов, MB/min) —
#     гейт soak-rss-slope (M9: slope <= ~1 MB/min);
#   - CPU: суммарные utime+stime тики за steady-окно -> CPU-секунды (clock tick = 100Hz);
#   - при -BaselineDir: Δ CPU % и Δ RSS % vs baseline (гейты m5-cpu < 3%, m5-rss < 10%)
#     и Δ p99 vs baseline (гейт degraded-p99 <= +50ms);
#   - дописывает блок analysis в summary.json прогона.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RunDir,
    [string]$BaselineDir = ""
)

$ErrorActionPreference = "Stop"

function Read-RunMetrics {
    param([string]$dir)
    $summary = Get-Content (Join-Path $dir "summary.json") -Raw | ConvertFrom-Json
    $samples = Import-Csv (Join-Path $dir "rss-cpu-samples.csv")
    if ($samples.Count -lt 4) { throw "Слишком мало сэмплов в $dir — прогон невалиден" }

    # Steady-окно: отбрасываем warmup-фазу по времени первого сэмпла.
    $t0 = [long]$samples[0].epochMs
    $steadyStartMs = $t0 + [long]$summary.warmupMin * 60000
    $steady = @($samples | Where-Object { [long]$_.epochMs -ge $steadyStartMs })
    if ($steady.Count -lt 3) { throw "Steady-окно в $dir содержит < 3 сэмплов" }

    # CPU-секунды за steady-окно: дельта (utime+stime) в тиках / 100 Hz.
    $first = $steady[0]; $last = $steady[-1]
    $cpuTicks = ([long]$last.utimeTicks + [long]$last.stimeTicks) - ([long]$first.utimeTicks + [long]$first.stimeTicks)
    $windowSec = ([long]$last.epochMs - [long]$first.epochMs) / 1000.0
    $cpuSeconds = $cpuTicks / 100.0
    # Утилизация процесса (долей ядра): сравнима между прогонами при равном rate.
    $cpuUtilization = if ($windowSec -gt 0) { $cpuSeconds / $windowSec } else { 0 }

    # RSS: среднее/максимум + линейный slope (наименьшие квадраты) в MB/min.
    $xs = $steady | ForEach-Object { ([long]$_.epochMs - $steadyStartMs) / 60000.0 }   # минуты
    $ys = $steady | ForEach-Object { [double]$_.vmRssKb / 1024.0 }                     # MB
    $n = $xs.Count
    $sumX = ($xs | Measure-Object -Sum).Sum; $sumY = ($ys | Measure-Object -Sum).Sum
    $sumXY = 0.0; $sumXX = 0.0
    for ($i = 0; $i -lt $n; $i++) { $sumXY += $xs[$i] * $ys[$i]; $sumXX += $xs[$i] * $xs[$i] }
    $denom = ($n * $sumXX - $sumX * $sumX)
    $slope = if ([math]::Abs($denom) -gt 1e-9) { ($n * $sumXY - $sumX * $sumY) / $denom } else { 0 }

    # p99 steady-фазы из k6 summary (метрика http_req_duration{phase:steady} при наличии).
    $p99 = $null
    $k6File = Join-Path $dir "k6-summary.json"
    if (Test-Path $k6File) {
        $k6 = Get-Content $k6File -Raw | ConvertFrom-Json
        $trend = $k6.metrics.'http_req_duration{phase:steady}'
        if (-not $trend) { $trend = $k6.metrics.http_req_duration }
        if ($trend) { $p99 = [double]$trend.'p(99)' }
    }

    [ordered]@{
        cpuSeconds     = [math]::Round($cpuSeconds, 2)
        cpuUtilization = [math]::Round($cpuUtilization, 4)
        rssAvgMb       = [math]::Round(($ys | Measure-Object -Average).Average, 1)
        rssMaxMb       = [math]::Round(($ys | Measure-Object -Maximum).Maximum, 1)
        rssSlopeMbPerMin = [math]::Round($slope, 3)
        steadySamples  = $n
        p99Ms          = if ($null -ne $p99) { [math]::Round($p99, 2) } else { $null }
    }
}

$run = Read-RunMetrics -dir $RunDir
$analysis = [ordered]@{ run = $run }

if ($BaselineDir) {
    $base = Read-RunMetrics -dir $BaselineDir
    $analysis.baseline = $base
    $analysis.delta = [ordered]@{
        # Δ CPU % — по утилизации процесса при равном arrival rate (определение REQ-PERF-CPU-001).
        cpuPct = if ($base.cpuUtilization -gt 0) {
            [math]::Round((($run.cpuUtilization - $base.cpuUtilization) / $base.cpuUtilization) * 100, 2)
        } else { $null }
        rssPct = if ($base.rssAvgMb -gt 0) {
            [math]::Round((($run.rssAvgMb - $base.rssAvgMb) / $base.rssAvgMb) * 100, 2)
        } else { $null }
        p99Ms = if ($null -ne $run.p99Ms -and $null -ne $base.p99Ms) {
            [math]::Round($run.p99Ms - $base.p99Ms, 2)
        } else { $null }
    }
}

# Дописываем блок analysis в summary.json прогона.
$summaryFile = Join-Path $RunDir "summary.json"
$summary = Get-Content $summaryFile -Raw | ConvertFrom-Json
$summary | Add-Member -NotePropertyName analysis -NotePropertyValue $analysis -Force
$summary | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 $summaryFile

Write-Host "=== Анализ $RunDir"
$analysis | ConvertTo-Json -Depth 4 | Write-Host
