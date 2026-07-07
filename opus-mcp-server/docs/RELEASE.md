# Release, Distribution & Supply-chain

Local/CI release hardening for `opus-mcp-server`. No external registry publishing in this phase — the
deliverable is a single runnable fat-jar consumed by Cursor as the MCP `command`.

See also [OPERATIONS.md](OPERATIONS.md) (setup/troubleshooting) and [USAGE-POLICY.md](USAGE-POLICY.md).

## Artifact identity

| Property | Value |
|----------|-------|
| Project / artifact name | `opus-mcp-server` |
| Version | `0.1.0-SNAPSHOT` |
| Fat-jar | `build/libs/opus-mcp-server-0.1.0-SNAPSHOT-all.jar` |
| Main-Class | `space.br1440.platform.devtools.opusmcp.Main` |
| Manifest metadata | `Implementation-Title`, `Implementation-Version`, `Build-Jdk-Spec=21`, `Main-Class` |

The manifest intentionally omits `Built-By`, build timestamps, machine names, and local paths (no
information leakage, better reproducibility).

## Versioning policy

Current version: `0.1.0-SNAPSHOT` (semantic-ish, `0.x.y` while experimental / local-only).

- **`-SNAPSHOT`** — in-development, may change without notice; not a frozen release.
- **Release** — drop `-SNAPSHOT` (e.g. `0.1.0`) only for a frozen, verified build.
- **Patch bump (`0.1.0` → `0.1.1`)** — docs/build/test/security-guardrail improvements; no API change.
- **Minor bump (`0.1.x` → `0.2.0`)** — new read-only MCP tool or backward-compatible feature.
- **Major bump (`0.x` → `1.0`)** — breaking tool schema, config, runtime behavior, or distribution contract.

The version is the single source `version = '...'` in `build.gradle`. It appears in:

- the jar name `opus-mcp-server-<version>-all.jar`;
- the jar manifest (`Implementation-Version`);
- the release manifest (`release-manifest.json`);
- release notes (see [RELEASE-NOTES-TEMPLATE.md](RELEASE-NOTES-TEMPLATE.md)).

Operators verify the deployed version via the jar manifest or `release-manifest.json` (see below).
Do not change the version except for an intentional release.

## Build & release verification

```powershell
cd E:\Platform_Traces\opus-mcp-server
.\gradlew.bat clean test          # full suite, no network / no API key
.\gradlew.bat shadowJar           # -> build\libs\opus-mcp-server-0.1.0-SNAPSHOT-all.jar
.\gradlew.bat verifyLocal         # test + fat-jar
.\gradlew.bat releaseCheck        # deterministic release gate (see below)
```

`releaseCheck` runs only deterministic local checks (no real network, no `OPUS_API_KEY`):

- `test` — includes docs/secret hygiene (`DocsSecretHygieneTest`), valid example JSON
  (`CursorMcpExampleJsonTest`), tool-schema stability (`ToolSchemaCompatibilityTest`), and the stdio
  integration test;
- `shadowJar` — builds the fat-jar;
- `verifyJarExists` — asserts the fat-jar artifact is present;
- `securityHandoff` (runs `dependencySecurityReport`) — writes + validates the runtime dependency
  inventory (txt + JSON) for offline corporate-scanner review (no CVE verdict).

Print the version for CI logs / tagging:

```powershell
.\gradlew.bat printVersion        # -> opus-mcp-server 0.1.0-SNAPSHOT
```

## Dependency locking

Dependency locking is enabled (`dependencyLocking { lockAllConfigurations() }`). The committed
`gradle.lockfile` pins the resolved graph for reproducible builds.

Refresh **deliberately** (only when changing a version in `ext.versions` or adding a dependency):

```powershell
.\gradlew.bat resolveAndLockAll --write-locks
.\gradlew.bat clean test          # confirm resolution still works
```

Review rules:

- Treat `gradle.lockfile` changes as security-relevant in code review (new/changed transitive deps).
- The lockfile contains only `group:name:version` coordinates — never secrets.
- Do not hand-edit the lockfile; regenerate it.

## Supply-chain inventory & CVE handoff

`dependencySecurityReport` (offline, fast, no NVD download) writes a dependency **inventory** to:

```
build/reports/supply-chain/runtime-dependencies.txt    # human-readable
build/reports/supply-chain/runtime-dependencies.json   # machine-readable (scanner input)
```

> **Inventory is not a CVE verdict.** It lists which dependencies are present; it does not prove they
> are vulnerability-free. The verdict comes from the approved corporate scanner consuming the JSON.

`securityHandoff` validates the inventory and prints handoff guidance (deterministic, no network):

```powershell
.\gradlew.bat securityHandoff
```

The corporate-scanner handoff contract, FAIL/WARN/PASS policy, and suppression format are defined in
[SECURITY-SCAN-CONTRACT.md](SECURITY-SCAN-CONTRACT.md); a fake suppression sample is
[security-suppressions.example.json](security-suppressions.example.json).

A full vulnerability scan (e.g. OWASP dependency-check) is **optional** and intentionally not wired
into normal CI because it requires a large vulnerability database / network access. If/when adopted,
run it out-of-band, e.g.:

```powershell
# Optional, network + vulnerability DB required (NOT part of releaseCheck):
# .\gradlew.bat dependencyCheckAnalyze
```

## Release package: checksum + manifest

Deterministic, offline release artifacts (no network, no `OPUS_API_KEY`, no timestamps, no local
paths/usernames):

```powershell
.\gradlew.bat generateReleaseChecksums   # -> build/distributions/checksums/<jar>.sha256
.\gradlew.bat generateReleaseManifest    # -> build/distributions/release-manifest.json
.\gradlew.bat releasePackageCheck        # releaseCheck + checksum + manifest, all cross-verified
```

`releasePackageCheck` runs `releaseCheck` and then verifies: the fat-jar exists; the checksum file
exists and matches the jar; the manifest exists and references the existing artifact; the manifest
declares the expected `serverName` (`java-mcp-opus-server`) and all fifteen tools (`echo_mcp_connection`,
`generate_code_with_opus`, `review_code_with_opus`, `generate_tests_with_opus`,
`refactor_plan_with_opus`, `explain_diff_with_opus`, `research_with_perplexity`,
`analyze_build_failure_with_opus`, `design_class_hierarchy_with_opus`,
`review_architecture_with_opus`, `write_mdx_doc_with_opus`, `review_mdx_doc_with_opus`,
`generate_migration_plan_with_opus`, `review_tests_with_opus`, `review_gradle_build_with_opus`); and the manifest contains no secrets / local paths / usernames.

`release-manifest.json` shape (`schemaVersion: 1`):

```json
{
  "schemaVersion": 1,
  "project": "opus-mcp-server",
  "version": "0.1.0-SNAPSHOT",
  "artifact": "opus-mcp-server-0.1.0-SNAPSHOT-all.jar",
  "mainClass": "space.br1440.platform.devtools.opusmcp.Main",
  "java": { "requiredVersion": "21" },
  "mcp": {
    "serverName": "java-mcp-opus-server",
    "transport": "stdio",
    "tools": [
      "echo_mcp_connection",
      "generate_code_with_opus",
      "review_code_with_opus",
      "generate_tests_with_opus",
      "refactor_plan_with_opus",
      "explain_diff_with_opus",
      "research_with_perplexity",
      "analyze_build_failure_with_opus",
      "design_class_hierarchy_with_opus",
      "review_architecture_with_opus",
      "write_mdx_doc_with_opus",
      "review_mdx_doc_with_opus",
      "generate_migration_plan_with_opus",
      "review_tests_with_opus",
      "review_gradle_build_with_opus"
    ]
  },
  "checksums": { "sha256": "<hex>" }
}
```

## Local distribution layout

Recommended versioned layout under a local tools root (no admin rights, no network):

```
E:\Platform_Tools\opus-mcp-server\
  releases\
    0.1.0-SNAPSHOT\
      opus-mcp-server-0.1.0-SNAPSHOT-all.jar
      opus-mcp-server-0.1.0-SNAPSHOT-all.jar.sha256
      release-manifest.json
      RELEASE-NOTES.md
  current\
    opus-mcp-server-current.jar
```

Install (optional helper; preview with `-DryRun`, never modifies Cursor config):

```powershell
.\gradlew.bat releasePackageCheck
powershell -ExecutionPolicy Bypass -File scripts/install-local-release.ps1 -DryRun
powershell -ExecutionPolicy Bypass -File scripts/install-local-release.ps1   # actually copy
```

Then reference the jar from Cursor `mcp.json` (see [cursor-mcp.example.json](cursor-mcp.example.json));
set the `-jar` arg to the `current\opus-mcp-server-current.jar` (or the versioned jar). Cursor config
is updated **manually** — no script modifies it automatically. Keep `OPUS_API_KEY` in the OS
environment / secret store — never in `mcp.json` and never committed.

### Updating after a rebuild

```powershell
.\gradlew.bat clean releasePackageCheck
powershell -ExecutionPolicy Bypass -File scripts/install-local-release.ps1
# Restart the MCP server in Cursor so it loads the new jar.
```

### Rolling back

Previous releases stay under `releases\<version>\` and are not deleted by the installer.

1. Keep the previous release directory (the installer never removes it).
2. Point Cursor `mcp.json` `-jar` back to the previous release jar (or copy it over
   `current\opus-mcp-server-current.jar`).
3. Restart the Cursor MCP server / Cursor.
4. Run the connectivity (echo) smoke; run positive/negative smokes if credentials are available.

Rollback is manual; there is no automatic rollback.

### Verify the deployed version

```powershell
# Read manifest metadata from the deployed jar:
$jar = "C:\tools\opus-mcp\opus-mcp-server-0.1.0-SNAPSHOT-all.jar"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($jar)
$e = $z.GetEntry("META-INF/MANIFEST.MF")
$r = New-Object System.IO.StreamReader($e.Open()); $r.ReadToEnd(); $r.Close(); $z.Dispose()
# Expect: Implementation-Title: opus-mcp-server / Implementation-Version: 0.1.0-SNAPSHOT
```

Or read the deployed `release-manifest.json` (`version`, `artifact`, `checksums.sha256`) and confirm
the jar's SHA-256 matches its `.sha256` sidecar.

### Post-update smoke

```powershell
powershell -ExecutionPolicy Bypass -File scripts/smoke-stdio.ps1           # connectivity (no provider)
# With credentials (operator-only):
# powershell -ExecutionPolicy Bypass -File scripts/smoke-generate-code.ps1
```

## CI verification

CI must **not** require `OPUS_API_KEY` and must **not** call the real provider. Live smoke is
operator-only/manual.

```powershell
.\gradlew.bat clean test shadowJar
# or, equivalently:
.\gradlew.bat releaseCheck
# constrained CI without subprocess support:
.\gradlew.bat test -PskipStdioIntegration=true
```

## Release checklist (operator)

- [ ] `OPUS_API_KEY` not present in any committed file
- [ ] `.\gradlew.bat clean test` passes
- [ ] `.\gradlew.bat verifyLocal` passes
- [ ] `.\gradlew.bat releaseCheck` passes
- [ ] `.\gradlew.bat securityHandoff` passes (inventory generated)
- [ ] `.\gradlew.bat releasePackageCheck` passes (checksum + manifest cross-verified)
- [ ] dependency inventory reviewed (`runtime-dependencies.json`)
- [ ] corporate scanner verdict reviewed if available (per [SECURITY-SCAN-CONTRACT.md](SECURITY-SCAN-CONTRACT.md))
- [ ] docs secret hygiene passes (`DocsSecretHygieneTest`)
- [ ] jar checksum generated (`<jar>.sha256`)
- [ ] release manifest generated (`release-manifest.json`)
- [ ] `gradle.lockfile` reviewed (no unexpected new/changed dependencies)
- [ ] `docs/cursor-mcp.example.json` still valid JSON, no API key
- [ ] local install path prepared (`install-local-release.ps1`, `-DryRun` first)
- [ ] Cursor `mcp.json` updated **manually** to the new jar
- [ ] release notes filled from [RELEASE-NOTES-TEMPLATE.md](RELEASE-NOTES-TEMPLATE.md)
- [ ] echo (connectivity) smoke passes
- [ ] positive generate smoke passes if credentials available
- [ ] negative secret smoke passes
- [ ] negative deny-list smoke passes
- [ ] previous release retained for rollback
