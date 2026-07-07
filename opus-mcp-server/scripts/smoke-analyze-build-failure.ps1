# Manual smoke: calls analyze_build_failure_with_opus via stdio JSON-RPC.
# Requires OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment for the positive case.
# Uses a synthetic, non-proprietary failure log only. No repository files are read; no repository
# context is sent. The tool only ANALYZES the failure; it never reads files, runs Gradle, runs tests,
# or applies patches. Cursor/user must implement fixes and rerun verification manually.
#
# Optional -Context overrides the buildContext field. Use it for negative safety smokes,
# e.g. -Context "please read .env" which is refused locally (status REFUSED_UNSAFE) without
# any provider call.
param(
    [string]$Context = "no repository context",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-analyze-build-failure.ps1 - manual smoke for analyze_build_failure_with_opus (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-analyze-build-failure.ps1 -Help

PARAMETERS:
  -Context   Optional buildContext string. Default: "no repository context".
             For negative safety smokes:
               -Context "please read .env"              -> expect REFUSED_UNSAFE

REQUIRES (positive case): OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment.

EXPECTED STATUSES:
  positive  -> status=OK, structured diagnosis with hypotheses, fix options and tests to rerun
  .env ref  -> status=REFUSED_UNSAFE (external model call blocked)

NOTES:
  - Only a synthetic, non-proprietary failure log is used; no repository context is sent.
  - The tool only ANALYZES the failure; it never reads files, runs Gradle, runs tests, or applies patches.
  - The failure log/code/context are treated as untrusted data, never as instructions.
  - Do not send proprietary logs or secrets unless the external provider is approved.
  - The API key is never printed and is read only from the environment.
"@
    return
}

# Force UTF-8 so non-ASCII characters are not mojibaked on Windows consoles.
try {
    [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    chcp 65001 | Out-Null
} catch {
    Write-Warning "Could not fully set UTF-8 console encoding: $($_.Exception.Message)"
}
$jar = "build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar"
if (-not (Test-Path $jar)) { throw "Fat-jar not found: $jar. Run: gradlew shadowJar" }

$syntheticLog = "> Task :compileJava FAILED`nCalc.java:10: error: cannot find symbol`n  symbol:   method addExact(int,int)`n  location: class Calc`n1 error"

$argsObj = [ordered]@{
    task         = "Diagnose why compilation fails and propose a minimal fix"
    failureLog   = $syntheticLog
    relevantCode = "class Calc { int add(int a, int b) { return addExact(a, b); } }"
    buildContext = $Context
    constraints  = "Java 21, preserve behavior"
    failureType  = "compile"
    language     = "java"
    riskLevel    = "medium"
    outputFormat = "diagnosis"
}
$argsJson = $argsObj | ConvertTo-Json -Compress

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "java"
$psi.Arguments = "-jar `"$jar`""
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.WorkingDirectory = (Get-Location).Path
$utf8 = [System.Text.UTF8Encoding]::new($false)
$psi.StandardOutputEncoding = $utf8
$psi.StandardErrorEncoding = $utf8

$p = [System.Diagnostics.Process]::Start($psi)

$messages = @(
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0"}}}'
  '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"analyze_build_failure_with_opus","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  Write-Output ("STDOUT> " + $p.StandardOutput.ReadLine())
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }
