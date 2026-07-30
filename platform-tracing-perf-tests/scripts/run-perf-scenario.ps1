# Раннер перф-сценария (Фаза 17, PR-3): полный жизненный цикл одного прогона Mx/S1.
#
#   .\run-perf-scenario.ps1 -Scenario m5 [-Rate 500] [-WarmupMin 2] [-SteadyMin 10] [-Jfr]
#                           [-DockerHost tcp://192.168.100.70:2375] [-OutRoot ..\..\docs\tracing\perf-results]
#
# Жизненный цикл:
#   1. Сборка SUT-контекста (gradle prepareSutDockerContext) и образов compose.
#   2. Запуск collector + SUT; замер S1 (время до первого health 200).
#   3. Фоновое сэмплирование RSS (/proc/1/status) и CPU (/proc/1/stat) каждые 5с.
#   4. Запуск k6 (open model); exit code k6 = validity gate (dropped_iterations и т.д.).
#   5. Сбор артефактов (docker cp): gc.log, *.jfr, k6-summary.json + rss/cpu csv.
#   6. summary.json с метаданными (gitSha, scenario, rate, окно steady state).
#
# Validity gates прогона (performance-test-matrix): прогон НЕВАЛИДЕН, если k6 завершился
# с ненулевым кодом, отсутствуют GC-логи/сэмплы или steady state короче заявленного.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Scenario,
    [int]$Rate = 500,
    [int]$WarmupMin = 2,
    [int]$SteadyMin = 10,
    [switch]$Jfr,
    [string]$DockerHost = "",
    [string]$OutRoot = ""
)

$ErrorActionPreference = "Stop"
$moduleDir = Split-Path -Parent $PSScriptRoot
$repoRoot = Split-Path -Parent $moduleDir

if ($DockerHost) { $env:DOCKER_HOST = $DockerHost }

# --- 1. Конфигурация сценария ------------------------------------------------------------
$envFile = Join-Path $moduleDir "scenarios\$Scenario.env"
if (-not (Test-Path $envFile)) {
    throw "Неизвестный сценарий '$Scenario': нет файла $envFile"
}
# Clear scenario-scoped flags before load. `& run-perf-scenario.ps1` shares the caller
# process env, so m10's PERF_RELOAD_SEQUENCE must not leak into m10c/m10d.
@(
    'PERF_RELOAD_SEQUENCE',
    'PERF_RELOAD_STORM',
    'PERF_RELOAD_STORM_INTERVAL_SEC',
    'PERF_SAMPLE_EXPORT_METRICS',
    'PERF_TOXIPROXY',
    'PERF_BACKPRESSURE',
    'PERF_COMPOSE_PROFILES',
    'PERF_SCENARIO_JVM_OPTS',
    'SCENARIO_ID',
    'SUT_CPUSET',
    'COLLECTOR_CPUSET',
    'K6_CPUSET'
) | ForEach-Object { Remove-Item "env:$_" -ErrorAction SilentlyContinue }
# Переменные сценария экспортируются в процесс: compose подставит их при интерполяции.
Get-Content $envFile | Where-Object { $_ -match '^\s*[^#=]+=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "env:$($name.Trim())" -Value $value.Trim()
}
$env:RATE = "$Rate"
$env:WARMUP_MIN = "$WarmupMin"
$env:STEADY_MIN = "$SteadyMin"

if ($Jfr) {
    # JFR пишется в /perf-artifacts и забирается docker cp на шаге 5.
    $env:PERF_SCENARIO_JVM_OPTS = "$($env:PERF_SCENARIO_JVM_OPTS) " +
        "-XX:StartFlightRecording=filename=/perf-artifacts/run.jfr,settings=profile,maxsize=256m,dumponexit=true"
}

$gitSha = "unknown"
try {
    $gitSha = (git -C $repoRoot rev-parse --short HEAD 2>$null)
    if (-not $gitSha) { $gitSha = "unknown" }
} catch { $gitSha = "unknown" }
$runStamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
if (-not $OutRoot) { $OutRoot = Join-Path $repoRoot "docs\tracing\perf-results" }
$outDir = Join-Path $OutRoot "$runStamp\$Scenario"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Write-Host "=== Perf run: scenario=$Scenario rate=$Rate warmup=${WarmupMin}m steady=${SteadyMin}m sha=$gitSha"
Write-Host "=== Артефакты: $outDir"

$composeBase = @(
    "compose",
    "-f", (Join-Path $moduleDir "docker-compose.perf.yml"),
    "--project-directory", $moduleDir,
    "-p", "perf-$Scenario"
)
function Invoke-Compose {
    param([string[]]$ComposeArgs, [switch]$AllowFail)
    $all = $composeBase + $ComposeArgs
    # Out-Host: иначе stdout docker compose попадает в return pipeline и ломает $k6Exit.
    # 2>&1 + Continue: docker пишет прогресс в stderr; при Stop это NativeCommandError.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker @all 2>&1 | Out-Host } finally { $ErrorActionPreference = $prevEap }
    $exit = $LASTEXITCODE
    if (-not $AllowFail -and $exit -ne 0) {
        throw "docker compose $($ComposeArgs -join ' ') failed ($exit)"
    }
    return $exit
}

# Start-Job is starved while this script blocks on docker/k6 console I/O (Windows PS 5.1).
# Workers run as separate powershell.exe processes.
$workerDir = Join-Path $PSScriptRoot "workers"
$psExe = Join-Path $env:WINDIR "System32\WindowsPowerShell\v1.0\powershell.exe"
function Start-WorkerProcess {
    param([Parameter(Mandatory = $true)][string]$ScriptName, [Parameter(Mandatory = $true)][string[]]$WorkerArgs)
    $scriptPath = Join-Path $workerDir $ScriptName
    if (-not (Test-Path $scriptPath)) { throw "Worker script missing: $scriptPath" }
    $argList = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $scriptPath) + $WorkerArgs
    return Start-Process -FilePath $psExe -ArgumentList $argList -PassThru -WindowStyle Hidden
}
function Stop-WorkerProcess {
    param($Process)
    if (-not $Process) { return }
    try {
        $Process.Refresh()
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
        }
    } catch { }
}
function Stop-StrayPerfWorkers {
    # Host-side workers talk to published :18080; a leaked m10c storm must not mutate the next SUT.
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.CommandLine -and (
                $_.CommandLine -match 'reload-storm-worker\.ps1' -or
                $_.CommandLine -match 'reload-sequence-worker\.ps1' -or
                $_.CommandLine -match 'rss-cpu-sampler-worker\.ps1' -or
                $_.CommandLine -match 'export-metrics-sampler-worker\.ps1'
            )
        } |
        ForEach-Object {
            Write-Host "=== stopping stray perf worker pid=$($_.ProcessId)"
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        }
}
$samplerProc = $null
$flakyJob = $null
$exportMetricsProc = $null
$reloadProc = $null
$stormProc = $null
try {
    Stop-StrayPerfWorkers
    # --- 2. Сборка и запуск стенда -------------------------------------------------------
    & (Join-Path $repoRoot "gradlew.bat") ":platform-tracing-perf-tests:prepareSutDockerContext" -q
    if ($LASTEXITCODE -ne 0) { throw "prepareSutDockerContext failed" }

    # Деградационные сценарии (PR-4) подключают профиль degraded (toxiproxy/backpressure).
    if ($env:PERF_COMPOSE_PROFILES) { $composeBase += @("--profile", $env:PERF_COMPOSE_PROFILES) }

    Invoke-Compose @("build")
    Invoke-Compose @("up", "-d", "collector")
    # Ожидание готовности Collector (distroless — без wget; healthcheck = validate config + running).
    for ($i = 0; $i -lt 60; $i++) {
        $state = docker inspect --format '{{.State.Status}}' perf-collector 2>$null
        if ($state -eq 'running') { Start-Sleep -Seconds 3; break }
        Start-Sleep -Seconds 2
    }

    # --- 2a. Toxiproxy (M8a slow / M8c flaky / queue saturation) --------------------------
    if ($env:PERF_TOXIPROXY) {
        Invoke-Compose @("up", "-d", "toxiproxy")
        $toxiHost = if ($env:DOCKER_HOST -match 'tcp://([^:]+)') { $Matches[1] } else { "127.0.0.1" }
        $toxiApi = "http://${toxiHost}:18474"
        # PowerShell UA блокируется на reference-лабе (User agent not allowed); явный UA обязателен.
        $toxiHeaders = @{ 'User-Agent' = 'platform-tracing-perf/1.0' }
        function Invoke-ToxiApi {
            param([string]$Method, [string]$Uri, [string]$Body = "")
            $p = @{ Method = $Method; Uri = $Uri; Headers = $toxiHeaders }
            if ($Body) { $p.Body = $Body; $p.ContentType = 'application/json' }
            Invoke-RestMethod @p | Out-Null
        }
        # Прокси otlp: toxiproxy:4318 -> collector:4318.
        $proxyBody = '{"name":"otlp","listen":"0.0.0.0:4318","upstream":"collector:4318"}'
        for ($i = 0; $i -lt 30; $i++) {
            try { Invoke-ToxiApi -Method Post -Uri "$toxiApi/proxies" -Body $proxyBody; break }
            catch { Start-Sleep -Milliseconds 500 }
        }
        if ($env:PERF_TOXIPROXY -eq 'latency') {
            # M8a: задержка 200ms +/- 50ms на downstream-пути (медленный Collector).
            $toxic = '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":200,"jitter":50}}'
            try { Invoke-ToxiApi -Method Post -Uri "$toxiApi/proxies/otlp/toxics" -Body $toxic }
            catch { Invoke-ToxiApi -Method Delete -Uri "$toxiApi/proxies/otlp/toxics/slow"; Invoke-ToxiApi -Method Post -Uri "$toxiApi/proxies/otlp/toxics" -Body $toxic }
            Write-Host "=== toxiproxy: latency toxic 200ms активен"
        }
        if ($env:PERF_TOXIPROXY -eq 'flaky') {
            # M8c: чередование 10с timeout / 10с healthy — осцилляции ретраев OTLP-клиента.
            $flakyJob = Start-Job -ScriptBlock {
                param($api, $headers)
                $toxic = '{"name":"outage","type":"timeout","stream":"downstream","attributes":{"timeout":1}}'
                while ($true) {
                    try { Invoke-RestMethod -Method Post -Uri "$api/proxies/otlp/toxics" -Body $toxic -ContentType 'application/json' -Headers $headers | Out-Null } catch { }
                    Start-Sleep -Seconds 10
                    try { Invoke-RestMethod -Method Delete -Uri "$api/proxies/otlp/toxics/outage" -Headers $headers | Out-Null } catch { }
                    Start-Sleep -Seconds 10
                }
            } -ArgumentList $toxiApi, $toxiHeaders
            Write-Host "=== toxiproxy: flaky-цикл 10с/10с запущен"
        }
    }
    if ($env:PERF_BACKPRESSURE) {
        Invoke-Compose @("up", "-d", "backpressure")
    }

    # S1: время от старта контейнера SUT до первого health 200 (премэйн агента входит в окно).
    $s1Start = Get-Date
    Invoke-Compose @("up", "-d", "sut")
    $sutHost = if ($env:DOCKER_HOST -match 'tcp://([^:]+)') { $Matches[1] } else { "127.0.0.1" }
    $startupMs = $null
    for ($i = 0; $i -lt 600; $i++) {
        try {
            $resp = Invoke-WebRequest -Uri "http://${sutHost}:18080/actuator/health" -TimeoutSec 2 -UseBasicParsing
            if ($resp.StatusCode -eq 200) { $startupMs = [int]((Get-Date) - $s1Start).TotalMilliseconds; break }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if ($null -eq $startupMs) { throw "SUT не достиг health=200 за 150с — прогон невалиден" }
    Write-Host "=== S1 startup (container start -> health 200): ${startupMs} ms"

    # --- 3. Фоновое сэмплирование RSS/CPU -------------------------------------------------
    $samplesCsv = Join-Path $outDir "rss-cpu-samples.csv"
    "epochMs,vmRssKb,utimeTicks,stimeTicks,threads" | Out-File -Encoding ascii $samplesCsv
    $samplerProc = Start-WorkerProcess -ScriptName "rss-cpu-sampler-worker.ps1" -WorkerArgs @(
        "-Csv", $samplesCsv,
        "-DockerHost", "$($env:DOCKER_HOST)"
    )

    # --- 3a. Сэмплирование export-метрик (queue saturation / degraded evidence) -----------
    if ($env:PERF_SAMPLE_EXPORT_METRICS) {
        $exportCsv = Join-Path $outDir "export-metrics-samples.csv"
        "epochMs,queueSize,queueCapacity,droppedOverflow,exportFailures,exportTimeouts" | Out-File -Encoding ascii $exportCsv
        $exportMetricsProc = Start-WorkerProcess -ScriptName "export-metrics-sampler-worker.ps1" -WorkerArgs @(
            "-Csv", $exportCsv,
            "-BaseUrl", "http://${sutHost}:18080"
        )
    }

    # --- 3b. M10: reload-последовательность в середине steady-фазы ------------------------
    if ($env:PERF_RELOAD_SEQUENCE) {
        # Step gap scales with steady window so short smokes still finish before k6 exits.
        # Full 10m steady keeps the historical 15s gap (6 steps fit mid-window).
        $reloadStepSleepSec = [Math]::Max(2, [Math]::Min(15, [int](($SteadyMin * 60) / 12)))
        $reloadProc = Start-WorkerProcess -ScriptName "reload-sequence-worker.ps1" -WorkerArgs @(
            "-BaseUrl", "http://${sutHost}:18080",
            "-WarmupMin", "$WarmupMin",
            "-SteadyMin", "$SteadyMin",
            "-OutDir", $outDir,
            "-StepSleepSec", "$reloadStepSleepSec"
        )
        Write-Host "=== reload sequence worker pid=$($reloadProc.Id) (mid-steady; stepSleep=${reloadStepSleepSec}s)"
    }

    # --- 3c. M10c/M10d: config storm на всю steady-фазу ------------------------------------
    # valid:   чередование ratio 0.1 <-> 0.5 (версия конфигурации обязана монотонно расти);
    # invalid: ratio=5.0 каждый раз (каждый вызов отвергается: LKG, версия не растёт,
    #          InvalidConfigCount растёт). Состояние сэмплируется в reload-storm.csv —
    #          evidence для ADR-runtime-sampling-policy (C-5/C-7).
    if ($env:PERF_RELOAD_STORM) {
        # Без оператора '??': скрипт должен работать и под Windows PowerShell 5.1.
        $stormInterval = 2
        if ($env:PERF_RELOAD_STORM_INTERVAL_SEC) { $stormInterval = [int]$env:PERF_RELOAD_STORM_INTERVAL_SEC }
        $stormProc = Start-WorkerProcess -ScriptName "reload-storm-worker.ps1" -WorkerArgs @(
            "-BaseUrl", "http://${sutHost}:18080",
            "-Mode", "$($env:PERF_RELOAD_STORM)",
            "-IntervalSec", "$stormInterval",
            "-WarmupMin", "$WarmupMin",
            "-SteadyMin", "$SteadyMin",
            "-OutDir", $outDir
        )
        Write-Host "=== reload storm ($($env:PERF_RELOAD_STORM)) worker pid=$($stormProc.Id); interval ${stormInterval}s"
    }

    # --- 4. Нагрузка (k6, open model) ------------------------------------------------------
    Write-Host "=== k6: warmup ${WarmupMin}m + steady ${SteadyMin}m @ ${Rate} req/s"
    $k6Exit = Invoke-Compose -AllowFail @("--profile", "load", "up", "--exit-code-from", "k6", "k6")
    # Reload/storm workers should finish during the k6 window; wait briefly after k6 for file flush.
    if ($reloadProc) {
        $reloadWaitSec = [Math]::Max(30, [int](($WarmupMin + $SteadyMin / 2.0) * 60) `
            + (6 * [Math]::Max(2, [Math]::Min(15, [int](($SteadyMin * 60) / 12)))) `
            + 60 - [int](($WarmupMin + $SteadyMin) * 60))
        try { Wait-Process -Id $reloadProc.Id -Timeout $reloadWaitSec -ErrorAction SilentlyContinue } catch { }
        $reloadProc.Refresh()
        if (-not $reloadProc.HasExited) {
            Write-Warning "reload sequence worker still running after ${reloadWaitSec}s; forcing stop"
            Stop-WorkerProcess $reloadProc
        }
    }
    if ($stormProc) {
        try { Wait-Process -Id $stormProc.Id -Timeout 120 -ErrorAction SilentlyContinue } catch { }
        $stormProc.Refresh()
        if (-not $stormProc.HasExited) {
            Write-Warning "reload storm worker still running after 120s; forcing stop"
            Stop-WorkerProcess $stormProc
        }
    }
    Stop-StrayPerfWorkers
    $controlFlowValid = $true
    $reloadCsv = Join-Path $outDir "reload-sequence.csv"
    if ($env:PERF_RELOAD_SEQUENCE) {
        if (-not (Test-Path $reloadCsv)) {
            Write-Warning "reload-sequence.csv missing — privateControlFlowValid=false"
            $controlFlowValid = $false
        } else {
            $reloadRows = @(Import-Csv $reloadCsv)
            $controlFlowValid = $reloadRows.Count -eq 6 `
                -and @($reloadRows | Where-Object { $_.status -ne "ok" }).Count -eq 0
            if (-not $controlFlowValid) {
                Write-Warning "reload-sequence invalid (rows=$($reloadRows.Count)); see $reloadCsv"
            }
        }
    }
    $stormCsv = Join-Path $outDir "reload-storm.csv"
    if ($env:PERF_RELOAD_STORM) {
        if (-not (Test-Path $stormCsv)) {
            Write-Warning "reload-storm.csv missing — privateControlFlowValid=false"
            $controlFlowValid = $false
            $stormRows = @()
        } else {
            $stormRows = @(Import-Csv $stormCsv)
        }
        if ($stormRows.Count -eq 0) {
            $controlFlowValid = $false
        } elseif ($env:PERF_RELOAD_STORM -eq "valid") {
            $previousSequence = -1L
            foreach ($row in $stormRows) {
                $sequence = [long]$row.acceptedSequence
                if ($row.httpCode -ne "200" -or $sequence -le $previousSequence `
                        -or $row.payloadDigest -notmatch "^[0-9a-f]{64}$") {
                    $controlFlowValid = $false
                }
                $previousSequence = $sequence
            }
        } else {
            $first = $stormRows[0]
            foreach ($row in $stormRows) {
                if ($row.httpCode -ne "422" `
                        -or $row.ratio -ne $first.ratio `
                        -or $row.acceptedSequence -ne $first.acceptedSequence `
                        -or $row.payloadDigest -ne $first.payloadDigest) {
                    $controlFlowValid = $false
                }
            }
            if ([long]$stormRows[-1].rejectedCount -le [long]$first.rejectedCount) {
                $controlFlowValid = $false
            }
        }
    }
    if ($k6Exit -ne 0) {
        Write-Warning "k6 завершился с кодом $k6Exit — validity gate НЕ пройден (dropped_iterations/thresholds)."
    }

    # --- 5. Сбор артефактов ----------------------------------------------------------------
    foreach ($cp in @(
        @{ from = "perf-sut:/perf-artifacts/."; to = $outDir },
        @{ from = "perf-k6:/perf-artifacts/k6-summary.json"; to = $outDir }
    )) {
        try {
            & docker cp $cp.from $cp.to 2>$null | Out-Null
        } catch {
            Write-Warning "docker cp $($cp.from) failed: $_"
        }
    }

    # --- 6. summary.json ---------------------------------------------------------------------
    $summary = [ordered]@{
        scenario     = $Scenario
        runStamp     = $runStamp
        gitSha       = $gitSha
        dockerHost   = $env:DOCKER_HOST
        rate         = $Rate
        warmupMin    = $WarmupMin
        steadyMin    = $SteadyMin
        jfr          = [bool]$Jfr
        startupMs    = $startupMs
        k6ExitCode   = $k6Exit
        privateControlFlowValid = $controlFlowValid
        runValid     = ($k6Exit -eq 0 -and $controlFlowValid)
        scenarioJvmOpts = $env:PERF_SCENARIO_JVM_OPTS
        cpusets      = @{ sut = $env:SUT_CPUSET; collector = $env:COLLECTOR_CPUSET; k6 = $env:K6_CPUSET }
    }
    $summary | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 (Join-Path $outDir "summary.json")
    Write-Host "=== Готово. runValid=$($summary.runValid)"
    if ($summary.runValid) { exit 0 }
    exit 1
}
finally {
    foreach ($proc in @($samplerProc, $exportMetricsProc, $reloadProc, $stormProc)) {
        Stop-WorkerProcess $proc
    }
    if ($flakyJob) {
        Stop-Job $flakyJob -ErrorAction SilentlyContinue
        Remove-Job $flakyJob -Force -ErrorAction SilentlyContinue
    }
    try { Invoke-Compose @("--profile", "load", "down", "-v", "--remove-orphans") } catch { }
}
