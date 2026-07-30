# Independent process worker for RSS/CPU sampling (avoids Start-Job starvation).
param(
    [Parameter(Mandatory = $true)][string]$Csv,
    [string]$DockerHost = ""
)

$ErrorActionPreference = "Continue"
if ($DockerHost) { $env:DOCKER_HOST = $DockerHost }
while ($true) {
    try {
        $status = docker exec perf-sut sh -c "grep -E 'VmRSS|Threads' /proc/1/status; cat /proc/1/stat" 2>$null
        if ($LASTEXITCODE -eq 0 -and $status) {
            $rssKb = (($status | Select-String 'VmRSS:\s+(\d+)').Matches.Groups[1].Value)
            $threads = (($status | Select-String 'Threads:\s+(\d+)').Matches.Groups[1].Value)
            $statLine = ($status | Select-Object -Last 1) -split '\s+'
            $epoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            "$epoch,$rssKb,$($statLine[13]),$($statLine[14]),$threads" | Out-File -Append -Encoding ascii $Csv
        }
    } catch { }
    Start-Sleep -Seconds 5
}
