$ErrorActionPreference = "Stop"
$Utf8 = New-Object System.Text.UTF8Encoding $false
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$Otel = Join-Path $Root "platform-starters-tracing\spring-boot-starter-platform-tracing-otel"
$Policy = Join-Path $Root "platform-starters-tracing\platform-tracing-runtime-policy"
$Api = Join-Path $Root "platform-starters-tracing\spring-boot-starter-platform-tracing-api"
$Util = Join-Path $Root "platform-starters-common\platform-starters-common-util"
$Docs = $PSScriptRoot

$Replacements = @(
    @{ Old = "space.br1440.platform.tracing.otel.runtime.versioned"; New = "space.br1440.platform.tracing.runtime.versioned" },
    @{ Old = "space.br1440.platform.tracing.otel.sampling"; New = "space.br1440.platform.tracing.runtime.sampling" },
    @{ Old = "space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceTraceContext"; New = "space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceContext" },
    @{ Old = "space.br1440.platform.tracing.otel.mdc.remote.RemoteServiceTraceMirror"; New = "space.br1440.platform.tracing.runtime.remote.RemoteServiceTraceMirror" },
    @{ Old = "space.br1440.platform.tracing.otel.propagation.control"; New = "space.br1440.platform.tracing.runtime.propagation.control" },
    @{ Old = "space.br1440.platform.tracing.otel.propagation.RequestIdSupport"; New = "space.br1440.platform.tracing.runtime.propagation.RequestIdSupport" }
)

function Read-Utf8([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, $Utf8)
}

function Write-Utf8([string]$Path, [string]$Text) {
    $dir = Split-Path $Path -Parent
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    if (-not $Text.EndsWith("`n")) {
        $Text += "`n"
    }
    $normalized = $Text -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($Path, $normalized, $Utf8)
}

function Rewrite-Text([string]$Text) {
    foreach ($pair in $Replacements) {
        $Text = $Text.Replace($pair.Old, $pair.New)
    }
    return $Text
}

function Get-ClassName([string]$Path) {
    $rel = $Path.Replace("\", "/")
    $marker = "/java/"
    $idx = $rel.LastIndexOf($marker)
    return $rel.Substring($idx + $marker.Length).Replace("/", ".").Replace(".java", "")
}

function Transfer-File([string]$Src, [string]$Dst) {
    $text = Rewrite-Text (Read-Utf8 $Src)
    Write-Utf8 $Dst $text
    Remove-Item -LiteralPath $Src
}

function Move-Tree([string]$SrcRel, [string]$DstRel, [string]$SourceSet) {
    $srcRoot = Join-Path $Otel "src\$SourceSet\java\$SrcRel"
    $dstRoot = Join-Path $Policy "src\$SourceSet\java\$DstRel"
    if (-not (Test-Path $srcRoot)) {
        return
    }
    Get-ChildItem -Path $srcRoot -Recurse -Filter *.java | ForEach-Object {
        $rel = $_.FullName.Substring($srcRoot.Length).TrimStart("\", "/")
        Transfer-File $_.FullName (Join-Path $dstRoot $rel)
    }
}

function Move-Named([string]$SrcRel, [string]$DstRel, [string]$SourceSet) {
    $src = Join-Path $Otel "src\$SourceSet\java\$SrcRel"
    $dst = Join-Path $Policy "src\$SourceSet\java\$DstRel"
    if (Test-Path $src) {
        Transfer-File $src $dst
    }
}

function Generate-Inventory {
    $records = New-Object System.Collections.Generic.List[object]
    Get-ChildItem -Path (Join-Path $Api "src\main\java") -Recurse -Filter *.java | ForEach-Object {
        $records.Add([ordered]@{
            class = Get-ClassName $_.FullName
            originModule = "spring-boot-starter-platform-tracing-api"
            whyNeeded = "published API contract"
            consumer = "BOTH"
            classification = "SHARED_AGENT_APPLICATION"
            modulePlacement = "KEEP:spring-boot-starter-platform-tracing-api"
        })
    }
    Get-ChildItem -Path (Join-Path $Util "src\main\java") -Recurse -Filter *.java | ForEach-Object {
        $needed = @("StringUtils", "MapUtils", "ArrayUtils") -contains $_.BaseName
        $records.Add([ordered]@{
            class = Get-ClassName $_.FullName
            originModule = "platform-starters-common-util"
            whyNeeded = "JDK-only utility already in a dedicated module"
            consumer = $(if ($needed) { "BOTH" } else { "APPLICATION" })
            classification = $(if ($needed) { "SHARED_AGENT_APPLICATION" } else { "ACCIDENTAL" })
            modulePlacement = "KEEP:platform-starters-common-util"
        })
    }
    $moveTokens = @(
        ".sampling.",
        ".runtime.versioned.",
        ".mdc.remote.RemoteServiceTraceContext",
        ".mdc.remote.RemoteServiceTraceMirror",
        ".propagation.control.",
        ".propagation.RequestIdSupport"
    )
    Get-ChildItem -Path (Join-Path $Otel "src\main\java") -Recurse -Filter *.java | ForEach-Object {
        $fqcn = Get-ClassName $_.FullName
        $move = $false
        foreach ($token in $moveTokens) {
            if ($fqcn.Contains($token)) { $move = $true; break }
        }
        if ($move) {
            $records.Add([ordered]@{
                class = $fqcn
                originModule = "spring-boot-starter-platform-tracing-otel"
                whyNeeded = "logging-agnostic domain/runtime primitive required by Agent and application"
                consumer = "BOTH"
                classification = "SHARED_AGENT_APPLICATION"
                modulePlacement = "MOVE:platform-tracing-runtime-policy"
            })
        } else {
            $records.Add([ordered]@{
                class = $fqcn
                originModule = "spring-boot-starter-platform-tracing-otel"
                whyNeeded = "application-plane implementation; present in current hybrid JAR only because of whole-module embedding"
                consumer = "APPLICATION"
                classification = "APPLICATION_ONLY"
                modulePlacement = "KEEP:spring-boot-starter-platform-tracing-otel"
            })
        }
    }
    $payload = [ordered]@{
        schemaVersion = 1
        generatedAt = "2026-08-16"
        commit = "5ac5d5a82cc021ff1bc0777f2e8b0c0c4d28750e"
        embeddedClassCount = $records.Count
        agentRuntimeModule = "NOT_CREATED"
        runtimePolicyModule = "platform-tracing-runtime-policy"
        hardAssertions = [ordered]@{
            approvedOtelClosureSlf4j = 0
            approvedOtelClosureMdc = 0
            approvedOtelClosureSpring = 0
        }
        classes = $records
    }
    $json = $payload | ConvertTo-Json -Depth 6
    Write-Utf8 (Join-Path $Docs "agent-runtime-class-closure.json") $json
}

Generate-Inventory

Move-Tree "space\br1440\platform\tracing\otel\sampling" "space\br1440\platform\tracing\runtime\sampling" "main"
Move-Tree "space\br1440\platform\tracing\otel\sampling" "space\br1440\platform\tracing\runtime\sampling" "test"
Move-Tree "space\br1440\platform\tracing\otel\runtime\versioned" "space\br1440\platform\tracing\runtime\versioned" "main"
Move-Tree "space\br1440\platform\tracing\otel\runtime\versioned" "space\br1440\platform\tracing\runtime\versioned" "test"
Move-Tree "space\br1440\platform\tracing\otel\propagation\control" "space\br1440\platform\tracing\runtime\propagation\control" "main"
Move-Tree "space\br1440\platform\tracing\otel\propagation\control" "space\br1440\platform\tracing\runtime\propagation\control" "test"
Move-Named "space\br1440\platform\tracing\otel\propagation\RequestIdSupport.java" "space\br1440\platform\tracing\runtime\propagation\RequestIdSupport.java" "main"
Move-Named "space\br1440\platform\tracing\otel\propagation\RequestIdSupportTest.java" "space\br1440\platform\tracing\runtime\propagation\RequestIdSupportTest.java" "test"
Move-Named "space\br1440\platform\tracing\otel\mdc\remote\RemoteServiceTraceContext.java" "space\br1440\platform\tracing\runtime\remote\RemoteServiceTraceContext.java" "main"
Move-Named "space\br1440\platform\tracing\otel\mdc\remote\RemoteServiceTraceMirror.java" "space\br1440\platform\tracing\runtime\remote\RemoteServiceTraceMirror.java" "main"
Move-Named "space\br1440\platform\tracing\otel\mdc\remote\RemoteServiceTraceContextTest.java" "space\br1440\platform\tracing\runtime\remote\RemoteServiceTraceContextTest.java" "test"
Move-Named "space\br1440\platform\tracing\otel\mdc\remote\RemoteServiceTraceMirrorTest.java" "space\br1440\platform\tracing\runtime\remote\RemoteServiceTraceMirrorTest.java" "test"

Get-ChildItem -Path (Join-Path $Otel "src") -Recurse -Directory | Sort-Object FullName -Descending | ForEach-Object {
    if (-not (Get-ChildItem -LiteralPath $_.FullName -Force | Where-Object { $_.Name -ne "." })) {
        Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
    }
}

$changed = 0
Get-ChildItem -Path (Join-Path $Root "platform-starters-tracing") -Recurse -Filter *.java | ForEach-Object {
    $original = Read-Utf8 $_.FullName
    $updated = Rewrite-Text $original
    if ($updated -ne $original) {
        Write-Utf8 $_.FullName $updated
        $changed++
    }
}

Write-Host "transfer complete, rewritten files: $changed"
