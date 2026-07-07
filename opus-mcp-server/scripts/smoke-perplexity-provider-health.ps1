# Perplexity provider health smoke (Phase 8A spike) - diagnostics ONLY.
#
# Runs the ISOLATED Java spike class PerplexityEndpointSmokeCheck, which sends ONLY a synthetic
# prompt ("Reply with exactly: OK") to <PERPLEXITY_BASE_URL>/chat/completions (OpenAI-compatible)
# and prints a safe, redacted diagnostic. This is NOT an exposed MCP tool.
#
# This script:
#   - checks PERPLEXITY_API_KEY presence WITHOUT printing it,
#   - uses PERPLEXITY_BASE_URL and PERPLEXITY_MODEL (with the Java-side defaults if unset),
#   - never sends repository context (synthetic prompt only),
#   - never prints the full provider response (the Java class caps the preview),
#   - does NOT change application config, the Opus tools, or the default model.
#
# Requires (for a live call): PERPLEXITY_API_KEY in the environment, and the built fat-jar.
param(
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-perplexity-provider-health.ps1 - Perplexity provider compatibility spike (Phase 8A, diagnostics only)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-perplexity-provider-health.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-perplexity-provider-health.ps1 -Help

REQUIRES (live call): PERPLEXITY_API_KEY in the environment.
OPTIONAL: PERPLEXITY_BASE_URL (default https://api.perplexity.ai), PERPLEXITY_MODEL (default sonar-deep-research).

WHAT IT DOES:
  - Runs the isolated Java class PerplexityEndpointSmokeCheck from the built fat-jar.
  - Sends ONLY the synthetic prompt "Reply with exactly: OK" to <baseUrl>/chat/completions.
  - No repository context is sent. The API key is never printed.
  - Prints: ok, statusCode, statusDescription, model, requestId, capped text preview,
    diagnosticCategory, and a masked errorBodyPreview on failure.

DIAGNOSTIC CATEGORIES (mapped by PerplexityProviderErrorClassifier):
  OK, RESPONSE_PARSE_ERROR, AUTH_ERROR, REQUEST_SHAPE_ERROR, MODEL_NOT_FOUND,
  RATE_LIMIT_OR_QUOTA, NETWORK_ERROR, PROVIDER_DOWN, UNKNOWN_PROVIDER_ERROR.

SAFETY:
  - This is a discovery spike, NOT an MCP tool. No tool is exposed to Cursor.
  - Does NOT print: PERPLEXITY_API_KEY, Authorization header, full request/response body.
  - Does NOT send code, secrets, or repository content.
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
if ([string]::IsNullOrWhiteSpace($env:PERPLEXITY_API_KEY)) {
    throw "Missing PERPLEXITY_API_KEY in environment (it is never printed). Perplexity live smoke not run."
}
Write-Output "[pplx-health] PERPLEXITY_API_KEY present (value not printed)."

$baseUrl = if ([string]::IsNullOrWhiteSpace($env:PERPLEXITY_BASE_URL)) { "https://api.perplexity.ai (default)" } else { $env:PERPLEXITY_BASE_URL }
$model   = if ([string]::IsNullOrWhiteSpace($env:PERPLEXITY_MODEL))    { "sonar-deep-research (default)" }    else { $env:PERPLEXITY_MODEL }
Write-Output "[pplx-health] baseUrl=$baseUrl"
Write-Output "[pplx-health] model=$model"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$jar = Join-Path $projectRoot "build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar"

if (-not (Test-Path $jar)) {
    throw "Fat-jar not found at $jar. Build it first: .\gradlew.bat shadowJar"
}

$mainClass = "space.br1440.platform.devtools.opusmcp.perplexity.PerplexityEndpointSmokeCheck"
Write-Output "[pplx-health] Running isolated spike class (synthetic prompt only, no repo context)..."
Write-Output ""

& java -cp $jar $mainClass
$exit = $LASTEXITCODE
Write-Output ""
Write-Output "[pplx-health] exitCode=$exit (0=ok, 1=provider/parse failure, 2=config error)"
exit $exit
