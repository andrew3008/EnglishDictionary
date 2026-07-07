# Provider health smoke (Phase 7A.1) - diagnostics ONLY.
#
# Sends ONLY a synthetic prompt ("Reply with exactly: OK") to <OPUS_BASE_URL>/v1/messages for one or
# more models and prints a safe, redacted diagnostic for each. It distinguishes provider/gateway
# failures (e.g. Cloudflare HTTP 502) from MCP server logic.
#
# This script:
#   - never prints OPUS_API_KEY or Authorization headers,
#   - never sends repository context (synthetic prompt only),
#   - prints only a length-capped, masked error body preview and a small set of safe headers,
#   - does NOT change application config or default model.
#
# Requires (for a live call): OPUS_API_KEY, OPUS_BASE_URL in the environment.
param(
    [string]$Models = "",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-provider-health.ps1 - provider/gateway diagnostics (Phase 7A.1, diagnostics only)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-provider-health.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-provider-health.ps1 -Models "claude-opus-4-8,custom-opus-4-8"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-provider-health.ps1 -Help

PARAMETERS:
  -Models   Optional comma-separated model list to probe.
            Default: OPUS_MODEL if set, otherwise claude-opus-4-8.

REQUIRES (live call): OPUS_API_KEY, OPUS_BASE_URL in the environment.

WHAT IT DOES:
  - Sends ONLY the synthetic prompt "Reply with exactly: OK" to <baseUrl>/v1/messages.
  - No repository context is sent. The API key is never printed.
  - Prints per model: baseUrl, model, path, statusCode, statusDescription,
    safe error body preview, selected safe headers, diagnosticCategory, requestId.

DIAGNOSTIC CATEGORIES:
  OK, RESPONSE_PARSE_ERROR, AUTH_ERROR, REQUEST_SHAPE_ERROR, MODEL_ROUTE_DOWN,
  RATE_LIMIT_OR_QUOTA, NETWORK_ERROR, PROVIDER_DOWN, UNKNOWN_PROVIDER_ERROR.

  502/503/504/500 -> PROVIDER_DOWN (provider/gateway/upstream issue, not MCP logic).

SAFETY:
  - Does NOT print: x-api-key, Authorization, full request headers, full body.
  - Does NOT switch providers, change config, or update the default model.
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

$SyntheticPrompt = "Reply with exactly: OK"
$MessagesPath = "/v1/messages"
$AnthropicVersion = "2023-06-01"
$MaxPreview = 1000

$baseUrl = $env:OPUS_BASE_URL
$apiKey  = $env:OPUS_API_KEY

if ([string]::IsNullOrWhiteSpace($baseUrl)) { throw "Missing OPUS_BASE_URL in environment." }
if ([string]::IsNullOrWhiteSpace($apiKey))  { throw "Missing OPUS_API_KEY in environment (it is never printed)." }

# Resolve model list: -Models, else OPUS_MODEL, else claude-opus-4-8.
if ([string]::IsNullOrWhiteSpace($Models)) {
    if (-not [string]::IsNullOrWhiteSpace($env:OPUS_MODEL)) { $Models = $env:OPUS_MODEL } else { $Models = "claude-opus-4-8" }
}
$modelList = $Models.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }

$normalizedBase = $baseUrl.TrimEnd("/")
$uri = "$normalizedBase$MessagesPath"

# Redact known secret shapes (mirrors Java Masking) plus the literal API key value.
function Get-SafePreview([string]$body) {
    if ([string]::IsNullOrWhiteSpace($body)) { return "<empty>" }
    $masked = $body
    if (-not [string]::IsNullOrWhiteSpace($apiKey)) { $masked = $masked.Replace($apiKey, "[REDACTED]") }
    $masked = [regex]::Replace($masked, "(?is)-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----.*?-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY-----", "[REDACTED]")
    $masked = [regex]::Replace($masked, "(?i)\bBearer\s+[A-Za-z0-9._\-]{8,}", "Bearer [REDACTED]")
    $masked = [regex]::Replace($masked, "(?i)\b(x-api-key|api[_-]?key|apikey|access_token|refresh_token|id_token|client_secret|secret|password|passwd|token)(\s*[:=]\s*)([^\s,;}`"']+)", '$1$2[REDACTED]')
    $collapsed = [regex]::Replace($masked, "\s+", " ").Trim()
    if ($collapsed.Length -eq 0) { return "<empty>" }
    if ($collapsed.Length -gt $MaxPreview) { return $collapsed.Substring(0, $MaxPreview) + "...[truncated]" }
    return $collapsed
}

# Mirrors ProviderDiagnostics.classify (Java). Diagnostics only; do not overclaim root cause.
function Get-DiagnosticCategory([int]$status, [bool]$parseOk, [string]$body) {
    if ($status -ge 200 -and $status -lt 300) {
        if ($parseOk) { return "OK" } else { return "RESPONSE_PARSE_ERROR" }
    }
    switch ($status) {
        401 { return "AUTH_ERROR" }
        403 { return "AUTH_ERROR" }
        400 { return "REQUEST_SHAPE_ERROR" }
        404 { if ($body -and $body.ToLower().Contains("model")) { return "MODEL_ROUTE_DOWN" } else { return "REQUEST_SHAPE_ERROR" } }
        408 { return "NETWORK_ERROR" }
        429 { return "RATE_LIMIT_OR_QUOTA" }
        500 { return "PROVIDER_DOWN" }
        502 { return "PROVIDER_DOWN" }
        503 { return "PROVIDER_DOWN" }
        504 { return "PROVIDER_DOWN" }
        default { if ($status -ge 500) { return "PROVIDER_DOWN" } else { return "UNKNOWN_PROVIDER_ERROR" } }
    }
}

function Get-StatusDescription([int]$status) {
    switch ($status) {
        200 { return "OK" }
        400 { return "Bad Request" }
        401 { return "Unauthorized" }
        403 { return "Forbidden" }
        404 { return "Not Found" }
        408 { return "Request Timeout" }
        429 { return "Too Many Requests" }
        500 { return "Internal Server Error" }
        502 { return "Bad Gateway" }
        503 { return "Service Unavailable" }
        504 { return "Gateway Timeout" }
        default { return "HTTP $status" }
    }
}

Add-Type -AssemblyName System.Net.Http | Out-Null

Write-Output "[health] baseUrl=$normalizedBase"
Write-Output "[health] path=$MessagesPath"
Write-Output "[health] syntheticPromptOnly=`"$SyntheticPrompt`" (no repository context)"
Write-Output ""

foreach ($model in $modelList) {
    $bodyObj = [ordered]@{
        model      = $model
        max_tokens = 16
        messages   = @(@{ role = "user"; content = $SyntheticPrompt })
    }
    $bodyJson = $bodyObj | ConvertTo-Json -Compress -Depth 5

    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromSeconds(30)
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, $uri)
    $req.Headers.TryAddWithoutValidation("x-api-key", $apiKey) | Out-Null
    $req.Headers.TryAddWithoutValidation("anthropic-version", $AnthropicVersion) | Out-Null
    $req.Content = New-Object System.Net.Http.StringContent($bodyJson, [System.Text.Encoding]::UTF8, "application/json")

    Write-Output "----- model=$model -----"
    try {
        $resp = $client.SendAsync($req).GetAwaiter().GetResult()
        $status = [int]$resp.StatusCode
        $reason = if ($resp.ReasonPhrase) { $resp.ReasonPhrase } else { Get-StatusDescription $status }
        $respBody = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()

        # Selected SAFE headers only (never request headers / secrets).
        function Hdr([string]$name) {
            $v = $null
            $out = [string]::Empty
            if ($resp.Headers.TryGetValues($name, [ref]$v)) { return ($v -join ", ") }
            if ($resp.Content -and $resp.Content.Headers.TryGetValues($name, [ref]$v)) { return ($v -join ", ") }
            return ""
        }
        $hDate   = Hdr "Date"
        $hServer = Hdr "Server"
        $hCfRay  = Hdr "CF-RAY"
        $hCType  = Hdr "Content-Type"
        $hCLen   = Hdr "Content-Length"
        $hRetry  = Hdr "Retry-After"
        $reqId   = Hdr "request-id"
        if ([string]::IsNullOrWhiteSpace($reqId)) { $reqId = Hdr "x-request-id" }

        $parseOk = $false
        if ($status -ge 200 -and $status -lt 300) {
            try {
                $parsed = $respBody | ConvertFrom-Json
                if ($parsed.content) {
                    foreach ($block in $parsed.content) { if ($block.text) { $parseOk = $true; break } }
                }
            } catch { $parseOk = $false }
        }

        $category = Get-DiagnosticCategory $status $parseOk $respBody
        $preview = if ($status -ge 200 -and $status -lt 300 -and $parseOk) { "<n/a>" } else { Get-SafePreview $respBody }

        Write-Output "model=$model"
        Write-Output "statusCode=$status"
        Write-Output "statusDescription=$reason"
        Write-Output "diagnosticCategory=$category"
        Write-Output "errorBodyPreview=$preview"
        Write-Output "header.Date=$hDate"
        Write-Output "header.Server=$hServer"
        Write-Output "header.CF-RAY=$hCfRay"
        Write-Output "header.Content-Type=$hCType"
        Write-Output "header.Content-Length=$hCLen"
        Write-Output "header.Retry-After=$hRetry"
        Write-Output "requestId=$reqId"
    } catch {
        # Transport-level failure (timeout, DNS, connection reset). Never includes secrets.
        Write-Output "model=$model"
        Write-Output "statusCode=-1"
        Write-Output "statusDescription=<network>"
        Write-Output "diagnosticCategory=NETWORK_ERROR"
        Write-Output ("errorBodyPreview=" + (Get-SafePreview $_.Exception.Message))
    } finally {
        if ($client) { $client.Dispose() }
        if ($req) { $req.Dispose() }
    }
    Write-Output ""
}
