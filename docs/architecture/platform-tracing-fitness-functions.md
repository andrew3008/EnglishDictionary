# Platform Tracing: Architectural Fitness Functions

| Поле | Значение |
|------|----------|
| Версия | 1.0 |
| Дата | 2026-06-11 |
| Статус | **Proposed** — CI gates to implement via PR-3+ |
| ADR | [ADR-platform-tracing-clean-core-hybrid](../decisions/ADR-platform-tracing-clean-core-hybrid.md) |
| Target architecture | [platform-tracing-target-architecture.md](./platform-tracing-target-architecture.md) |

---

## 1. Назначение

Fitness functions — **автоматизируемые архитектурные инварианты** Clean Core Hybrid. Нарушение fitness function = CI failure.

Документ определяет правила, тесты и метрики. Implementation — PR-3 (ArchUnit) и последующие PRs.

**Committee note:** fitness functions блокируют merge/release gates; ADR architecture vote не требует их полной реализации заранее.

---

## 2. Fitness function catalog

| ID | Name | Type | Module | PR |
|----|------|------|--------|-----|
| FF-01 | ExtensionNoSpring | ArchUnit | otel-extension | PR-3 |
| FF-02 | AutoconfigureNoExtensionMain | ArchUnit | autoconfigure | PR-3 |
| FF-03 | CoreNoOtelNoSpring | ArchUnit | core | PR-3/4 |
| FF-04 | ApiPureContracts | ArchUnit | api | PR-3 |
| FF-05 | DependencyDirection | ArchUnit | all | PR-3 |
| FF-06 | WireSchemaValidation | Unit + contract | api, e2e | PR-2 |
| FF-07 | PolicyLkgInvariant | Unit + integration | core, extension | PR-2 |
| FF-08 | ScrubbingMandatory | Config + contract | extension, e2e | PR-6 |
| FF-09 | TelemetryBaselineParity | Contract | e2e | PR-6 |
| FF-10 | JmhSamplerRegression | JMH | extension/core | PR-6 |
| FF-11 | JmhScrubbingRegression | JMH | core | PR-6 |
| FF-12 | DriftMetricEmitted | Integration | autoconfigure | PR-7 |
| FF-13 | MutationRequiresAuth | Integration | autoconfigure | PR-8 |
| FF-14 | AgentAbsentMutation503 | Integration | autoconfigure | PR-7 |
| FF-15 | ForbiddenAttributesAbsent | Contract | e2e | PR-5 |
| FF-16 | OtelAgentCompatibility | Matrix | e2e | PR-B |
| FF-17 | CoreScopeFreeze | Review gate | core | manual |
| FF-18 | MutationDisabledInProd | Integration | autoconfigure | PR-8 |
| FF-19 | ConfigSourcePrecedence | Unit + integration | autoconfigure | PR-7A |
| FF-20 | TopologyChangeRejectedFromConfigServer | Integration | autoconfigure | PR-7A |
| FF-21 | DesiredActualDriftDetected | Integration | autoconfigure | PR-7A |
| FF-22 | ConfigServerRefreshUsesWireValidation | Unit + contract | api, autoconfigure | PR-7A |
| FF-23 | EmergencyOverrideRequiresWaiver | Integration + review | autoconfigure | PR-8 |

---

## 3. ArchUnit fitness functions

### FF-01: ExtensionNoSpring

```text
Rule: classes in ..otel.extension.. must not depend on org.springframework..
Status: EXISTS (ExtensionNoSpringDependencyArchTest) — extend to full org.springframework.*
Enforcement: otel-extension/src/test/.../arch/
Severity: ERROR — merge blocker
```

### FF-02: AutoconfigureNoExtensionMain

```text
Rule: ..autoconfigure.. must not depend on ..otel.extension.. (main sources)
Exception: testImplementation only
Enforcement: autoconfigure/src/test/.../arch/
Severity: ERROR
```

### FF-03: CoreNoOtelNoSpring

```text
Rule: ..core.. must not depend on:
  - io.opentelemetry..
  - org.springframework..
  - ..otel.extension..
  - ..autoconfigure..
Enforcement: core/src/test/.../arch/CorePurityArchTest
Severity: ERROR
Introduced: PR-4

Classloader note: core may load in both CLs as same bytecode, different Class
objects. No cross-CL object identity, mutable static shared state, or direct
object passing across CL boundary.
```

### FF-04: ApiPureContracts

```text
Rule: ..api.. must not depend on:
  - io.opentelemetry..
  - org.springframework..
  - ..core.. (api is depended upon by core, not reverse)
Enforcement: api/src/test/.../arch/ApiPurityArchTest
Severity: ERROR
```

### FF-05: DependencyDirection

```text
Allowed edges:
  otel-extension → core, api
  autoconfigure  → core, api
  core           → api
  api            → (JDK)

Forbidden:
  core → otel-extension
  core → autoconfigure
  api  → core
  autoconfigure → otel-extension (main)
Enforcement: aggregated ArchUnit in platform-tracing-build or per-module
Severity: ERROR
```

---

## 4. Wire contract fitness functions

### FF-06: WireSchemaValidation

| Test | Input | Expected |
|------|-------|----------|
| Valid sampling policy Map | primitives + contractVersion | accept |
| Java object value in Map | custom class | reject |
| Nested Map value | `{key: {nested: 1}}` | reject (v1 flat schema) |
| Topology field | `exporter.endpoint` | reject |
| Unknown key (strict) | `unknownField` | reject + metric |
| Type coercion trap | `ratio: "0.1"` (String) | reject or explicit parse policy |
| Missing contractVersion | valid fields only | reject |

**Location:** `platform-tracing-api/src/test/.../control/`, `e2e-tests/contract/`

**Wire format policy:** Map acceptable only with validated JDK/open types. CompositeData documented fallback if Map too loose (E2).

---

## 5. Runtime policy fitness functions

### FF-07: PolicyLkgInvariant

| Scenario | Expected |
|----------|----------|
| Valid policy update | new version published |
| Invalid policy update | LKG preserved; failure metric |
| Concurrent updates | CAS winner; no corrupted state |
| Kill switch enabled | all traces dropped |
| Side-effect-free builder | retried N times on CAS contention — no double metrics |

**Classes:** `DomainConfigHolder`, `SamplerStateHolder`, `PlatformTracingControl`

---

## 6. Pipeline fitness functions

### FF-08: ScrubbingMandatory

```text
Invariant: scrubbing processor MUST be registered in baseline pipeline
           for all profiles (prod, dev, staging).

Test approaches:
  1. Integration: span with PII-like attribute → scrubbed in export
  2. Config assertion: prod profile processor list includes scrubbing
  3. Negative: attempt to disable scrubbing via policy → reject (LKG); scrubbing never optional tier

Fail mode: FAIL-CLOSED (span dropped if scrubbing fails)
```

**Explicitly NOT optional tier** — contradicting this fitness function is architecture violation.

### FF-09: TelemetryBaselineParity

```text
Invariant: mandatory attributes per CategoryContracts present in:
  - prod profile (validation OFF)
  - dev profile (validation ON)

Forbidden: prod profile missing attrs that dashboards depend on.

Test: parameterized contract test per SpanCategory
Location: e2e-tests/contract/TelemetryBaselineParityTest
```

---

## 7. Performance fitness functions

### FF-10: JmhSamplerRegression

| Benchmark | Threshold | CI |
|-----------|-----------|-----|
| `CompositeSamplerPolicyBranchesBenchmark` | no regression >5% vs baseline | optional gate → warn |
| `CompositeSamplerConcurrentUpdateBenchmark` | no regression >5% | optional gate → warn |

**Note:** JMH in CI may be nightly; PR-6 requires manual baseline update with justification.

**Wording for committee:** thresholds are **local engineering policy**, not OTel standard.

### FF-11: JmhScrubbingRegression

| Benchmark | Scope |
|-----------|-------|
| Scrubbing rule set N=10, N=50 | linear bound verification |
| ReDoS pathological regex | circuit breaker triggers |

---

## 8. Control plane fitness functions

### FF-12: DriftMetricEmitted

```text
Given: TracingDesiredState (desired) != agent applied state (actual)
When:  TracingConfigReconciler runs
Then:  metric platform.tracing.config.drift.detected increments
       AND Actuator READ exposes TracingConfigDriftStatus
       AND log at WARN minimum
```

### FF-13: MutationRequiresAuth

```text
Applies only when Actuator MUTATION enabled (non-prod or waiver prod).

Given: mutation endpoint exposed under dev/test/staging/debug profile
When:  unauthenticated POST policy update
Then:  HTTP 401/403

When:  authenticated without write role
Then:  HTTP 403

When:  authenticated with write role
Then:  HTTP 200 + audit log entry (source=actuator-debug)
```

### FF-14: AgentAbsentMutation503

```text
Given: SdkMode indicates agent/extension unavailable
When:  POST mutation endpoint (mutation enabled profile only)
Then:  HTTP 503

When:  GET Actuator READ endpoint
Then:  HTTP 200 with degraded apply status (not 503)

When:  Config Server refresh while agent absent
Then:  desired state preserved; apply status degraded; drift metric
```

---

## 9. Telemetry contract fitness functions

### FF-15: ForbiddenAttributesAbsent

| Category | Forbidden examples |
|----------|-------------------|
| PII | raw passwords, tokens, full credit card |
| High-cardinality | unbounded user-generated URLs in prod |

Test: export capture → assert absent keys.

### FF-16: OtelAgentCompatibility

```text
Matrix: pinned agent versions × smoke tests
Minimum: 2.28.x (current pin)
Expand: per PR-B Alibaba section
```

---

## 10. Desired-state and mutation guard fitness functions

### FF-18: MutationDisabledInProd

```text
Given: prod profile
When:  Actuator mutation endpoint is called
Then:  endpoint is not exposed OR returns 404/403 per project convention.

Given: dev/test/staging/debug profile AND mutation explicitly enabled
When:  authorized request is sent
Then:  mutation may be processed through validated wire path (SamplingControlClient).

Given: prod profile AND mutation enabled property WITHOUT explicit waiver
Then:  application fails startup OR mutation endpoint remains disabled (FF-18).
```

### FF-19: ConfigSourcePrecedence

```text
Precedence (highest wins for apply decision):
1. Temporary emergency override with TTL — only if explicitly enabled (waiver).
2. Config Server runtime policy desired state — normal production path.
3. Helm/env bootstrap defaults — initial desired state at startup.
4. Agent applied state — NEVER source of truth.
```

### FF-20: TopologyChangeRejectedFromConfigServer

```text
Given: Config Server refresh contains topology fields (exporter endpoint, BSP queue, etc.)
When:  TracingConfigReconciler processes refresh
Then:  topology fields are rejected; agent applied topology is not mutated; metric emitted.
```

### FF-21: DesiredActualDriftDetected

```text
Given: desired state != actual agent state
When:  reconciler runs
Then:  platform.tracing.config.drift.detected increments
       AND Actuator READ exposes drift status with desired/actual versions.
```

### FF-22: ConfigServerRefreshUsesWireValidation

```text
Given: Config Server provides invalid policy value (wrong type, unknown key, topology field)
When:  reconciler applies desired state
Then:  validation fails before JMX invoke OR JMX rejects payload; LKG preserved on agent.
```

### FF-23: EmergencyOverrideRequiresWaiver

```text
Given: production profile
When:  emergency override is requested
Then:  explicit waiver flag, audit log entry, RBAC and network restriction required (E4).
       Override must not silently override Config Server desired state without TTL/audit.
```

---

## 11. Core scope fitness function (manual review)

### FF-17: CoreScopeFreeze (review gate, not automated)

**Allowed in core:**

- Immutable policy snapshots
- Policy merge and validation logic
- Scrubbing rule compilation and matching
- Semconv validation rules (pure data)
- Circuit breaker state machines

**Forbidden in core:**

- OTel types (`Span`, `Attributes`, `Resource`, `Context`)
- Spring types
- HTTP/JMX/Actuator
- Plugin/SPI frameworks
- Generic rule engines unrelated to tracing policy

**Classloader:** no cross-CL object identity assumptions; no mutable static shared state for cross-boundary coordination.

**Review:** architecture review on every PR touching `platform-tracing-core` package list.

---

## 12. CI integration plan

| Stage | Fitness functions |
|-------|-------------------|
| PR compile | FF-01–05 (ArchUnit) |
| PR test | FF-06, FF-07, FF-19–FF-22 (unit) |
| PR integration | FF-08, FF-09, FF-14, FF-15, FF-18, FF-21 |
| PR-7A merge | E7 full suite; FF-19–FF-22 |
| PR-8 merge | FF-18, FF-13, FF-23 + E4 if waiver |
| Nightly | FF-10, FF-11, FF-16 |
| Pre-release | E6 macro perf; E7 if in scope |

---

## 13. Failure response

| Fitness function failure | Action |
|--------------------------|--------|
| ArchUnit (FF-01–05) | Block merge; fix dependency |
| Wire validation (FF-06, FF-22) | Block merge; fix schema or caller |
| LKG (FF-07) | Block merge — production safety |
| Scrubbing mandatory (FF-08) | **Architecture violation** — escalate |
| Baseline parity (FF-09) | Block PR-6 merge until contract fixed |
| JMH regression (FF-10/11) | Investigate; may waive with committee note |
| Mutation in prod (FF-18) | **Block release** — architecture violation |
| Config precedence (FF-19) | Block PR-7A merge |
| Topology from Config Server (FF-20) | Block PR-7A merge |
| Emergency waiver (FF-23) | Block prod override without E4 |

---

## 14. Mapping to required tests (ADR §Testing)

| ADR test category | Fitness functions |
|-------------------|-------------------|
| ArchUnit | FF-01–05, FF-17 |
| Telemetry contract | FF-09, FF-15 |
| Runtime policy | FF-07, FF-19–FF-22 |
| JMX/Actuator contract | FF-06, FF-12–14, FF-18 |
| Config reconciliation | FF-19–FF-23, E7 |
| Performance | FF-10, FF-11 + E6 |
| Compatibility | FF-16 |

---

## 15. Existing tests to preserve/enhance

| Existing test | Fitness function |
|---------------|------------------|
| `ExtensionNoSpringDependencyArchTest` | FF-01 |
| `DomainConfigHolderTest` | FF-07 |
| `CompositeSamplerRouteRatioTest` | FF-07 |
| `SamplerRuntimeUpdateConcurrencyTest` | FF-07 |
| `RuntimeSamplingControlSmokeTest` | FF-07, FF-14 (extend) |
| `CompositeSamplerPolicyBranchesBenchmark` | FF-10 |
| `CategoryContractsTest` | FF-09 (extend to e2e) |

---

*Fitness functions implement invariants from ADR Clean Core Hybrid. No production code changed in this document.*
