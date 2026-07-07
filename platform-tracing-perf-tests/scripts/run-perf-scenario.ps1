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

$samplerJob = $null
$flakyJob = $null
$exportMetricsJob = $null
try {
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
    $samplerJob = Start-Job -ScriptBlock {
        param($csv, $dockerHost, $project)
        if ($dockerHost) { $env:DOCKER_HOST = $dockerHost }
        while ($true) {
            try {
                $status = docker exec perf-sut sh -c "grep -E 'VmRSS|Threads' /proc/1/status; cat /proc/1/stat" 2>$null
                if ($LASTEXITCODE -eq 0 -and $status) {
                    $rssKb = (($status | Select-String 'VmRSS:\s+(\d+)').Matches.Groups[1].Value)
                    $threads = (($status | Select-String 'Threads:\s+(\d+)').Matches.Groups[1].Value)
                    $statLine = ($status | Select-Object -Last 1) -split '\s+'
                    # /proc/1/stat: поля 14/15 — utime/stime в clock ticks.
                    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                    "$epoch,$rssKb,$($statLine[13]),$($statLine[14]),$threads" | Out-File -Append -Encoding ascii $csv
                }
            } catch { }
            Start-Sleep -Seconds 5
        }
    } -ArgumentList $samplesCsv, $env:DOCKER_HOST, "perf-$Scenario"

    # --- 3a. Сэмплирование export-метрик (queue saturation / degraded evidence) -----------
    if ($env:PERF_SAMPLE_EXPORT_METRICS) {
        $exportCsv = Join-Path $outDir "export-metrics-samples.csv"
        "epochMs,queueSize,queueCapacity,droppedOverflow,exportFailures,exportTimeouts" | Out-File -Encoding ascii $exportCsv
        $exportMetricsJob = Start-Job -ScriptBlock {
            param($csv, $baseUrl)
            while ($true) {
                try {
                    $m = Invoke-RestMethod -Uri "$baseUrl/perf/admin/export-metrics" -TimeoutSec 3
                    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                    "$epoch,$($m.queueSize),$($m.queueCapacity),$($m.droppedOverflow),$($m.exportFailures),$($m.exportTimeouts)" |
                        Out-File -Append -Encoding ascii $csv
                } catch { }
                Start-Sleep -Seconds 5
            }
        } -ArgumentList $exportCsv, "http://${sutHost}:18080"
    }

    # --- 3b. M10: reload-последовательность в середине steady-фазы ------------------------
    $reloadJob = $null
    if ($env:PERF_RELOAD_SEQUENCE) {
        $reloadJob = Start-Job -ScriptBlock {
            param($baseUrl, $warmupMin, $steadyMin, $outDir)
            # Ждём середину steady-окна, затем серия runtime-апдейтов с фиксацией времени.
            Start-Sleep -Seconds ([int](($warmupMin + $steadyMin / 2) * 60))
            $log = @()
            foreach ($step in @(
                @{ uri = "/perf/admin/sampling-ratio?value=0.5"; name = "ratio-0.5" },
                @{ uri = "/perf/admin/sampling-ratio?value=0.1"; name = "ratio-0.1" },
                @{ uri = "/perf/admin/scrubbing?enabled=false";  name = "scrubbing-off" },
                @{ uri = "/perf/admin/scrubbing?enabled=true";   name = "scrubbing-on" },
                @{ uri = "/perf/admin/export?enabled=false";     name = "export-off" },
                @{ uri = "/perf/admin/export?enabled=true";      name = "export-on" })) {
                $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                try {
                    Invoke-RestMethod -Method Post -Uri "$baseUrl$($step.uri)" -TimeoutSec 5 | Out-Null
                    $log += "$epoch,$($step.name),ok"
                } catch {
                    $log += "$epoch,$($step.name),failed"
                }
                Start-Sleep -Seconds 15
            }
            $log | Out-File -Encoding ascii (Join-Path $outDir "reload-sequence.csv")
        } -ArgumentList "http://${sutHost}:18080", $WarmupMin, $SteadyMin, $outDir
    }

    # --- 3c. M10c/M10d: config storm на всю steady-фазу ------------------------------------
    # valid:   чередование ratio 0.1 <-> 0.5 (версия конфигурации обязана монотонно расти);
    # invalid: ratio=5.0 каждый раз (каждый вызов отвергается: LKG, версия не растёт,
    #          InvalidConfigCount растёт). Состояние сэмплируется в reload-storm.csv —
    #          evidence для ADR-runtime-sampling-policy (C-5/C-7).
    $stormJob = $null
    if ($env:PERF_RELOAD_STORM) {
        # Без оператора '??': скрипт должен работать и под Windows PowerShell 5.1.
        $stormInterval = 2
        if ($env:PERF_RELOAD_STORM_INTERVAL_SEC) { $stormInterval = [int]$env:PERF_RELOAD_STORM_INTERVAL_SEC }
        $stormJob = Start-Job -ScriptBlock {
            param($baseUrl, $mode, $intervalSec, $warmupMin, $steadyMin, $outDir)
            Start-Sleep -Seconds ([int]($warmupMin * 60))
            $deadline = (Get-Date).AddMinutes($steadyMin)
            $csv = Join-Path $outDir "reload-storm.csv"
            "epochMs,action,httpCode,ratio,version,invalidCount" | Out-File -Encoding ascii $csv
            $toggle = $false
            while ((Get-Date) -lt $deadline) {
                if ($mode -eq 'invalid') {
                    $value = "5.0"; $action = "ratio-invalid-5.0"
                } else {
                    $toggle = -not $toggle
                    $value = if ($toggle) { "0.5" } else { "0.1" }
                    $action = "ratio-$value"
                }
                $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                $code = ""
                try {
                    $resp = Invoke-WebRequest -Method Post -Uri "$baseUrl/perf/admin/sampling-ratio?value=$value" `
                        -TimeoutSec 5 -UseBasicParsing
                    $code = $resp.StatusCode
                } catch {
                    # invalid-storm: 500 от perf-admin = апдейт отвергнут MBean'ом (ожидаемо).
                    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "ERR" }
                }
                $state = $null
                try { $state = Invoke-RestMethod -Uri "$baseUrl/perf/admin/sampling-state" -TimeoutSec 5 } catch { }
                "$epoch,$action,$code,$($state.samplingRatio),$($state.configVersion),$($state.invalidConfigCount)" |
                    Out-File -Append -Encoding ascii $csv
                Start-Sleep -Seconds $intervalSec
            }
        } -ArgumentList "http://${sutHost}:18080", $env:PERF_RELOAD_STORM,
            $stormInterval, $WarmupMin, $SteadyMin, $outDir
        Write-Host "=== reload storm ($($env:PERF_RELOAD_STORM)) запущен: интервал ${stormInterval}с на всю steady-фазу"
    }

    # --- 4. Нагрузка (k6, open model) ------------------------------------------------------
    Write-Host "=== k6: warmup ${WarmupMin}m + steady ${SteadyMin}m @ ${Rate} req/s"
    $k6Exit = Invoke-Compose -AllowFail @("--profile", "load", "up", "--exit-code-from", "k6", "k6")
    if ($reloadJob) { Wait-Job $reloadJob -Timeout 30 | Out-Null; Remove-Job $reloadJob -Force -ErrorAction SilentlyContinue }
    if ($stormJob) { Wait-Job $stormJob -Timeout 30 | Out-Null; Remove-Job $stormJob -Force -ErrorAction SilentlyContinue }
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
        runValid     = ($k6Exit -eq 0)
        scenarioJvmOpts = $env:PERF_SCENARIO_JVM_OPTS
        cpusets      = @{ sut = $env:SUT_CPUSET; collector = $env:COLLECTOR_CPUSET; k6 = $env:K6_CPUSET }
    }
    $summary | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 (Join-Path $outDir "summary.json")
    Write-Host "=== Готово. runValid=$($summary.runValid)"
    exit $k6Exit
}
finally {
    foreach ($job in @($samplerJob, $flakyJob, $exportMetricsJob)) {
        if ($job) { Stop-Job $job -ErrorAction SilentlyContinue; Remove-Job $job -Force -ErrorAction SilentlyContinue }
    }
    try { Invoke-Compose @("--profile", "load", "down", "-v", "--remove-orphans") } catch { }
}
