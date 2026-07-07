# Security Scan Contract (Phase 6B)

Integration contract between this project (which produces a **dependency inventory**) and an
**approved corporate CVE / SCA scanner** (which produces the **vulnerability verdict**).

> **Inventory is not a verdict.** `dependencySecurityReport` lists *which* dependencies are present.
> It does **not** prove they are free of known vulnerabilities. A CVE verdict must come from the
> approved corporate scanner / SCA tooling consuming the inventory below.

This is an **adapter-style integration point**: this project emits inventory; the scanner consumes it.
No corporate scanner API/CLI is invented or called here. Normal build/CI stays deterministic and
network-free.

## 1. Input artifacts (produced by this project)

Generated offline by `.\gradlew.bat dependencySecurityReport` (or `securityHandoff` / `releaseCheck`):

| Artifact | Purpose |
|----------|---------|
| `build/reports/supply-chain/runtime-dependencies.json` | Machine-readable inventory (primary scanner input) |
| `build/reports/supply-chain/runtime-dependencies.txt`  | Human-readable inventory (review / fallback) |

The JSON contains only `group:name:version` coordinates and project name/version — no local paths,
usernames, machine names, environment values, tokens, keys, or source code. No timestamp (kept
reproducible).

### JSON schema (`schemaVersion: 1`)

```json
{
  "schemaVersion": 1,
  "project": { "name": "opus-mcp-server", "version": "0.1.0-SNAPSHOT" },
  "configuration": "runtimeClasspath",
  "note": "Inventory only. Not a CVE verdict. Submit to corporate scanner.",
  "dependencies": [
    {
      "group": "com.fasterxml.jackson.core",
      "name": "jackson-databind",
      "version": "2.18.2",
      "coordinates": "com.fasterxml.jackson.core:jackson-databind:2.18.2"
    }
  ]
}
```

## 2. Expected scanner responsibility

The corporate scanner / SCA tool is responsible for:

- mapping each dependency coordinate to known CVEs / advisories;
- classifying severity (critical / high / medium / low);
- reporting fixed versions when available;
- honoring suppressions with an owner, reason, and expiry (see §5).

## 3. Expected scanner output contract

The scanner is expected to emit a result equivalent to:

```json
{
  "status": "PASS | WARN | FAIL",
  "project": { "name": "opus-mcp-server", "version": "0.1.0-SNAPSHOT" },
  "vulnerabilities": [
    {
      "coordinates": "group:name:version",
      "advisory": "CVE-YYYY-NNNN",
      "severity": "CRITICAL | HIGH | MEDIUM | LOW",
      "fixedVersion": "x.y.z or null",
      "suppressionId": "MCP-SEC-000N or null"
    }
  ]
}
```

`status` is the gate signal for CI; the array is the evidence. Exact field names may be adapted to the
corporate scanner's native format by the integrating team.

## 4. Fail / Warn / Pass policy

Default policy (may be tightened by company security standards):

**FAIL**
- critical vulnerability in a runtime dependency without an approved suppression;
- high vulnerability in a runtime dependency without an approved suppression;
- any vulnerable dependency with a known exploit and no approved exception.

**WARN**
- medium or low severity vulnerability;
- vulnerability in a test-only dependency;
- vulnerability with no fixed version available yet.

**PASS**
- no findings; or
- all findings suppressed with a valid owner, expiry, and reason.

> Final FAIL/WARN thresholds are owned by corporate security standards and may override these defaults.

## 5. Suppression / allowlist policy

Suppressions are explicit, owned, time-boxed exceptions. Format (`schemaVersion: 1`):

```json
{
  "schemaVersion": 1,
  "suppressions": [
    {
      "id": "MCP-SEC-0001",
      "coordinates": "group:name:version",
      "advisory": "CVE-YYYY-NNNN",
      "reason": "No reachable vulnerable code path in local stdio-only usage",
      "owner": "team-or-person",
      "expiresOn": "YYYY-MM-DD"
    }
  ]
}
```

Rules:
- every suppression MUST have `id`, `coordinates`, `advisory`, `reason`, `owner`, and `expiresOn`;
- expired suppressions are invalid and the finding is re-activated;
- suppressions are reviewed at each release;
- a sample (fake values only) lives at [security-suppressions.example.json](security-suppressions.example.json);
- do not add real suppressions without a real finding and explicit approval.

## 6. Handoff workflow

```powershell
.\gradlew.bat securityHandoff
# -> regenerates inventory + validates JSON shape + prints handoff guidance (no network, no verdict)
# Then submit build/reports/supply-chain/runtime-dependencies.json to the corporate scanner.
```

`releaseCheck` runs `securityHandoff` as part of the deterministic local gate, so the inventory is
always refreshed and validated at release time. It does **not** call a scanner and does **not** claim
a CVE verdict.

## 7. What this project does NOT do

- It does not call a corporate scanner, NVD, or OWASP dependency-check during normal build/CI.
- It does not download a vulnerability database.
- It does not require network access or credentials for inventory generation.
- It does not assert any dependency is vulnerability-free.

A full vulnerability scan (e.g. OWASP dependency-check) remains optional and out-of-band; see
[RELEASE.md](RELEASE.md).
