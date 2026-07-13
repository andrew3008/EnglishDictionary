# TraceOperations v3 — Production Readiness

Checklist for production sign-off after the v3 refactoring (Slices 0A–7, remediation B01–B10, post-Perplexity hardening). **Do not mark unchecked items as done** unless verified in your environment.

## Production readiness checklist

- [x] `.\gradlew.bat build` GREEN
- [x] targeted TraceOperations tests GREEN
- [x] grep gates clean (no v1 API in active docs/code paths)
- [ ] `-PrunE2e` run in Docker/Testcontainers environment
- [ ] Kafka batch listener container e2e passed
- [ ] dashboard/alert owners notified about Kafka span semantic drift
- [ ] suppress-micrometer-tracing fleet default decided
- [ ] temporary workaround references audited (`SpanSpecReason.TEMPORARY_WORKAROUND`)

## Build verification

```powershell
.\gradlew.bat build
.\gradlew.bat :platform-tracing-samples:compileJava
```

## Targeted test suites (reference)

| Suite | Module | Purpose |
|-------|--------|---------|
| `TracingImplementationRoutingTest` | core | Single creation boundary |
| `BeanTopologyTest` | autoconfigure | One SPI chain, metered decoration |
| `MeteredTopologyMatrixTest` | autoconfigure | ROOT/DETACHED/links + metering |
| `ObservationCoexistenceTest` | autoconfigure | No duplicate unsynchronized roots |
| `DiagnosticsBoundaryTest` | autoconfigure | Stable diagnostics DTO |
| `KafkaBatchAspectMigrationTest` | autoconfigure | Aspect uses v3 batch API |
| `KafkaBatchSpanBuilderIntegrationTest` | core | Exported batch span semantics |

## E2E sign-off (`-PrunE2e`)

E2e tests require Docker. Enable explicitly:

```powershell
.\gradlew.bat build -PrunE2e
```

Includes agent/container scenarios such as `DuplicateHttpSpanAgentSmokeTest`. **Not wired into default CI/build** by design.

## Operational decisions before fleet rollout

### Kafka batch semantic drift

v3 batch spans use `KAFKA_CONSUMER` kind, `<destination> process` naming, and messaging semconv attributes. Update dashboards, alerts, and SLO queries. See [Kafka batch links](./platform-tracing-v3-kafka-batch-links.md).

### suppress-micrometer-tracing

Decide fleet default for `platform.tracing.suppression.suppress-micrometer-tracing` when OTel Agent instruments HTTP. See [ADR-suppress-micrometer-tracing](../decisions/ADR-suppress-micrometer-tracing.md).

### TEMPORARY_WORKAROUND audit

Search for `SpanSpecReason.TEMPORARY_WORKAROUND` usages and verify each has a tracked `reference` with an expiry owner.

## Scope confirmation (v3 refactoring)

- Public API: `traceContext()` + `manual()` only
- No v1 API restored
- No compatibility shim
- No `MeteredPlatformTracing` public decorator
- Single creation boundary: `TracingImplementation.startSpan(SpanSpec)`
- Kafka aspect boundary closed (B03)

## Related documents

- [Getting started](./platform-tracing-v3-getting-started.md)
- [Observability and diagnostics](./platform-tracing-v3-observability-and-diagnostics.md)
- [platform-tracing-refactoring-plan.md](../analysis/platform-tracing-refactoring-plan.md)
