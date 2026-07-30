# Independent process worker for M10c/M10d reload-storm (avoids Start-Job starvation).
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [Parameter(Mandatory = $true)][string]$Mode,
    [Parameter(Mandatory = $true)][int]$IntervalSec,
    [Parameter(Mandatory = $true)][int]$WarmupMin,
    [Parameter(Mandatory = $true)][int]$SteadyMin,
    [Parameter(Mandatory = $true)][string]$OutDir
)

$ErrorActionPreference = "Continue"
Start-Sleep -Seconds ([int]($WarmupMin * 60))
$deadline = (Get-Date).AddMinutes($SteadyMin)
$csv = Join-Path $OutDir "reload-storm.csv"
"epochMs,action,httpCode,ratio,acceptedSequence,rejectedCount,payloadDigest" |
    Out-File -Encoding ascii $csv
$toggle = $false
while ((Get-Date) -lt $deadline) {
    if ($Mode -eq "invalid") {
        $value = "5.0"; $action = "ratio-invalid-5.0"
    } else {
        $toggle = -not $toggle
        $value = if ($toggle) { "0.5" } else { "0.1" }
        $action = "ratio-$value"
    }
    $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $code = ""
    try {
        $resp = Invoke-WebRequest -Method Post -Uri "$BaseUrl/perf/private-control/sampling-ratio?value=$value" `
            -TimeoutSec 5 -UseBasicParsing
        $code = $resp.StatusCode
    } catch {
        $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "ERR" }
    }
    $state = $null
    try { $state = Invoke-RestMethod -Uri "$BaseUrl/perf/private-control/state" -TimeoutSec 5 } catch { }
    "$epoch,$action,$code,$($state.samplingRatio),$($state.acceptedSequence),$($state.rejectedCount),$($state.payloadDigest)" |
        Out-File -Append -Encoding ascii $csv
    Start-Sleep -Seconds $IntervalSec
}
exit 0
