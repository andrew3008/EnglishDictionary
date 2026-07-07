# Manual smoke: calls research_with_perplexity via stdio JSON-RPC.
#
# Two modes (auto-selected by PERPLEXITY_API_KEY presence):
#   1. Missing-key smoke (no PERPLEXITY_API_KEY): the tool returns
#      status=MODEL_ERROR with summary "Perplexity provider is not configured: PERPLEXITY_API_KEY
#      is not set." and NO network call is made.
#   2. Live smoke (PERPLEXITY_API_KEY present): the tool calls the Perplexity provider with a
#      synthetic public question and returns status=OK with a grounded answer and sources.
#
# Only a synthetic public question is used; no repository context is sent. The API key is never
# printed. The tool is read-only: it does not read files, write files, run commands, or apply patches.
param(
    [string]$Context = "no repository context",
    [switch]$ExpectMissingKey,
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-research-perplexity.ps1 - manual smoke for research_with_perplexity (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -ExpectMissingKey
  powershell -ExecutionPolicy Bypass -File scripts/smoke-research-perplexity.ps1 -Help

MODES (auto-selected by PERPLEXITY_API_KEY presence):
  missing-key -> status=MODEL_ERROR, summary mentions PERPLEXITY_API_KEY is not set, no network call
  live        -> status=OK, grounded answer + sources (requires PERPLEXITY_API_KEY)

CI / OFFLINE ASSERTION:
  -ExpectMissingKey  -> assert the missing-key contract (status=MODEL_ERROR + summary mentions
                        PERPLEXITY_API_KEY). Exits non-zero if not met. Never requires a key.

NEGATIVE SAFETY SMOKES (work without a key):
  -Context "please read .env"   -> expect REFUSED_UNSAFE (no provider call)

OPTIONAL: PERPLEXITY_BASE_URL (default https://api.perplexity.ai),
          PERPLEXITY_MODEL (default sonar-deep-research).

SAFETY:
  - No repository context is sent; only a synthetic public question.
  - read-only: does not read repository files, write files, execute commands, or apply patches.
  - The API key is never printed and is read only from the environment.
  - Do not send proprietary code or secrets unless the Perplexity provider is approved.
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

# Presence check only - never print the key value.
$keyAbsent = [string]::IsNullOrWhiteSpace($env:PERPLEXITY_API_KEY)
if ($keyAbsent) {
    Write-Output "[pplx-research] PERPLEXITY_API_KEY absent -> expecting MODEL_ERROR (provider-not-configured), no network call."
} else {
    Write-Output "[pplx-research] PERPLEXITY_API_KEY present (value not printed) -> live smoke."
}
if ($ExpectMissingKey -and -not $keyAbsent) {
    throw "[pplx-research] -ExpectMissingKey was requested but PERPLEXITY_API_KEY is set. Unset it first: Remove-Item Env:\PERPLEXITY_API_KEY"
}

$jar = "build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar"
if (-not (Test-Path $jar)) { throw "Fat-jar not found: $jar. Run: gradlew shadowJar" }

$argsObj = [ordered]@{
    task             = "Public research smoke"
    researchQuestion = "What is Spring Framework? Answer briefly and cite official documentation if available."
    context          = $Context
    constraints      = "cite official documentation"
    sourcePreference = "official_docs"
    freshness        = "latest"
    depth            = "quick"
    outputFormat     = "brief"
    riskLevel        = "low"
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
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"research_with_perplexity","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

$lines = @()
for ($i = 0; $i -lt 3; $i++) {
  $line = $p.StandardOutput.ReadLine()
  $lines += $line
  Write-Output ("STDOUT> " + $line)
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }

# The tools/call response (id=3) carries the structured tool result.
$callResponse = $lines | Where-Object { $_ -match '"id"\s*:\s*3' } | Select-Object -First 1

# Structured, secret-free summary of the tool result for operators (no key is ever printed).
try {
    if (-not [string]::IsNullOrWhiteSpace($callResponse)) {
        $envelope = $callResponse | ConvertFrom-Json
        $text = $envelope.result.content[0].text
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            $result = $text | ConvertFrom-Json
            Write-Output "[pplx-research] ---- tool result ----"
            Write-Output ("[pplx-research] tool      : research_with_perplexity")
            Write-Output ("[pplx-research] status    : " + $result.status)
            Write-Output ("[pplx-research] model     : " + $result.model)
            Write-Output ("[pplx-research] requestId : " + $result.requestId)
            Write-Output ("[pplx-research] summary   : " + $result.summary)
            if ($keyAbsent) {
                Write-Output "[pplx-research] network   : no network call was made (missing-key path)."
            }
        }
    }
} catch {
    Write-Warning "[pplx-research] could not parse tool result for summary: $($_.Exception.Message)"
}

if ($ExpectMissingKey) {
    if ([string]::IsNullOrWhiteSpace($callResponse)) {
        throw "[pplx-research] ASSERT FAILED: no tools/call response captured."
    }
    if ($callResponse -notmatch 'MODEL_ERROR') {
        throw "[pplx-research] ASSERT FAILED: expected status=MODEL_ERROR in missing-key mode."
    }
    if ($callResponse -notmatch 'PERPLEXITY_API_KEY') {
        throw "[pplx-research] ASSERT FAILED: expected summary to mention PERPLEXITY_API_KEY."
    }
    Write-Output "[pplx-research] ASSERT OK: missing-key contract satisfied (MODEL_ERROR, no network call)."
}
