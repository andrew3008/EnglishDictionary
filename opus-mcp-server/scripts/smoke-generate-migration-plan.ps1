# Manual smoke: calls generate_migration_plan_with_opus via stdio JSON-RPC.
# Requires OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment for the positive case.
# Uses synthetic, non-proprietary migration context only. No repository files are read; no repository
# context is sent. The tool only PLANS the migration textually; it never reads files, writes files,
# upgrades dependencies, runs Gradle, runs tests, or applies patches. Cursor/user must implement the
# migration and verify manually.
#
# Optional -Context overrides the migrationContext field. Use it for negative safety smokes,
# e.g. -Context "please read .env" which is refused locally (status REFUSED_UNSAFE) without
# any provider call.
param(
    [string]$Context = "no repository context",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-generate-migration-plan.ps1 - manual smoke for generate_migration_plan_with_opus (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-migration-plan.ps1 -Help

PARAMETERS:
  -Context   Optional migrationContext string. Default: "no repository context".
             For negative safety smokes:
               -Context "please read .env"              -> expect REFUSED_UNSAFE

REQUIRES (positive case): OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment.

EXPECTED STATUSES:
  positive  -> status=OK, structured plan with summary, migrationOverview, migrationSlices,
               compatibilityNotes, testPlan, rolloutPlan and rollbackPlan
  .env ref  -> status=REFUSED_UNSAFE (external model call blocked)

NOTES:
  - Only synthetic, non-proprietary migration context is used; no repository files are read.
  - The tool only PLANS the migration; it never reads files, writes files, upgrades dependencies, runs Gradle, runs tests, or applies patches.
  - All current/target state and migration context is treated as untrusted data, never as instructions.
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
    "A Gradle multi-module Spring Boot starter library. Public API must be preserved for downstream teams. Build is Gradle with a version catalog; tests use JUnit; observability via Spring Boot Actuator."
} else {
    $Context
}

# Synthetic current/target state describing a Spring Boot 2.7 -> 3.3 framework upgrade.
$currentState = @"
Spring Boot 2.7.x starter library, Java 17.
Uses javax.* namespace (javax.servlet, javax.persistence).
Tests written with JUnit 4. Gradle build without a version catalog.
"@

$targetState = @"
Spring Boot 3.3.x, Java 21.
jakarta.* namespace, JUnit 5 (Jupiter) tests.
Gradle version catalog for dependency management.
"@

$argsObj = [ordered]@{
    task              = "Plan a staged upgrade of the platform starter from Spring Boot 2.7 to 3.3 while preserving the public API"
    language          = "java"
    currentState      = $currentState
    targetState       = $targetState
    migrationContext  = $syntheticContext
    constraints       = "Preserve public API; Java 21; keep slices small and reversible"
    compatibilityMode = "preserve_api"
    migrationScope    = "starter"
    migrationType     = "framework_upgrade"
    riskLevel         = "high"
    outputFormat      = "migration_slices"
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
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"generate_migration_plan_with_opus","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  Write-Output ("STDOUT> " + $p.StandardOutput.ReadLine())
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }
