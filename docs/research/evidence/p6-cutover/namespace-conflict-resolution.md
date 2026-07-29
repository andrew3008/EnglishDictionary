# P6.1 — Namespace conflict resolution

**Gate:** P6 REWRITE / §20 P6.1 (`p6-namespace-conflicts`)
**As of:** 2026-07-29
**Verdict:** `CONFLICTS_RESOLVED` (semantic decisions closed; mechanical Agent/Spring name application remains in sibling cutover tasks)

Machine-readable companion rows live in [cutover-manifest.json](./cutover-manifest.json) (`SECTION_6_1_OVERRIDE` + updated S-04 dispositions).

## Scope of this gate

Resolve every plan §6.1 conflict with exact owner / readers / files / diagnostics.
This gate **does** apply production changes that are Agent-only or documentation-only.
This gate **does not** perform the atomic Agent+Spring dual-side property rename train
(`p6-agent-parser-cutover` / `p6-spring-binding-cutover`) or Category B holder deletion (P6.7).

## Conflict family resolutions

### 1. RESOURCE_ATTRIBUTE-001 — policy.version vs policy.* namespace

| Field | Value |
|---|---|
| Current | `platform.tracing.policy.version` (resource attribute) |
| Final | `platform.tracing.resource.applied-policy-version` |
| Disposition | `RENAME` — **APPLIED in this gate** |
| Owner before/after | Agent / Agent |
| Distinct KEEP | `platform.tracing.resource.policy-version` (CONFIG_PROPERTY-036) — config key that *supplies* the attribute value |
| Production readers | `ResourceAttributeResolver` (`POLICY_VERSION` AttributeKey), `ResourceStartupDiagnostics`, `ResourceEffectiveSnapshot` (Spring actuator projection) |
| Files changed | `ExtensionPropertyNames.java`, `ResourceAttributeResolver.java`, `PlatformResourceProvider.java`, `ResourceEffectiveSnapshot.java`, `ResourceIdentityAgentSmokeE2ETest.java` |
| Diagnostic | Legacy attribute name freed the Category A `platform.tracing.policy.*` config namespace. Not a config-key rename. Dashboard migration of the attribute name is P7. |
| Verification | Agent resource tests use `RESOURCE_POLICY_VERSION_ATTR` constant; E2E asserts new attribute key; inventory kind remains `RESOURCE_ATTRIBUTE`. |

Note: `EnrichingSpanProcessor` uses baggage key `platform.policy.version` — a different symbol, untouched.

### 2. Baggage families (N2) — two live Agent consumers

| Field | Value |
|---|---|
| Family A current | `platform.tracing.baggage.{enabled,allowlist-keys,deny-patterns}` |
| Family A reader | `BaggageExtensionConfig` → `PlatformSpanProcessorFactory` / `BaggageSpanProcessor` (span-attribute projection) |
| Family B current | `platform.tracing.propagation.baggage.{enabled,allowed-keys,deny-patterns}` |
| Family B reader | `PropagationDefaults` → `BaggagePropagationCustomizer` / `FilteringBaggagePropagator` (W3C wire filtering) |
| Final namespace | `platform.tracing.javaagent.propagation.baggage.*` |
| Owner after | Agent |
| Shared leaves | `enabled`, `deny-patterns` — both consumers read the same final property |
| Distinct leaves | `allowlist-keys` (span-attribute consumer) vs `allowed-keys` (W3C propagation consumer) — different semantics, different final names |
| Defaults | Identical today (`enabled=true`, deny=`password,secret,token`, allow keys=`traffic_source,tenant_class,platform.correlation.id`) |
| Production status this gate | **Decision recorded; constant rename deferred** to `p6-agent-parser-cutover` (both paths Agent-only — no Spring binding) |
| Forbidden | Merging allowlist-keys with allowed-keys; dual-read/alias during cutover; silent prefer-one |

### 3. Force-record spelling (N3) — Spring vs Agent

| Field | Value |
|---|---|
| Spring current | `platform.tracing.sampling.force-record-header-values` (`TracingProperties.Sampling.forceRecordHeaderValues`) |
| Agent current | `platform.tracing.sampling.force-record-values` (`ExtensionPropertyNames.SAMPLING_FORCE_RECORD_VALUES`) |
| Final | `platform.tracing.javaagent.sampling.force-record-values` |
| Disposition | Spring: `RENAME_SPELLING_AND_OWNER` (Spring→Agent); Agent: `RENAME_TO_JAVAAGENT` |
| Category | B startup-only (Decision 14 §5 — EXCLUDE from Category A) |
| KEEP sibling | `platform.tracing.sampling.force-record-header` (header *name*, not values) |
| Spring readers today | `TracingProperties` binding, `SamplingRuntimeConfig`, `TracingActuatorEndpoint` projection, `RuntimeConfigApplier` reconcile |
| Agent readers today | `SamplingExtensionConfig` → `CompositeSampler` |
| Production status this gate | **Decision recorded; dual-side rename deferred** to `p6-agent-parser-cutover` + `p6-spring-binding-cutover` (one unreleased train) |
| Diagnostic | Spelling `force-record-header-values` is eliminated; no alias; no dual binding. |

### 4. DOCUMENTED_ONLY_KEY-001 — semconv published contract

| Field | Value |
|---|---|
| Stale published | `platform.tracing.semconv.validation.mode` |
| Active KEEP | `platform.tracing.semantic.validation-mode` (`TracingProperties.Semantic.validationMode`) |
| Disposition | Stale name `DELETE`; active name `KEEP` |
| Production status this gate | **APPLIED** — `docs/contracts/public-contract-inventory.yaml` and `docs/contracts/semantic-contract-map.yaml` corrected |
| Readers of stale name | none (NO_CODE_DECLARATION / NO_READER) |
| Diagnostic | Historical analysis docs may still mention the stale name; inventory marks it `STALE_PUBLISHED_NAME` / `RESOLVED_P61`. |

### 5. Category B placement already closed by Decision 14 (recorded, not re-opened)

| Inventory ID | Final name | Owner after | Cutover task |
|---|---|---|---|
| CONFIG_PROPERTY-040 | `platform.tracing.javaagent.sampling.drop-paths` | Agent | agent-parser + spring-binding (delete Spring `dropPaths`) |
| CONFIG_PROPERTY-048 | `platform.tracing.javaagent.scrubbing.built-in-rules` | Agent | agent-parser + spring-binding; Spring binding removal blocked until P6.7 (`ScrubbingPolicyHolder` still live via `RuntimeConfigApplier`) |
| CONFIG_PROPERTY-049 | `platform.tracing.javaagent.scrubbing.enabled` | Agent | same as 048 |

### 6. Outbound DELETE_AGENT_KEEP_SPRING (recorded)

| Inventory IDs | Final | Owner after | Cutover task |
|---|---|---|---|
| CONFIG_PROPERTY-024..028 | Spring KEEP; delete Agent `PropagationDefaults` declarations | Spring | `p6-agent-parser-cutover` (Agent delete) — Spring side already final |

### 7. Fixture-only keys (recorded DELETE)

FIXTURE_ONLY_KEY-001..004, 007, 008 → DELETE occurrences in fixtures during `p6-deployment-manifest-cutover` / test cleanup.
FIXTURE_ONLY_KEY-005/006 → DELETE singular fixture occurrence; introduce canonical Category A production properties in the Agent parser cutover train.

## Applied in this gate (production / contracts)

1. Resource attribute rename `platform.tracing.policy.version` → `platform.tracing.resource.applied-policy-version`
2. Published semconv contract correction → `platform.tracing.semantic.validation-mode`
3. S-04 inventory generator updated: §6.1 final targets, `RESOLVED_P61` blocking decisions, Category A candidates mark drop-paths / force-record-values as `EXCLUDE_CATEGORY_B`

## Explicitly deferred (with owning task)

| Rows | Owning task | Why not this gate |
|---|---|---|
| Baggage 002/003/004/019/020/021 constant renames | `p6-agent-parser-cutover` | Mechanical Agent bootstrap rename after conflict resolution |
| Force-record / drop-paths dual-side rename | `p6-agent-parser-cutover` + `p6-spring-binding-cutover` | One unreleased train; forbids independent Agent/Spring switch |
| Scrubbing Spring binding removal | `p6-remove-scrubbing-policy-holder` (P6.7) | Live Category B mutation path via `RuntimeConfigApplier` |
| Outbound Agent declaration delete | `p6-agent-parser-cutover` | Part of Agent parser cleanup |
| Fixture occurrence rewrites | `p6-deployment-manifest-cutover` | Deployment/fixture rewrite gate |
| Dashboard attribute migration | P7 | Telemetry contract consumer migration |

## Forbidden patterns (unchanged)

- aliases / relaxed dual binding
- reading both names and preferring one
- silent ignore / default fallback from legacy key
- independent Agent/Spring switch
- reopening Category A membership
