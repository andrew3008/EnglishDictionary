# Runtime Policy Control Architecture (PR-9A+)

Unified architecture for **sampling**, **scrubbing**, and **validation** runtime policy after PR-6/7/8,
extended with the **six-domain JMX control plane** reset (pre-production, 2026-06-18).

No new runtime policy domains in this document — enrichment/export/propagation are separate policy
surfaces but share the same JMX infrastructure.

Normative ADRs and runbooks:

- [ADR-runtime-sampling-policy.md](../decisions/ADR-runtime-sampling-policy.md)
- [runtime-sampling-control.md](runtime-sampling-control.md) — operational runbook (historical filename; covers all three policy domains)
- [platform-tracing-sampling-characterization.md](../architecture/platform-tracing-sampling-characterization.md)
- [platform-tracing-scrubbing-validation-characterization.md](../architecture/platform-tracing-scrubbing-validation-characterization.md)

## Three policy domains

| Domain | Agent holder | Processor / sampler consumer | Spring schema v1 view |
|--------|--------------|------------------------------|------------------------|
| Sampling | `SamplerStateHolder` | `CompositeSampler` → `SamplingPolicyEngine` | `SamplingRuntimeConfig` |
| Scrubbing | `ScrubbingPolicyHolder` | `ScrubbingSpanProcessor` | `ScrubbingRuntimeConfig` |
| Validation | `ValidationPolicyHolder` | `ValidatingSpanProcessor` | `ValidationRuntimeConfig` |

Validation degradation (annotate / warn / exception) is **not** dropped-span loss — no `VALIDATION_REJECTED` in span-loss taxonomy.

**PR-9F strict-mode guard:** Runtime updates that set `strict=true` are **rejected by default** unless startup flag `platform.tracing.validation.strict-runtime-allowed=true` (agent-side). Rejected update keeps LKG; version/source unchanged; `InvalidConfigCount` increments. Strict mode remains a CI/test/pre-prod diagnostic — not a safe default production runtime policy while it can throw from `Span.end()`.

**PR-9J access hardening (W-007 / W-013):**

| Surface | Guard | Notes |
|---------|-------|-------|
| **Actuator write** (`POST /actuator/tracing/{property}/{value}`) | `platform.tracing.actuator.mutation-enabled=false` (default) | When disabled, write returns HTTP 403; no JMX call; read (`GET /actuator/tracing`) unchanged |
| **Direct JMX** (six domain MBeans) | **Deployment / JVM / platform security** | Not protected by Actuator flag; Spring `RuntimeConfigApplier` also uses JMX |
| **Startup strict** (`platform.tracing.validation.strict=true`) | Ops policy + one-time startup WARN | Separate from PR-9F runtime guard; can still throw from `Span.end()` at startup strict |

Production recommendation: leave `platform.tracing.actuator.mutation-enabled=false`; restrict JMX via JVM `-Dcom.sun.management.jmxremote.*`, firewall, management port policy, and container RBAC.

## Six-domain JMX control plane

The monolithic `PlatformTracingControl` / `PlatformTracingControlMBean` and `jmx.operations` package
were **removed** (pre-production breaking change). Runtime control is now split into six focused
Standard MBeans, each with its own `ObjectName`:

| Domain | MBean interface | Implementation | ObjectName |
|--------|-----------------|----------------|------------|
| Sampling | `PlatformSamplingControlMBean` | `PlatformSamplingControl` | `space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl` |
| Scrubbing | `PlatformScrubbingControlMBean` | `PlatformScrubbingControl` | `space.br1440.platform.tracing:type=Scrubbing,name=PlatformScrubbingControl` |
| Validation | `PlatformValidationControlMBean` | `PlatformValidationControl` | `space.br1440.platform.tracing:type=Validation,name=PlatformValidationControl` |
| Export | `PlatformExportControlMBean` | `PlatformExportControl` | `space.br1440.platform.tracing:type=Export,name=PlatformExportControl` |
| Processor metrics | `PlatformProcessorMetricsControlMBean` | `PlatformProcessorMetricsControl` | `space.br1440.platform.tracing:type=Metrics,name=PlatformProcessorMetricsControl` |
| Diagnostics | `PlatformDiagnosticsControlMBean` | `PlatformDiagnosticsControl` | `space.br1440.platform.tracing:type=Diagnostics,name=PlatformDiagnosticsControl` |

**Old monolith ObjectName** (`type=Control,name=PlatformTracingControl`) is **no longer registered**.

Constants are duplicated across classloaders:

- Agent: `PlatformTracingObjectNames` (`platform-tracing-otel-extension`)
- Spring: `PlatformTracingJmxObjectNames` (`platform-tracing-spring-boot-autoconfigure`)

Equality is enforced by `ObjectNameConstantsConsistencyTest`.

### Registration (agent-side)

`PlatformTracingJmxRegistrar` is the **sole owner** of JMX registration:

- **Batch all-or-nothing:** all six MBeans register in one synchronized pass; failure triggers reverse-order rollback.
- **REPLACE_EXISTING:** if an ObjectName is already registered, it is unregistered before re-register.
- **Idempotent:** concurrent `tryRegisterMBeans()` calls register exactly once (6× `registerMBean`).
- **Shared `LongAdder invalidConfigCounter`** passed to sampling, scrubbing, validation, and diagnostics MBeans.
- **Early-registration timing unchanged:** registration triggers on first non-null `configHolder`; export suppliers remain late-bound.
- **Success log** (after all six succeed): lists all six ObjectName strings.
- **Failure:** `PlatformTracingJmxRegistrationException` with rollback failures attached via `addSuppressed`.

```text
PlatformTracingJmxRegistrar.tryRegisterMBeans()
  → register sampling   [ok] → push SAMPLING
  → register scrubbing  [ok] → push SCRUBBING
  → register validation [ok] → push VALIDATION
  → register export     [FAIL]
  → rollback: unregister VALIDATION, SCRUBBING, SAMPLING
  → throw PlatformTracingJmxRegistrationException(cause + suppressed rollback errors)
```

### Client (Spring-side)

`PlatformTracingJmxClient` replaces the deleted `SamplingControlClient`:

| Method | Semantics |
|--------|-----------|
| `allMBeansAvailable()` | `true` only when all six ObjectNames are registered |
| `getMBeansStatus()` | `Map<TracingControlDomain, Boolean>` — compile-time-safe domain keys |
| Mutations (`setRatio`, `updateSamplingPolicy`, …) | **Fail-closed:** throw `PlatformTracingJmxOperationException` if domain MBean absent |
| Read-only (`getCurrentRatio`, metrics, …) | **Graceful degradation:** `Optional.empty()` / empty collections; no throw |

`TracingControlDomain` enum (autoconfigure only): `SAMPLING`, `SCRUBBING`, `VALIDATION`, `EXPORT`, `PROCESSOR_METRICS`, `DIAGNOSTICS`.

## Write path (one domain = one JMX call)

```text
TracingProperties (Spring Environment)
  → RuntimeConfigApplier.applyAll()
      gate: client.allMBeansAvailable()  →  [REJECTED] if false
  → PlatformTracingJmxClient.update{Domain}Policy(*RuntimeConfig)
  → Platform{Domain}Control MBean (agent classloader, domain ObjectName)
  → *PolicyHolder.tryApplyPolicyUpdate / tryUpdate (CAS + last-known-good)
  → immutable *Snapshot (version++, source recorded)
  → sampler/processor hot path: holder.current() lock-free
```

Apply order in `RuntimeConfigApplier` (explicit, not enum iteration):

1. sampling → 2. scrubbing → 3. validation → 4. export → 5. diagnostics (propagation) → 6. log level (if non-blank)

Spring reconciliation uses `source = "spring-runtime-config"` on every domain update.
JMX 2-arg overloads delegate to 3-arg with `source = "JMX"`.
Blank/null `source` normalizes to `"JMX"` on the agent.

**Refresh:** `RefreshScopeRefreshedEvent` → `RuntimeConfigApplier` (not `@RefreshScope`) reads fresh `TracingProperties` proxy — no stale-read on refresh.

### RuntimeConfigApplier reliability (Spring-side diagnostics)

| Outcome | Log | Counters / state |
|---------|-----|------------------|
| Gate failed (any MBean missing) | `[REJECTED]` | `rejectedApplyCount++`; `lastConfigApplyResult` stays `null` |
| Gate passed, all domains OK | per-domain `[DOMAIN] applied` (debug) | `lastConfigApplyResult` with full `applied` set, empty `failed` |
| Gate passed, ≥1 domain failed | `[PARTIAL applied=N failed=M]` | `partialApplyCount++`; `lastConfigApplyResult` with exact `applied`/`failed` sets |

`ConfigApplyResult` record: `(Set<TracingControlDomain> applied, Set<TracingControlDomain> failed, Instant timestamp)`.

Micrometer: `platform.tracing.config.apply.result{domain,result}` per domain call (null-safe when `meterRegistry` absent).

**No rollback of already-applied domains** — JMX is non-transactional; partial state is recorded for operator diagnosis.

## Read path (Actuator + JMX)

`GET /actuator/tracing` exposes per domain:

| Layer | Meaning |
|-------|---------|
| **Configured** | `TracingProperties` — Spring input / reconciliation layer |
| **Live** | Agent MBean via `PlatformTracingJmxClient` — active runtime state |
| **Diagnostics** | `configVersion`, `configSource` per domain |

Authoritative runtime diagnostics: **`sampling.configSource`**, **`scrubbing.configSource`**, **`validation.configSource`** (and matching `configVersion`).

Legacy caveat: top-level `config.lastUpdatedSource` reflects **sampling only** (pre–PR-8D). Prefer per-domain `configSource` fields.

**Actuator mutation guard (PR-9J):** `GET /actuator/tracing` is always available when the endpoint is exposed. `POST /actuator/tracing/{property}/{value}` requires `platform.tracing.actuator.mutation-enabled=true` (default `false`). Read model includes `actuator.mutationEnabled`.

**Agent-vs-Spring drift (validation):** `validation.strictRuntimeAllowed` in the read model is the Spring configured view. `liveStrictRuntimeAllowed` is the agent startup enforcement flag read from the MBean. The agent reads `strictRuntimeAllowed` from agent startup configuration (`ExtensionConfig` / `ConfigProperties`, `ExtensionPropertyNames` keys); Spring binding may differ if Spring config is not the agent startup source. Operators should rely on **`liveStrictRuntimeAllowed`** for actual agent enforcement.

## JMX access model (PR-9J+)

Six domain MBeans are **direct runtime control surfaces** in the OTel Java Agent extension classloader.
They can mutate sampling, scrubbing, validation, export, and diagnostics policies via JMX regardless
of Actuator mutation settings.

| Caller | Typical source tag | Protected by Actuator guard? |
|--------|-------------------|------------------------------|
| Operator (jconsole, jmxterm) | `JMX` | **No** |
| Spring `RuntimeConfigApplier` | `spring-runtime-config` | **No** (intentional — reconciliation path) |
| Actuator HTTP write | via `PlatformTracingJmxClient` → JMX | **Yes** — blocked when `mutation-enabled=false` |

Production JMX access must be restricted by JVM JMX configuration, firewall, restricted management port, and platform security controls outside this codebase. No blanket agent-side JMX mutation disable was added — it would break Spring reconciliation without a trusted-caller model.

## Module boundaries

| Module | Responsibility | Must not |
|--------|----------------|----------|
| `platform-tracing-core` | Pure Java domain/policy logic (sampling engine, scrubbing merge, enrichers, …) | Spring, JMX, OTel agent runtime |
| `platform-tracing-otel-extension` | OTel adapters: samplers, span processors, six domain MBeans, `PlatformTracingJmxRegistrar`, agent-side holders/snapshots | Spring imports on hot path |
| `platform-tracing-spring-boot-autoconfigure` | `TracingProperties`, `RuntimeConfigApplier`, `*RuntimeConfig` views, `PlatformTracingJmxClient`, Actuator, refresh listener | Second authoritative runtime snapshot |

Shared primitive: `DomainConfigHolder` in `platform-tracing-api` (CAS + LKG, visible to both classloaders).

## Consistency checklist (PR-6D–8C)

| Concern | Sampling | Scrubbing | Validation |
|---------|----------|-----------|------------|
| Atomic JMX update | `updateSamplingPolicy(..., source)` | `updateScrubbingPolicy(..., source)` | `updateValidationPolicy(..., source)` |
| Spring reconciler | PR-6E | PR-7C | PR-8C |
| LKG on invalid update | yes | yes | yes (CAS/build failure) |
| Invalid counter | `InvalidConfigCount++` (shared `LongAdder`) | yes | yes |
| Version on success | monotonic | monotonic | monotonic |
| Unknown rule names | N/A | skipped (startup parity) | N/A |

## Startup-only vs runtime-mutable

Runtime-mutable (schema v1, one JMX call each):

- **Sampling:** `enabled`, `ratio`, `dropPaths`, `forceRecordHeaderValues`, `routeRatios`
- **Scrubbing:** `enabled`, `builtInRules`
- **Validation:** `enabled`, `strict`
- **Export:** `enabled` (via export domain MBean)
- **Diagnostics:** `propagationEnabled`, `platformLogLevel`

Startup-only examples: sampling header **names**, SPI scrubbing rules, semantic `ValidationMode`, export endpoint topology.

## Known follow-ups

1. **Scrubbing live rule names** — MBean exposes `liveRuleCount` only; name-level read getter optional future work.
2. **`config.lastUpdatedSource`** — legacy sampling-only; per-domain `configSource` preferred.
3. **Route ratio longest-prefix-wins** — **PR-9G complete** (deterministic compile-time sort in `SamplingPolicySnapshot.normalizeRouteRatios`; see [platform-tracing-sampling-characterization.md](../architecture/platform-tracing-sampling-characterization.md)).
4. **Enrichment runtime config** — not part of PR-6/7/8; separate future PR.
5. **Core extraction** — sampling engine in `core.sampling` (PR-6B); validation snapshot/update in `core.validation` (PR-9C); scrubbing rule-name resolution prep in `otel-extension.scrubbing.policy` (PR-9D) → future `core.scrubbing`.

## PR-9B — Core extraction readiness

Full inventory: [platform-tracing-core-extraction-readiness.md](../architecture/platform-tracing-core-extraction-readiness.md).

- No class moves in PR-9B; ArchUnit extended (`CORE_MAIN_NO_JMX`, JMX forbidden in core policy packages).
- **In core today:** `core.sampling.*`, `core.validation.*` (pure Java, no OTel/Spring/JMX).
- **PR-9C completed (validation):** `ValidationSnapshot`, `ValidationPolicyUpdate` → `core.validation`.
- **PR-9D completed (scrubbing prep):** `ScrubbingRuleResolution` isolates pure rule-name validate/select; `ScrubbingSnapshot` / `RuleExecutionWrapper` stay agent-side.
- **Stay agent-side:** holders, OTel processors/samplers, six domain MBeans, compiled scrubbing snapshots.
- **Stay Spring-side:** `*RuntimeConfig`, `RuntimeConfigApplier`, `PlatformTracingJmxClient`.

## Related architecture docs

- [platform-tracing-control-refactoring-dossier.md](../architecture/platform-tracing-control-refactoring-dossier.md) — Phase 1 (WINNER_INTERNAL_DELEGATES, superseded)
- [platform-tracing-control-second-stage-refactoring-dossier.md](../architecture/platform-tracing-control-second-stage-refactoring-dossier.md) — Phase 2 analysis → implemented as six-domain reset
