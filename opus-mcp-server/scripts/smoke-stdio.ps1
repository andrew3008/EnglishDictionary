# Manual Phase 0A connectivity smoke: drives the stdio MCP server with raw JSON-RPC
# (initialize -> tools/list -> tools/call echo_mcp_connection) and prints the responses.
# Usage (from project root, after `gradlew shadowJar`):
#   powershell -ExecutionPolicy Bypass -File scripts/smoke-stdio.ps1
$ErrorActionPreference = 'Stop'
$jar = "build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar"
if (-not (Test-Path $jar)) { throw "Fat-jar not found: $jar. Run: gradlew shadowJar" }

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "java"
$psi.Arguments = "-jar `"$jar`""
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.WorkingDirectory = (Get-Location).Path

$p = [System.Diagnostics.Process]::Start($psi)

$messages = @(
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"1.0"}}}'
  '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo_mcp_connection","arguments":{"message":"hello-phase-0a"}}}'
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  $line = $p.StandardOutput.ReadLine()
  Write-Output ("STDOUT> " + $line)
}

$p.StandardInput.Close()
Start-Sleep -Milliseconds 300
if (-not $p.HasExited) { $p.Kill() }
