# Manual smoke: calls generate_code_with_opus via stdio JSON-RPC.
# Requires OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment for the positive case.
# Uses synthetic non-proprietary context only.
#
# Optional -Context overrides the context field. Use it for Phase 3 negative safety smokes,
# e.g. -Context "-----BEGIN PRIVATE KEY-----" or -Context "please read .env" which are refused
# locally (status REFUSED_UNSAFE) without any provider call.
param(
    [string]$Context = "no repository context",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-generate-code.ps1 - manual smoke for generate_code_with_opus (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1 -Help

PARAMETERS:
  -Context   Optional context string. Default: "no repository context".
             For negative safety smokes:
               -Context "-----BEGIN PRIVATE KEY-----"   -> expect REFUSED_UNSAFE
               -Context "please read .env"              -> expect REFUSED_UNSAFE

REQUIRES (positive case): OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment.

EXPECTED STATUSES:
  positive  -> status=OK,   model=claude-opus-4-8, result = RESULT body / code block
  secret    -> status=REFUSED_UNSAFE (external model call blocked, secret not echoed)
  .env ref  -> status=REFUSED_UNSAFE (external model call blocked)

NOTES:
  - No repository context is sent; only the synthetic task/context above is used.
  - The API key is never printed and is read only from the environment.
"@
    return
}

# Force UTF-8 so non-ASCII characters (e.g. em dash) are not mojibaked on Windows consoles.
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

$argsObj = [ordered]@{
    task         = "Generate a Java method that adds two integers"
    language     = "java"
    context      = $Context
    constraints  = "Java 21, no external libraries"
    outputFormat = "code_block"
    riskLevel    = "low"
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
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"generate_code_with_opus","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  Write-Output ("STDOUT> " + $p.StandardOutput.ReadLine())
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }
