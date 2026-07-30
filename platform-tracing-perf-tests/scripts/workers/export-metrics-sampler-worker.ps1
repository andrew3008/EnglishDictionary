# Independent process worker for export-metrics sampling (avoids Start-Job starvation).
param(
    [Parameter(Mandatory = $true)][string]$Csv,
    [Parameter(Mandatory = $true)][string]$BaseUrl
)

$ErrorActionPreference = "Continue"
while ($true) {
    try {
        $m = Invoke-RestMethod -Uri "$BaseUrl/perf/private-control/export-metrics" -TimeoutSec 3
        $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        "$epoch,$($m.queueSize),$($m.queueCapacity),$($m.droppedOverflow),$($m.exportFailures),$($m.exportTimeouts)" |
            Out-File -Append -Encoding ascii $Csv
    } catch { }
    Start-Sleep -Seconds 5
}
