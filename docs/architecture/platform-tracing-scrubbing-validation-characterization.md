# Platform Tracing — Scrubbing / Validation / Enrichment characterization (PR-5B)

## Purpose

Freeze **current** scrubbing, validation, and enrichment processor behavior before PR-7 (scrubbing extraction) and PR-8 (validation/enrichment extraction). Golden-master / characterization tests — observed behavior, not target architecture.

## Scope of PR-5B

- Test-only changes in `platform-tracing-otel-javaagent-extension` and `platform-tracing-test`
- Harness helpers for scrubbing, validation, enrichment matrices
- Pipeline ordering characterization (factory + composite observable behavior)
- No production code moves or behavior changes

## Non-goals

- Extract scrubbing/validation/enrichment policy to core (PR-7/PR-8)
- Change rule order, merge semantics, circuit breaker thresholds, or processor wiring
- Full JMH baseline capture
- E2E exception-event scrubbing re-run (covered by existing `ExceptionEventScrubbingE2ETest`)

## Current scrubbing behavior summary

| Area | Observed behavior |
|------|-------------------|
| Execution point | `ScrubbingSpanProcessor.onEnding` (after enrichers add attributes) |
| Mandatory | Enabled by default; `updateScrubbingPolicy(false, …)` passthrough |
| Built-in rules | Key/value evaluation via `SpanAttributeScrubbingRule.evaluate`; DROP/MASK/HASH/TRUNCATE |
| Merge | `MergeEngine`: KEEP never weakens; critical DROP terminal; custom terminal ignored |
| Circuit breaker | Per-rule; critical OPEN → `<SCRUBBING_FAILED>` fail-closed; custom OPEN → skip |
| Events | Span **attributes** scrubbed; **event attributes not scrubbed** (documented boundary) |
| Loader | `ScrubbingRulesLoader` package-private; missing resource → empty list, no throw |

## Current validation behavior summary

| Area | Observed behavior |
|------|-------------------|
| Required span attrs | `platform.trace.type`, `platform.trace.result` on `onEnding` |
| Lenient (default) | Sets `platform.validation.missing`, export continues |
| Strict | Throws `TracingValidationException`, blocks span end |
| Disabled | `updateValidationPolicy(false, …)` → passthrough, no missing attr |
| Resource keys | Not validated per-span (startup diagnostics elsewhere) |

## Current enrichment behavior summary

| Area | Observed behavior |
|------|-------------------|
| Default type | From `SpanKind` (e.g. SERVER → `http_server`) |
| Override | Explicit `platform.trace.type` preserved (not overwritten by db detection) |
| DB client | `db.system.name` / `db.system` → `platform.trace.type=database` (CLIENT only) |
| Result | `success` default; ERROR status → `failure` |
| Remote service | CLIENT + ERROR only; from `peer.service` / `rpc.service` / DNS `server.address` |
| Classification | Separate `ClassificationSpanProcessor`: duration class + priority on error/slow |

## Scrubbing coverage matrix

| caseId | inputKey | inputValueShape | ruleSource | expectedScrubbed | expectedOutputShape | expectedFailureMode | winningRuleOrReason |
|--------|----------|-----------------|------------|------------------|---------------------|---------------------|---------------------|
| SCR-PW-01 | `db.password` | secret string | built-in `password` | yes | empty string | none | drop |
| SCR-JWT-01 | `token.value` | JWT `eyJ…` | built-in `jwt` | yes | empty string | none | drop |
| SCR-KEEP-01 | `just.text` | benign | password+jwt rules | no | preserved | none | keep |
| SCR-EMAIL-01 | `user.email` | email | built-in `email` | yes | `***` (no HMAC key) | none | mask |
| SCR-OAUTH-01 | `http.request.header.authorization` | Bearer token | built-in `oauth-header` | yes | empty string | none | drop |
| SCR-IP-01 | `client.address` | IPv4 | built-in `ip-address` | yes | prefix truncated (`10.1.2.0`) | none | truncate |

Additional security characterization (not in matrix): merge DROP beats HASH; prefixed secret keys; event attrs not scrubbed; rule exception does not break export.

## Validation coverage matrix

| caseId | category | mode | inputAttributes | expectedValid | expectedViolationCount | expectedFailureMode | expectedProcessorBehavior |
|--------|----------|------|-----------------|---------------|------------------------|---------------------|---------------------------|
| VAL-LEN-MISS | span | lenient enabled | none | no | n/a (missing attr recorded) | none | `platform.validation.missing` set, export OK |
| VAL-LEN-OK | span | lenient enabled | type+result | yes | 0 | none | missing attr absent |
| VAL-STRICT-MISS | span | strict enabled | none | no | n/a | `TracingValidationException` | span end throws |
| VAL-DISABLED | span | disabled | none | n/a | 0 | none | passthrough, no missing attr |

Violation details beyond missing-key list are not exposed on span — assert processor behavior only.

## Enrichment coverage matrix

| caseId | inputAttributes | config | expectedAddedAttributes | expectedPreservedAttributes | expectedOverwriteBehavior | expectedFailureMode |
|--------|-----------------|--------|-------------------------|----------------------------|---------------------------|---------------------|
| ENR-SERVER-OK | SERVER span | default | type=`http_server`, result=`success` | — | n/a | none |
| ENR-CLIENT-ERR | CLIENT ERROR + peer.service | default | type, result, remote_service | — | remote on error only | none |
| ENR-DB-OVERRIDE | CLIENT + db.system.name | default | type=`database` | — | overrides default http_client | none |
| ENR-PRESERVE-TYPE | CLIENT + preset type `rpc` + db attr | default | result=`success` | `platform.trace.type=rpc` | db override skipped when type preset | none |

## Pipeline ordering (observable)

Documented `PlatformSpanProcessorFactory` delegate order:

```text
BaggageSpanProcessor → EnrichingSpanProcessor → ScrubbingSpanProcessor → ValidatingSpanProcessor
→ ClassificationSpanProcessor → SpanWatchdogProcessor → MetricsSpanProcessor
```

Composite characterization proves:

- Enriching runs before Scrubbing (`platform.trace.type` present; authorization scrubbed)
- Scrubbing runs before Validation (scrubbed attrs + validation pass on enriched span)

## Tests added (PR-5B)

| Test class | Module | Role |
|------------|--------|------|
| `ScrubbingRuleMatrixCharacterizationTest` | otel-extension | 6-case scrubbing matrix |
| `ScrubbingEngineCharacterizationTest` | otel-extension | MergeEngine merge semantics |
| `ScrubbingSecurityCharacterizationTest` | otel-extension | High-risk security cases |
| `ScrubbingCircuitBreakerCharacterizationTest` | otel-extension | Critical/custom OPEN behavior |
| `ScrubbingLoaderCharacterizationTest` | otel-extension/scrubbing | Loader + disabled policy |
| `ScrubbingSpanProcessorCharacterizationTest` | otel-extension | Mandatory scrubbing, custom rule, HMAC |
| `ValidationPolicyCharacterizationTest` | otel-extension | Validation mode matrix |
| `ValidatingSpanProcessorCharacterizationTest` | otel-extension | Lenient export + disable |
| `ValidationRuntimePolicyCharacterizationTest` | otel-extension | Runtime policy version |
| `EnrichmentPolicyCharacterizationTest` | otel-extension | Enrichment matrix |
| `ClassificationCharacterizationTest` | otel-extension | Error → high priority |
| `PipelinePolicyCharacterizationTest` | otel-extension | Factory order + composite ordering |

### Harness additions (`platform-tracing-test`)

| Type | Purpose |
|------|---------|
| `ScrubbingDecisionCase` | Scrubbing matrix row |
| `ScrubbingAssertions` | Span attribute scrubbing checks |
| `ValidationDecisionCase` | Validation matrix row |
| `ValidationAssertions` | Missing attr / platform attr checks |
| `EnrichmentDecisionCase` | Enrichment matrix row |
| `EnrichmentAssertions` | Platform type/result/remote service checks |

## Existing tests preserved

All pre-PR-5B tests remain unchanged and green:

- `ScrubbingSpanProcessorTest`, `ScrubbingSpanProcessorAdvancedTest`, `ScrubbingSecurityNegativeTest`
- `MergeEngineTest`, `RuleCircuitBreakerTest`, `BuiltInRulesTest`
- `ScrubbingRulesLoaderTest`, `ExtensionRuleLoaderTest`, `ServiceLoaderSpanAttributeScrubbingRuleTest`
- `ValidatingSpanProcessorTest`, `ValidationPolicyRuntimeTest`
- `EnrichingSpanProcessorTest`, `EnrichingSpanProcessorAdvancedTest`
- `CategoryContractsTest` (api), `SpanEnricherTest` (core)

## Core-side characterization

**Deferred until PR-7/PR-8** create core policy packages.

Current behavior protected by otel-extension characterization tests. After extraction, copy/adapt matrices to core-side tests.

**PR-7A (2026-06-13):** Agent-side scrubbing runtime foundation (mirrors sampling PR-6A/6B pattern, scrubbing-adapted):

| Component | Role |
|-----------|------|
| `ScrubbingSnapshot` | Immutable compiled policy: `enabled`, `List<RuleExecutionWrapper>`, `version`, `updatedAt`, `source`; `fromRules()` validates + compiles at build time |
| `ScrubbingPolicyHolder` | `DomainConfigHolder` CAS + last-known-good; lock-free `current()` |
| `ScrubbingSpanProcessor` | Hot-path reads `policyHolder.current()` on `onEnding`; no regex compile / config parse on span path |

- Regex compiled at snapshot build (`ScrubbingSnapshot.compileWrappers` / rule constructors), not on span hot path.
- `enabled=false` → passthrough (values unchanged).
- Invalid runtime update → LKG preserved (`ScrubbingPolicyHolder.tryUpdate` / `updateScrubbingPolicy`).
- Circuit breaker / fail-closed behavior unchanged (PR-5B characterization preserved).
- **Not in PR-7A scope:** formal JMX atomic contract polish (PR-7B), Spring schema/reconciler (PR-7C).

New tests: `ScrubbingSnapshotTest`, `ScrubbingPolicyHolderTest`, `ScrubbingRuntimeFoundationTest`.

**PR-7B (2026-06-13):** Atomic agent-side scrubbing runtime update via JMX (mirrors PR-6D):

| Component | Role |
|-----------|------|
| `ScrubbingPolicyUpdate` | Validates merged domain; delegates rule-name selection to `ScrubbingRuleResolution` (PR-9D); `buildNext` compile before CAS |
| `ScrubbingPolicyHolder.tryApplyPolicyUpdate(...)` | validate → compile → publish full snapshot |
| `PlatformTracingControl.updateScrubbingPolicy(enabled, ruleNames[], source)` | One JMX call per scrubbing domain; invalid → LKG, version unchanged, `InvalidConfigCount`++ |

- `ruleNames` null → toggle `enabled` only (reuse compiled wrappers + circuit breakers).
- Unknown built-in names skipped (startup parity).
- Null rule name → `IllegalArgumentException` (JMX pre-check).
- Rule count bounded (`MAX_RULES=200`); syntax-invalid regex rejected at compile (LKG).
- Catastrophic ReDoS not solved here; per-rule `RuleCircuitBreaker` + rate-limited logging remain active.
- **Not in PR-7B:** Spring reconciler (PR-7C).

New tests: `ScrubbingPolicyRuntimeUpdateJmxTest`.

**PR-9D (2026-06-13):** Scrubbing extraction preparation — pure rule-name resolution split:

| Component | Role |
|-----------|------|
| `ScrubbingRuleResolution` | Pure validate/select: null reject, unknown skip, order/duplicates preserved, `MAX_RULES=200` |
| `ScrubbingRuleResolutionResult` | Resolved canonical config names + skipped unknown names |
| `BuiltInSpanAttributeScrubbingRules.lookup()` | Enum lookup without rule instantiation |
| `ScrubbingPolicyUpdate` | Delegates domain validation/selection; agent-side compile unchanged |

- **Stays agent-side:** `ScrubbingSnapshot`, `RuleExecutionWrapper`, `ScrubbingPolicyHolder`, `ScrubbingSpanProcessor`.
- **Future core move:** `ScrubbingRuleResolution` + JDK-only built-in name registry; rule impl classes remain in extension.
- No scrubbing behavior changes; hot path unchanged.

New tests: `ScrubbingRuleResolutionTest`, `ScrubbingPolicyUpdateTest`; extended `ScrubbingPolicyHolderTest` (null `ruleNames` toggle).

**PR-7C (2026-06-13):** Spring-side scrubbing runtime config schema v1 + reconciler (mirrors PR-6E):

| Component | Role |
|-----------|------|
| `ScrubbingRuntimeConfig` | View: `enabled`, `builtInRules` → `ruleNames[]`; source `spring-runtime-config` |
| `ScrubbingRuleNamesWire` | List → `String[]`, preserves order; unknown names not filtered (Option A) |
| `RuntimeConfigApplier.applyScrubbing` | → `SamplingControlClient.updateScrubbingPolicy(ScrubbingRuntimeConfig)` → one JMX call |

- Properties: `platform.tracing.scrubbing.enabled`, `platform.tracing.scrubbing.built-in-rules`.
- `rulesConfig` / SPI rules — startup-only, not in runtime JMX domain.
- Refresh: `RefreshScopeRefreshedEvent` listener unchanged; `RuntimeConfigApplier` not `@RefreshScope`.
- Invalid agent update: LKG, version unchanged, `InvalidConfigCount++`.

New tests: `ScrubbingRuntimeConfigTest`, extended `RuntimeConfigApplierTest`, `TracingPropertiesBindingTest`.

**PR-8A (2026-06-11):** Agent-side validation runtime foundation (mirrors PR-7A scrubbing / PR-6A sampling pattern):

| Component | Role |
|-----------|------|
| `ValidationSnapshot` | `core.validation` | Immutable policy: `enabled`, `strict`, `version`, `updatedAt`, `source`; `fromPolicy()` normalizes source (PR-9C) |
| `ValidationPolicyUpdate` | `core.validation` | Side-effect-free `buildNext` for CAS publish (PR-8B-ready; PR-9C) |
| `ValidationPolicyHolder` | otel-extension | Agent-side CAS holder (stays with `ValidatingSpanProcessor`) |
| `ValidationPolicyHolder` | `DomainConfigHolder` CAS + last-known-good; lock-free `current()` |
| `ValidatingSpanProcessor` | Hot-path reads `policyHolder.current()` on `onEnding`; no Spring/JMX on span path |

- `enabled=false` → passthrough (no `platform.validation.missing`).
- `enabled=true, strict=false` → annotate missing attrs + rate-limited WARN; span still exported.
- `enabled=true, strict=true` → `TracingValidationException` on missing required attrs.
- Validation degradation (annotation/warn/exception) is **not** dropped-span loss — no `VALIDATION_REJECTED` in taxonomy.
- Invalid runtime update → LKG preserved (`ValidationPolicyHolder.tryUpdate` / `tryApplyPolicyUpdate`).
- **Not in PR-8A scope:** formal JMX atomic contract polish with `source` arg (PR-8B), Spring schema/reconciler (PR-8C).

New tests: `ValidationSnapshotTest` and `ValidationPolicyUpdateTest` (core); `ValidationPolicyHolderTest`, `ValidationRuntimeFoundationTest` (extension).

**PR-8B (2026-06-11):** Atomic agent-side validation runtime update via JMX (mirrors PR-7B):

| Component | Role |
|-----------|------|
| `PlatformTracingControl.updateValidationPolicy(enabled, strict, source)` | One JMX call per validation domain; CAS publish via `ValidatingSpanProcessor.tryApplyPolicyUpdate` |
| `getValidationConfigVersion()` / `getValidationConfigLastUpdatedSource()` | Diagnostics mirroring sampling/scrubbing |
| 2-arg `updateValidationPolicy(enabled, strict)` | Delegates to 3-arg with `source="JMX"` |

- Blank/null `source` normalizes to `"JMX"`.
- Successful update increments version and records source; failed CAS/build keeps LKG (increments `InvalidConfigCount`).
- `enabled=false` / lenient / strict runtime modes unchanged from PR-8A characterization.
- Validation degradation is **not** dropped-span loss — no `VALIDATION_REJECTED`.
- **Not in PR-8B:** Spring reconciler (PR-8C).

New tests: `ValidationPolicyRuntimeUpdateJmxTest`.

**PR-8C (2026-06-11):** Spring-side validation runtime config schema v1 + reconciler (mirrors PR-7C):

| Component | Role |
|-----------|------|
| `ValidationRuntimeConfig` | View: `enabled`, `strict`; source `spring-runtime-config` |
| `RuntimeConfigApplier.applyValidation` | → `SamplingControlClient.updateValidationPolicy(ValidationRuntimeConfig)` → one JMX call |
| `TracingProperties.Validation` | Runtime-mutable schema v1: `platform.tracing.validation.enabled`, `platform.tracing.validation.strict` |

- Refresh: `RefreshScopeRefreshedEvent` listener unchanged; `RuntimeConfigApplier` not `@RefreshScope`.
- Spring is input/reconciliation layer only; agent `ValidationPolicyHolder` remains source of truth.
- `enabled=false` / lenient / strict modes unchanged; validation degradation is not dropped-span loss.
- No sampling/scrubbing reconciler changes.

New tests: `ValidationRuntimeConfigTest`, extended `RuntimeConfigApplierTest`, `TracingPropertiesBindingTest`.

**PR-8D (2026-06-11):** Runtime policy read-model parity (`GET /actuator/tracing`):

| Domain | Configured (Spring) | Live (agent via JMX) | Diagnostics |
|--------|---------------------|----------------------|-------------|
| Sampling | `enabled`, `ratio`, `dropPaths`, `routeRatios`, … | `liveRatio`, `liveSamplerEnabled`, `liveDropPaths`, `liveRouteRatios`, … | `configVersion`, `configSource` |
| Scrubbing | `enabled`, `builtInRules` | `liveEnabled`, `liveRuleCount` | `configVersion`, `configSource` |
| Validation | `enabled`, **`strict`**, **`strictRuntimeAllowed`** | `liveEnabled`, **`liveStrict`**, **`liveStrictRuntimeAllowed`** | `configVersion`, `configSource` |

- Read model reflects **active agent-side state** where MBean is available; Spring properties show configured/reconciliation input only.
- Agent-side holders remain source of truth; no write-path or hot-path changes.
- Validation degradation is not dropped-span loss.

New tests: extended `TracingActuatorEndpointTest`.

**PR-9A (2026-06-13):** Runtime policy architecture consolidation (docs + boundaries, no behavior change):

- Unified architecture doc: [runtime-policy-control-architecture.md](../tracing/runtime-policy-control-architecture.md)
- `SamplingControlClient` javadoc: historical name, deferred rename to `PlatformTracingControlClient`
- `runtime-sampling-control.md`: filename note — covers sampling/scrubbing/validation
- Per-domain `configSource` / `configVersion` authoritative; `config.lastUpdatedSource` legacy (sampling only)
- Module boundaries documented; no class moves in PR-9A

**PR-9F (2026-06-13):** Validation strict-mode runtime safety guard (closes W-001 / Opus B1):

| Component | Role |
|-----------|------|
| `platform.tracing.validation.strict-runtime-allowed` | Startup-only agent flag (default `false`); when `false`, runtime updates with `strict=true` are rejected |
| `ValidationPolicyHolder.tryApplyPolicyUpdate` | Rejects `strict=true` before CAS when guard disabled; LKG retained |
| `PlatformTracingControl.updateValidationPolicy` | `InvalidConfigCount++` on rejection; version/source unchanged |
| `TracingProperties.Validation.strictRuntimeAllowed` | Spring binding for startup/agent config parity (not runtime-mutable via reconciler) |

- **Production recommendation:** `validation.strict=false`, `validation.strict-runtime-allowed=false`.
- **CI/test/pre-prod:** set `strict-runtime-allowed=true` only when runtime strict toggling is explicitly intended.
- Startup `validation.strict=true` unchanged (diagnostic at process start).
- Runtime strict behavior unchanged when guard allows (`strictRuntimeAllowed=true`).
- No `VALIDATION_REJECTED` in dropped-span taxonomy.

New tests: `ValidationStrictRuntimeGuardTest`; updated `ValidationPolicyRuntimeUpdateJmxTest`, `ValidationPolicyHolderTest`.

**PR-9J (2026-06-14):** Actuator mutation guard + startup strict ops policy (W-007 partial, W-013 ops policy):

| Component | Role |
|-----------|------|
| `platform.tracing.actuator.mutation-enabled` | Spring flag (default `false`); gates Actuator `POST` write only |
| `TracingActuatorEndpoint.assertMutationAllowed()` | HTTP 403 when mutation disabled; no JMX call |
| `GET /actuator/tracing` `actuator.mutationEnabled` | Read-model visibility of guard state |
| `ValidatingSpanProcessor` constructor | One-time WARN when startup `strict=true` |
| JMX `PlatformTracingControl` | Direct control surface — deployment/JVM security required |

- **Production recommendation:** `validation.strict=false`, `validation.strict-runtime-allowed=false`, `actuator.mutation-enabled=false`.
- **Startup strict:** `validation.strict=true` remains valid for CI/test/pre-prod diagnostics; emits startup WARN; can throw from `Span.end()` on business thread — separate from PR-9F runtime guard.
- **`strictRuntimeAllowed` drift:** Spring `TracingProperties.Validation.strictRuntimeAllowed` is configured view; `liveStrictRuntimeAllowed` on read model is agent enforcement. Agent reads startup config from `ExtensionConfig` / `ConfigProperties` (`ExtensionPropertyNames` keys); values can differ if Spring is not agent startup source.
- Actuator mutation guard does **not** protect direct JMX.

New tests: `ValidationStartupStrictPolicyTest`; extended `TracingActuatorEndpointTest`, `TracingPropertiesBindingTest`.

## What must happen in PR-7

1. Move scrubbing engine / rules / merge / circuit breaker value objects to `platform-tracing-core` without changing characterized decisions.
2. Keep `ScrubbingSpanProcessor` as OTel adapter.
3. Re-run all `*Characterization*Test` and existing scrubbing tests green.
4. Require full JMH baseline captured before merge.

## What must happen in PR-8

1. Move validation/enrichment policy to core; keep processors as adapters.
2. Re-run validation/enrichment characterization + existing tests.
3. Require full JMH baseline captured before merge.

## Gates and readiness

```text
PR-5B does not move scrubbing production code.
PR-5B does not move validation production code.
PR-5B does not move enrichment production code.
PR-5B does not extract policy to core.
PR-5B does not change runtime behavior.
PR-5B is allowed before full JMH only because it is test-only.
PR-7 and PR-8 remain blocked until full JMH baseline is captured and saved.
```

PR-6 (sampling extraction) also remains blocked until full JMH baseline.

Official JMH commands (unchanged):

```powershell
./gradlew :platform-tracing-bench:jmh -PjmhHeap=4g
./gradlew :platform-tracing-bench:jmhSaveBaseline
```

See also: [platform-tracing-sampling-characterization.md](platform-tracing-sampling-characterization.md), [perf-baseline-fix-report.md](perf-baseline-fix-report.md).

## Related migration plan

PR-5B corresponds to the scrubbing/validation slice of PR-5 in [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md) (split PR-5A / PR-5B).
