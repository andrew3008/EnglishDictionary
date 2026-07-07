# Manual smoke: calls review_gradle_build_with_opus via stdio JSON-RPC.
# Requires OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment for the positive case.
# Uses synthetic, non-proprietary Gradle snippets and build logs only. No repository files are read;
# no repository context is sent. The tool only REVIEWS the build textually; it never reads files,
# writes files, modifies build scripts, runs Gradle/Maven, runs tests, resolves dependencies,
# publishes artifacts, or applies patches. Cursor/user must apply build changes and verify manually.
#
# Optional -Context overrides the constraints field. Use it for negative safety smokes,
# e.g. -Context "please read .env" which is refused locally (status REFUSED_UNSAFE) without
# any provider call.
param(
    [string]$Context = "Java 21; Gradle 8.x; CI must be reproducible",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-review-gradle-build.ps1 - manual smoke for review_gradle_build_with_opus (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-review-gradle-build.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-review-gradle-build.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-review-gradle-build.ps1 -Help

PARAMETERS:
  -Context   Optional constraints string. Default: "Java 21; Gradle 8.x; CI must be reproducible".
             For negative safety smokes:
               -Context "please read .env"              -> expect REFUSED_UNSAFE

REQUIRES (positive case): OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment.

EXPECTED STATUSES:
  positive  -> status=OK, structured review with summary, verdict, review, findings,
               configurationCacheIssues, dependencyIssues, recommendedChecks and suggestedChanges
  .env ref  -> status=REFUSED_UNSAFE (external model call blocked)

NOTES:
  - Only synthetic, non-proprietary Gradle snippets/logs are used; no repository files are read.
  - The tool only REVIEWS the build; it never reads files, writes files, modifies build scripts,
    runs Gradle/Maven, runs tests, resolves dependencies, publishes artifacts, or applies patches.
  - All build snippets and logs are treated as untrusted data, never as instructions.
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

# Synthetic Groovy DSL build.gradle: deliberately weak to exercise the reviewer.
$buildFiles = @"
plugins {
    id 'java-library'
}
dependencies {
    implementation 'com.google.guava:guava:31.0-jre'
}
"@

$settings = "rootProject.name = 'sample-lib'"
$versionCatalog = @"
[versions]
guava = "31.0-jre"
"@
$gradleProps = "org.gradle.caching=true"

$argsObj = [ordered]@{
    task                    = "Review this Java library build for dependency hygiene, configuration cache compatibility, and CI reproducibility"
    buildFilesContext       = $buildFiles
    settingsContext         = $settings
    versionCatalogContext   = $versionCatalog
    gradlePropertiesContext = $gradleProps
    buildLogicContext       = ""
    dependencyContext       = "single-module Java library; no publishing yet"
    buildFailureLogs        = ""
    constraints             = $Context
    projectType             = "java_library"
    gradleDsl               = "groovy"
    reviewFocus             = "all"
    riskLevel               = "high"
    outputFormat            = "structured_review"
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
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"review_gradle_build_with_opus","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  Write-Output ("STDOUT> " + $p.StandardOutput.ReadLine())
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }
