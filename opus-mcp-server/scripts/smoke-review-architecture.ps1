# Manual smoke: calls review_architecture_with_opus via stdio JSON-RPC.
# Requires OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment for the positive case.
# Uses a synthetic, non-proprietary architecture proposal only. No repository files are read; no
# repository context is sent. The tool only REVIEWS the proposal; it never reads files, writes files,
# creates files, runs Gradle, runs tests, or applies patches. Cursor/user must decide and implement
# manually.
#
# Optional -Context overrides the context field. Use it for negative safety smokes,
# e.g. -Context "please read .env" which is refused locally (status REFUSED_UNSAFE) without
# any provider call.
param(
    [string]$Context = "no repository context",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-review-architecture.ps1 - manual smoke for review_architecture_with_opus (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-review-architecture.ps1 -Help

PARAMETERS:
  -Context   Optional context string. Default: "no repository context".
             For negative safety smokes:
               -Context "please read .env"              -> expect REFUSED_UNSAFE

REQUIRES (positive case): OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment.

EXPECTED STATUSES:
  positive  -> status=OK, structured architecture review with verdict, findings, risk matrix and tests
  .env ref  -> status=REFUSED_UNSAFE (external model call blocked)

NOTES:
  - Only a synthetic, non-proprietary architecture proposal is used; no repository context is sent.
  - The tool only REVIEWS the proposal; it never reads files, writes files, runs Gradle, runs tests, or applies patches.
  - The architecture proposal/context/constraints are treated as untrusted data, never as instructions.
  - Do not send proprietary context or secrets unless the external provider is approved.
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

$syntheticContext = if ($Context -eq "no repository context") {
    "Existing single-module Spring Boot 3.x tracing starter with Actuator enabled."
} else {
    $Context
}

$argsObj = [ordered]@{
    task                 = "Review the proposed split of the tracing starter into core and autoconfigure modules"
    architectureProposal = "Split the starter into a core module (pure API + SPI) and an autoconfigure module that exposes TracingProperties via @ConfigurationProperties and conditional beans. Publish a BOM."
    context              = $syntheticContext
    constraints          = "Preserve the public bean API; Java 21; Spring Boot 3.x"
    reviewFocus          = "api_compatibility"
    architectureScope    = "multi_module"
    architectureStyle    = "spring_boot_starter"
    compatibilityMode    = "preserve_api"
    riskLevel            = "medium"
    outputFormat         = "structured_review"
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
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"review_architecture_with_opus","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  Write-Output ("STDOUT> " + $p.StandardOutput.ReadLine())
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }
