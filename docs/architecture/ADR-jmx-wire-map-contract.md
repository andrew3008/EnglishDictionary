# ADR: JMX validated Map wire contract (PR-3 spike)

## Status

**Superseded (production purge) — wire schema retained, JMX spike transport removed from production**

> JMX wire spike transport has been removed from production code.
> Any retained JMX wire harness exists only as test-only OTel extension infrastructure.
> Production no longer supports platform.tracing.spike.jmx.wire.
> TracingControlProtocolValidator / protocol schema remains as stable validation contract.

Map-based wire is validated as the **primary candidate** for future private in-process JMX control migration. Production runtime **has not** switched to Map wire. The original PR-3 spike transport (`jmx.spike` package, `TracingControlWireSpike*`, runtime gate `platform.tracing.spike.jmx.wire`, ObjectName `type=WireSpike`, marker `SPIKE_JMX_WIRE:`, bootstrap call from `PlatformAutoConfigurationCustomizer`) was **purged from production `src/main`** (NEW_HYBRID). The cross-classloader round-trip evidence is now produced by a test-only OTel extension JAR under `platform-tracing-e2e-tests/src/jmxWireExtension` (`WireRoundTripTest*`), loaded via `-Dotel.javaagent.extensions`.

## Context

Platform Tracing crosses two classloaders:

| Side | Classloader | Examples |
|------|-------------|----------|
| Application | App CL | Spring starter, `SamplingControlClient`, `RuntimeConfigApplier` |
| Agent extension | Agent / Extension CL | `PlatformTracingControl`, samplers, span processors |

`platform-tracing-api` is visible to both sides and is the correct home for CL-neutral contracts.

PR-2 introduced wire schema v1 (now `api.control.protocol`):

- `TracingControlProtocolKeys`, `TracingControlProtocolSchema`, `TracingControlProtocolValidator`
- Strict unknown-key rejection
- Topology vs runtime policy classification
- OpenMBean-compatible types only

PR-3 must prove (or disprove) that `Map<String, Object>` payloads can cross the in-process JMX boundary without `ClassCastException`, `ClassNotFoundException`, or raw DTO leakage.

Raw Java DTOs across the boundary are **forbidden** — each side may load different class objects for the same FQCN.

## Decision

1. **Primary wire candidate:** validated `Map<String, Object>` using PR-2 v1 schema and `TracingControlProtocolValidator` via `TracingControlProtocol.current().validator()`.
2. **Production path unchanged:** existing primitive JMX operations (`setSamplingRatio(double)`, `updateSamplingPolicy(...)`, etc.) remain the only production control plane.
3. **No production spike MBean (purged):** the former `TracingControlWireSpikeMBean` (`type=WireSpike`) and runtime gate `platform.tracing.spike.jmx.wire` were removed from production. The round-trip MBean now exists only as a test-only harness `WireRoundTripTestMBean` (`type=WireRoundTripTest`) in `platform-tracing-e2e-tests/src/jmxWireExtension`, registered by a test-only `AutoConfigurationCustomizerProvider` when the test extension JAR is loaded.
4. **JMX remains private in-process adapter** — not a public remote control plane, not part of public API.
5. **Validation-only harness** — the test MBean evaluates payloads; it does not mutate sampler/scrubbing/export runtime state.

## Evidence (PR-3)

### Tests added

| Test | Module | Scope |
|------|--------|-------|
| `TracingControlProtocolValidatorTest` | platform-tracing-api | Full v1 schema scenario coverage (runs in normal CI) |
| `WireRoundTripInProcessTest` | e2e-tests (test-only harness) | In-process JMX invoke; all rejection paths |
| `SamplingControlClientWireContractTest` | spring-boot-autoconfigure | App-side invoke pattern with a self-contained test stub |
| `MapWireRoundTripE2ETest` | e2e-tests | Agent JVM + App CL main; cross-CL via test-only `jmxWireExtension` JAR |

### Wire round-trip results

| Scenario | Result |
|----------|--------|
| Valid Map (`contractVersion=1`, `sampling.ratio=0.5`, `diagnostics.requestId`) | **Accepted** (`valid=true`) through JMX invoke |
| Invalid type (`sampling.ratio="0.5"`) | **Rejected** (`valid=false`), no `ClassCastException` |
| Unknown key (`unknown.key=true`) | **Rejected** (strict v1) |
| Topology field (`exporter.endpoint=...`) | **Rejected** for runtime apply |
| Raw App CL DTO in Map | **Rejected**, no successful DTO crossing |
| Unsupported `contractVersion` | **Rejected** |
| Existing primitive `setSamplingRatio(double)` | **Unchanged** — existing `PlatformTracingControlTest` |

### Commands

```powershell
./gradlew :platform-tracing-api:test
./gradlew :platform-tracing-otel-extension:test --tests "*Wire*Test" --tests "*PlatformTracingControl*Test"
./gradlew :platform-tracing-spring-boot-autoconfigure:test --tests "*Wire*Test"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*MapWireRoundTripE2ETest*"
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*RuntimeSamplingControlSmokeTest*"
./gradlew pr1ModuleTaxonomyVerify
./gradlew pr0BaselineLock
```

## Alternatives considered

| Alternative | Verdict |
|-------------|---------|
| **CompositeData / TabularData** | Stronger OpenMBean typing; follow-up if Map proves too loose in production migration |
| **Primitive-only operations (status quo)** | Works today but does not scale to reconciler/policy bundles |
| **JSON String payload** | CL-safe but defers typing to ad-hoc parsing; weaker schema enforcement |
| **Raw Java DTO** | Rejected — ClassLoader unsafe |

## Consequences

- **PR-10** (`TracingConfigReconciler`) can build validated Map payloads from `TracingProperties` policy fields.
- **PR-11** can keep Actuator READ/MUTATION separation; validation runs before JMX invoke.
- **Runtime migration** requires a dedicated later PR (feature-flagged parallel path, then cutover).
- **Existing primitive JMX operations** remain until safe replacement is proven under load and E2E.
- Spike MBean and property gate can be removed or retained for regression tests until production wire lands.

## Explicit non-goals

```text
This ADR does not approve production-wide runtime migration.
This ADR does not remove existing primitive JMX operations.
This ADR does not expose JMX as public API.
This ADR does not implement TracingConfigReconciler.
```

## Related documents

- [platform-tracing-wire-schema-v1.md](platform-tracing-wire-schema-v1.md)
- [platform-tracing-preservation-first-migration-plan.md](platform-tracing-preservation-first-migration-plan.md)
- [ADR-classloader-visibility-spike-finding.md](../decisions/ADR-classloader-visibility-spike-finding.md)
