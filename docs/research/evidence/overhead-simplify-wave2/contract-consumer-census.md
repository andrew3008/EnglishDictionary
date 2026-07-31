# Overhead simplify wave2 — contract consumer census

| Field | Value |
|-------|-------|
| Date | 2026-07-31 |
| Plan | `overhead_simplify_wave2_d260b1ba` rev 6 |
| Method | Repo-wide `rg` across `*.java`, `*.md`, `*.yml`/`*.yaml`, `*.json`, scripts; no production code changed during pre-flight |
| Status | Pre-flight + Wave B.2 census recorded |

## 1. HTTP operational fields to remove (`GET /actuator/tracing`)

| JSON field | Production consumers | Test consumers | Docs | E2E / samples / benchmarks / scripts / monitoring | External SRE (in-repo) | Disposition |
|------------|----------------------|----------------|------|-----------------------------------------------------|------------------------|-------------|
| `sampling.liveRouteRatios` | `LegacyTracingDiagnosticsEndpoint` only | `LegacyTracingDiagnosticsEndpointTest` | Characterization table in `docs/architecture/platform-tracing-scrubbing-validation-characterization.md`; historical dumps under `docs/analysis/perplexity-review/*` | None | Unknown outside repo; none in-repo | **REMOVE** (security/disclosure hardening). Docs update required. |
| `sampling.liveDropPaths` | same | same | same | None | same | **REMOVE** |
| `sampling.liveForceRecordHeaderValues` | same | same | same | None | same | **REMOVE** |
| `scrubbing.customRulesSource` | same (hardcoded constant) | same | historical dumps only | None | same | **REMOVE** |
| `scrubbing.customRulesVisible` | same | same | historical dumps only | None | same | **REMOVE** |
| `scrubbing.note` | same | same | historical dumps only | None | same | **REMOVE** |

**Compatibility aliases / masked copies:** forbidden. Fields must disappear (not become `"***"`).

**Blocker status:** no mandatory in-repo consumer requires keeping raw values. Proceed with Wave A.

## 2. Java / JMX client methods (`PlatformTracingJmxClient`)

| Method | Visibility | Published artifact | main | test | samples/benchmarks/E2E/scripts | docs | reflection | Deletion verdict |
|--------|------------|--------------------|------|------|--------------------------------|------|------------|------------------|
| `getLiveRouteRatios()` | `public` | yes (`platform-tracing-spring-boot-autoconfigure`) | `LegacyTracingDiagnosticsEndpoint` only | `PlatformTracingJmxClientTest`, `LegacyTracingDiagnosticsEndpointTest` | none | characterization + historical dumps | not found | **APPROVED after Wave A switch** — no remaining prod consumer outside endpoint |
| `getLiveDropPathPrefixes()` | `public` | yes | same | same | none | same | not found | **APPROVED after Wave A switch** |
| `getLiveForceRecordValues()` | `public` | yes | same | same (`LegacyTracingDiagnosticsEndpointTest` only among endpoint tests) | none | same | not found | **APPROVED after Wave A switch** |

**MBean source attributes:**

| Attribute | Platform SAMPLING MBean (post P6.6) | Wave2 action |
|-----------|-------------------------------------|--------------|
| `RouteRatios` | **present** (read-only) | Spring reads locally; HTTP exposes `routeRatioCount` only |
| `DropPathPrefixes` | **already DELETE_OBSOLETE_READ** in P6.6 | Count method returns `Optional.empty()` → HTTP `null` (unavailable ≠ 0); Wave2 does **not** re-add the attribute |
| `ForceRecordValues` | **already DELETE_OBSOLETE_READ** in P6.6 | same |

Wave2 does not change Agent/JMX exposure.

**Replacement:** `getLiveRouteRatioCount()`, `getLiveDropPathCount()`, `getLiveForceRecordValueCount()` → `Optional<Integer>` (empty ≠ 0).

## 3. Formal bounds (for contract tests)

| Collection | Formal bound | Source |
|------------|--------------|--------|
| route ratios | **100** entries max | `SpringActuatorTracingPolicyAdapter.MAX_ROUTE_RATIOS` (mirrors Agent wire bound) |
| drop paths | no Spring-side formal max found for live JMX list | use representative multi-element collection; not claimed as maximum-boundary proof |
| force-record values | no Spring-side formal max found for live JMX list | same |

## 4. Reflection / string invocation

Searched for method name strings as reflective targets: **none found**.

## 5. Public API visibility note

`PlatformTracingJmxClient` is a public class in a published Spring Boot autoconfigure artifact. In-repo zero consumers after Wave A is necessary but not a formal guarantee against out-of-repo callers. Pre-production policy allows removal; no compatibility bridge is added.

## 6. Wave B.2 outcome (2026-07-31)

| Method | Remaining prod consumers after Wave A | Action taken |
|--------|--------------------------------------|--------------|
| `getLiveRouteRatios` | none (endpoint uses count API) | **DELETED**; `getLiveRouteRatioCount()` reads `RouteRatios` via `server.getAttribute` and returns `raw.size()` |
| `getLiveDropPathPrefixes` | none | **DELETED**; `getLiveDropPathCount()` uses `getStringArrayAttribute(...).map(arr -> arr.length)` |
| `getLiveForceRecordValues` | none | **DELETED**; `getLiveForceRecordValueCount()` same pattern |

Private helper `arrayToList` removed (sole consumers were the deleted raw getters). Platform MBean attributes unchanged.

## 7. Post-implementation-audit remediation (2026-07-31, autoconfigure-post-p6-simplify)

The consolidated post-implementation audit (`docs/research/evidence/autoconfigure-post-p6-simplify/consolidated-post-implementation-audit.md`,
§3 Wave2, §12) found `getLiveDropPathCount()` / `getLiveForceRecordValueCount()` / HTTP `sampling.dropPathCount` /
`sampling.forceRecordValueCount` always evaluated to `Optional.empty()` / `null` against the current Agent (§2 of
this document already recorded the underlying MBean attributes as `DELETE_OBSOLETE_READ` in P6.6), so they were
perpetual dead diagnostics rather than live signals.

| Method / field | Action |
|-----------------|--------|
| `PlatformTracingJmxClient.getLiveDropPathCount()` | **REMOVED** |
| `PlatformTracingJmxClient.getLiveForceRecordValueCount()` | **REMOVED** |
| `LegacyTracingDiagnosticsEndpoint` HTTP `sampling.dropPathCount` | **REMOVED** |
| `LegacyTracingDiagnosticsEndpoint` HTTP `sampling.forceRecordValueCount` | **REMOVED** |
| `getLiveRouteRatioCount()` / HTTP `sampling.routeRatioCount` | **KEPT** — `RouteRatios` remains a live Agent attribute |

No new production classes or MBean attributes were introduced by this remediation.
