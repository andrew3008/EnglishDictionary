# PlatformTracing v3 — Observability and Diagnostics

How platform manual tracing coexists with Micrometer Observation, how metering works internally, and how to inspect runtime state.

## Three intentional telemetry paths

| Path | Owner | Typical spans |
|------|-------|---------------|
| OpenTelemetry Java Agent | Agent bytecode instrumentation | HTTP, JDBC, gRPC, Kafka (production default) |
| Spring / Micrometer Observation | Framework conventions, `@Observed` | HTTP server/client observations |
| PlatformTracing `manual()` | `TracingImplementation` SPI | Governed manual and semantic transport spans |

PlatformTracing does **not** replace Agent or Observation auto-instrumentation. Call `manual()` only for gaps. See [ADR — Micrometer Observation Boundary](../decisions/ADR-platform-tracing-micrometer-observation-boundary.md) (Option C hybrid model, **Accepted**).

## Metering boundary (R01 fix)

- **`MeteredTracingImplementation`** decorates **`TracingImplementation`** at the SPI boundary.
- There is **no public-facade decorator** (`MeteredPlatformTracing` was removed in Slice 1B).
- Metering records platform self-metrics around span creation; it does **not** create spans directly.
- R01 (partial facade decorator losing ROOT/DETACHED/links) is **structurally addressed** — see [R01.md](../known-issues/R01.md).

Proof tests: `BeanTopologyTest`, `MeteredTopologyMatrixTest`, `MeteredSpanHandleDoubleCountTest`.

## Micrometer Observation coexistence

When Micrometer Observation is on the classpath:

- Framework HTTP observations remain owned by Spring/Micrometer.
- Platform customizes conventions (`Platform*ObservationConvention`).
- Opt-in `platform.tracing.suppression.suppress-micrometer-tracing` prevents duplicate HTTP spans when the Agent is present ([ADR-suppress-micrometer-tracing](../decisions/ADR-suppress-micrometer-tracing.md)).
- **`ObservationCoexistenceTest`** proves one HTTP request / application operation does not produce two unsynchronized root spans.
- Manual `manual().operation(...)` inside an auto-observed request may create a **child** span sharing the active trace id.
- Intentional `manual().root()` creates a separate trace when explicitly requested.

**Do not** combine `@Traced` and `@Observed` on the same method (ArchUnit rule `NO_TRACED_AND_OBSERVED_ON_SAME_METHOD`).

## Diagnostics

The Actuator tracing endpoint exposes a **stable DTO** (`TracingDiagnosticsView`), not raw internal `TracingState` or OpenTelemetry SDK types.

Typical fields include:

- Tracing mode (enabled / disabled / unavailable) and reason
- Implementation class name
- SDK mode / agent detection flags
- Metered decoration active indicator

Proof: `DiagnosticsBoundaryTest`, `TracingDiagnosticsViewJsonContractTest`.

## Bridge-otel dev path

`io.micrometer.tracing` bridge-otel is an **optional dev/staging** path (see `MicrometerTracingMdcBridgeSmokeTest`). It is **not** the platform manual tracing engine and does not replace `TracingImplementation`.

## Agent / container e2e gate

Full agent and Testcontainers e2e validation is gated by **`-PrunE2e`**. It is a **production sign-off gate**, not part of the default `.\gradlew.bat build`.

Do not mark production sign-off complete unless `-PrunE2e` was actually run in a Docker environment.

## Related documents

- [Getting started](./platform-tracing-v3-getting-started.md)
- [Production readiness](./platform-tracing-v3-production-readiness.md)
- [ADR — Metering SPI Boundary](../decisions/ADR-platform-tracing-metering-spi-boundary.md)
- [anti-double-instrumentation.md](./anti-double-instrumentation.md)
