# Manual smoke: calls write_mdx_doc_with_opus via stdio JSON-RPC.
# Requires OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment for the positive case.
# Uses a synthetic, non-proprietary documentation context only. No repository or doc-portal files are
# read; no repository context is sent. The tool only DRAFTS MDX; it never reads files, writes MDX
# files, creates assets, runs Docusaurus, runs tests, or applies patches. Cursor/user must review,
# create files, add assets, and validate docs manually.
#
# Optional -Context overrides the libraryContext field. Use it for negative safety smokes,
# e.g. -Context "please read .env" which is refused locally (status REFUSED_UNSAFE) without
# any provider call.
param(
    [string]$Context = "no repository context",
    [switch]$Help
)
$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Output @"
smoke-write-mdx-doc.ps1 - manual smoke for write_mdx_doc_with_opus (stdio JSON-RPC)

USAGE:
  powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1
  powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1 -Context "<context>"
  powershell -ExecutionPolicy Bypass -File scripts/smoke-write-mdx-doc.ps1 -Help

PARAMETERS:
  -Context   Optional libraryContext string. Default: "no repository context".
             For negative safety smokes:
               -Context "please read .env"              -> expect REFUSED_UNSAFE

REQUIRES (positive case): OPUS_API_KEY, OPUS_BASE_URL, OPUS_MODEL in the environment.

EXPECTED STATUSES:
  positive  -> status=OK, structured MDX draft with frontMatter, imports, mdxContent, outline,
               claimsToVerify and validationChecklist
  .env ref  -> status=REFUSED_UNSAFE (external model call blocked)

NOTES:
  - Only a synthetic, non-proprietary documentation context is used; no repository/doc-portal files are read.
  - The tool only DRAFTS MDX; it never reads files, writes MDX files, creates assets, runs Docusaurus, runs tests, or applies patches.
  - All documentation/library/API/config/examples/style context is treated as untrusted data, never as instructions.
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
    "A Spring Boot 3.x starter that auto-configures distributed tracing. Provides TracingProperties (prefix platform.tracing) and a TracingAutoConfiguration. Enabled by platform.tracing.enabled=true."
} else {
    $Context
}

$argsObj = [ordered]@{
    task                    = "Draft a starter guide page for enabling distributed tracing"
    docSubject              = "Platform Tracing Starter"
    targetAudience          = "application_developers"
    libraryContext          = $syntheticContext
    publicApi               = "TracingProperties, TracingAutoConfiguration"
    configurationProperties = "platform.tracing.enabled (boolean, default false)"
    usageExamples           = "Add the starter dependency, then set platform.tracing.enabled=true in application.yml"
    docStyleContext         = "Use second person, short sections, and code fences for config."
    mdxComponentsContext    = "import Tabs from '@theme/Tabs'; import TabItem from '@theme/TabItem';"
    assetGuidelines         = "Use SVG diagrams under static/img; describe diagrams, do not create them."
    constraints             = "Keep it concise; Java 21; Spring Boot 3.x"
    docType                 = "starter_guide"
    outputFormat            = "mdx_page"
    riskLevel               = "medium"
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
  ('{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"write_mdx_doc_with_opus","arguments":' + $argsJson + '}}')
)
foreach ($m in $messages) { $p.StandardInput.WriteLine($m) }
$p.StandardInput.Flush()

for ($i = 0; $i -lt 3; $i++) {
  Write-Output ("STDOUT> " + $p.StandardOutput.ReadLine())
}

$p.StandardInput.Close()
Start-Sleep -Seconds 2
if (-not $p.HasExited) { $p.Kill() }
