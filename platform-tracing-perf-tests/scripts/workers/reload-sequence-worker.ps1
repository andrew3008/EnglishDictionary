# Independent process worker for M10 reload-sequence (avoids Start-Job starvation).
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][int]$WarmupMin,
    [Parameter(Mandatory = $true)][int]$SteadyMin,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][int]$StepSleepSec
)

$ErrorActionPreference = "Continue"
$csv = Join-Path $OutDir "reload-sequence.csv"
$jobLog = Join-Path $OutDir "reload-sequence.job.txt"
"started utc=$(Get-Date -Format o) warmupMin=$WarmupMin steadyMin=$SteadyMin stepSleep=$StepSleepSec baseUrl=$BaseUrl" |
    Out-File -Encoding ascii $jobLog
"epochMs,action,status,outcome,acceptedSequence,payloadDigest" |
    Out-File -Encoding ascii $csv

Start-Sleep -Seconds ([int](($WarmupMin + $SteadyMin / 2.0) * 60))

$steps = @(
    @{ uri = "/perf/private-control/sampling-ratio?value=0.5"; name = "ratio-0.5" },
    @{ uri = "/perf/private-control/sampling-ratio?value=0.1"; name = "ratio-0.1" },
    @{ uri = "/perf/private-control/exporter?enabled=false"; name = "exporter-off" },
    @{ uri = "/perf/private-control/exporter?enabled=true"; name = "exporter-on" },
    @{ uri = "/perf/private-control/trace-control-headers?enabled=false"; name = "headers-off" },
    @{ uri = "/perf/private-control/trace-control-headers?enabled=true"; name = "headers-on" }
)
foreach ($step in $steps) {
    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $line = "$epoch,$($step.name),failed,HTTP_ERROR,,"
    try {
        $response = Invoke-RestMethod -Method Post -Uri ($BaseUrl + $step.uri) -TimeoutSec 5
        $valid = ($response.outcome -eq "APPLIED" -or $response.outcome -eq "UNCHANGED") `
            -and $response.sequenceValid -and $response.digestMatchesCandidate
        $status = if ($valid) { "ok" } else { "failed" }
        $line = "$epoch,$($step.name),$status,$($response.outcome),$($response.acceptedSequence),$($response.payloadDigest)"
    } catch { }
    $line | Out-File -Append -Encoding ascii $csv
    Start-Sleep -Seconds $StepSleepSec
}
"completed utc=$(Get-Date -Format o)" | Out-File -Append -Encoding ascii $jobLog
exit 0
