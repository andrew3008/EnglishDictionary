# Propagation package cleanup — evidence report

> Дата прогона: **2026-08-08**
> Baseline HEAD (до cleanup working tree): `d1abf6f65fb252cf6ef967f4e9a1ebd4740c8835`
> Working tree: uncommitted cleanup slices 1–5 (characterization → W3C fix → indirection → visibility → docs)
> Исторические narrative snapshots **не** переписывались.

## 1. Environment

| Поле | Значение |
|---|---|
| OS | Windows 10 (build 19045) |
| JDK | Oracle JDK **21.0.6** (build 21.0.6+8-LTS-188) |
| CPU | 13th Gen Intel Core i5-13500 (14 cores / 20 threads) |
| RAM | ~63.7 GiB |
| OTel SDK BOM | **1.62.0** (`openTelemetryBomVersion`) |
| OTel Java Agent | **2.28.1** |
| Docker host `tcp://192.168.100.70:2375` | **unreachable** (`TcpTestSucceeded=False`) на момент verify |

## 2. JMH before/after (decision-oriented matrix)

### 2.1 Command (полный профиль, не quick/dev)

```powershell
.\gradlew.bat :platform-tracing-bench:jmh `
  "-PjmhInclude=BaggagePropagationBenchmark" `
  "-PjmhResultFile=build\results\propagation-cleanup\before.json" `
  --no-daemon

.\gradlew.bat :platform-tracing-bench:jmh `
  "-PjmhInclude=BaggagePropagationBenchmark" `
  "-PjmhResultFile=build\results\propagation-cleanup\after.json" `
  --no-daemon
```

Профиль JMH (module defaults): **2 forks × 5 warmup × 5 measurement**, AverageTime ns/op, GC profiler.
Harness: `AutoConfiguredOpenTelemetrySdk` → final `TextMapPropagator` (G-PROP-6: нет compile-time
зависимости на package-private implementations).

`before.json` снят на **неизменённом** `FilteringBaggagePropagator` (ручная сериализация);
`after.json` — после production correction (filter → derived immutable `Baggage` → stock delegate).

### 2.2 Artifact storage (не в Git)

| Artifact | Local path (build output) | SHA-256 |
|---|---|---|
| before | `platform-tracing-bench/build/results/propagation-cleanup/before.json` | `883854832426A842FE2B53BC3B285633AD391A9E1A7DC576B65FF1EE19DAD45F` |
| after | `platform-tracing-bench/build/results/propagation-cleanup/after.json` | `E319CB72B52F9821433C816F70E81EC9FADFB5AB7E3A29A63C0AC1DA0FCF8AD8` |

Generated JSON **не коммитится**. Для CI/PR — прикладывать оба файла как build artifacts вместе с
этим evidence report.

### 2.3 Per-row results (`ns/op`, `gc.alloc.rate.norm` B/op)

| Benchmark | Scenario | before ns/op | after ns/op | Δ% | before alloc B/op | after alloc B/op |
|---|---|---:|---:|---:|---:|---:|
| extract | disabled | 1247.38 | 961.29 | −22.9 | 1936.0 | 1936.0 |
| extract | empty | 22.20 | 22.16 | −0.2 | 0.0 | 0.0 |
| extract | metadataAll | 2706.18 | 2075.91 | −23.3 | 4392.0 | 4392.0 |
| extract | mixedAllowDeny | 1113.60 | 1109.74 | −0.3 | 1816.0 | 1816.0 |
| extract | nearLimit | 14021.11 | 8863.65 | −36.8 | 27776.0 | 27776.0 |
| extract | oneAllowed | 266.37 | 265.26 | −0.4 | 592.0 | 592.0 |
| extract | realisticSmall | 1224.11 | 1073.33 | −12.3 | 1936.0 | 1936.0 |
| inject | disabled | 735.80 | 572.77 | −22.2 | 1188.0 | 1152.0 |
| inject | empty | 31.13 | 25.88 | −16.9 | 48.0 | 48.0 |
| inject | metadataAll | 2486.13 | 1915.08 | −23.0 | 8568.0 | 5360.0 |
| inject | mixedAllowDeny | 1518.70 | 954.74 | −37.1 | 4440.0 | 2504.0 |
| inject | nearLimit | 12876.81 | 15106.82 | **+17.3** | 46900.0 | **31208.0** |
| inject | oneAllowed | 440.26 | 422.63 | −4.0 | 1168.0 | 1136.0 |
| inject | realisticSmall | 1803.24 | 1460.87 | −19.0 | 3912.0 | 3060.0 |

**Интерпретация:** inject-путь в типичных сценариях быстрее и аллоцирует меньше (убран ручной
encoder). `inject/nearLimit` (+17.3% ns/op) — ожидаемый trade-off перестроения 64-entry
`Baggage` перед stock serialize; при этом alloc −33% (46900 → 31208 B/op). Extract allocation
стабилен (тот же stock decode).

## 3. Packaged outbound wire E2E (causal proof)

### 3.1 Command

```powershell
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.OutboundBaggageAgentE2ETest" `
  --no-daemon
```

Outcome **2026-08-08**: **PASS** (2/2, без skip). Docker/Collector не требуются (local JDK
`HttpServer` receiver; `otel.traces.exporter=none`).

### 3.2 Raw captured `baggage` headers

| Run | Variable | Raw header |
|---|---|---|
| positive (extension ON) | `otel.javaagent.extensions=<agent-extension.jar>` | `tenant_class=DF%2028;verified%3Dtrue` |
| negative (extension OFF) | `otel.javaagent.extensions` absent | `internal_secret=leak,tenant_class=DF%2028;verified%3Dtrue` |

Causal proof: positive = allowlist-filtered + stock `%20`; negative = unfiltered stock `%20` with
denied key present. Identical Spring/JVM config except extension path (single-variable constraint).

### 3.3 Exact child JVM system properties (captured 2026-08-08)

Positive (`OUTBOUND_BAGGAGE_EVIDENCE positive SMOKE_JVM_PROPERTIES=`):

```text
[otel.javaagent.extensions=E:/Platform_Traces/platform-tracing-otel-javaagent-extension/build/libs/platform-tracing-otel-javaagent-extension-0.1.0-SNAPSHOT-agent.jar,
 spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration,org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration,
 otel.service.name=outbound-baggage-agent-smoke,
 otel.traces.exporter=otlp,
 otel.exporter.otlp.endpoint=http://127.0.0.1:1,
 otel.exporter.otlp.traces.endpoint=http://127.0.0.1:1/v1/traces,
 otel.exporter.otlp.protocol=http/protobuf,
 platform.tracing.queue.overflow-policy=UPSTREAM,
 otel.metrics.exporter=none,
 otel.logs.exporter=none,
 otel.javaagent.logging=application,
 org.slf4j.simpleLogger.defaultLogLevel=info,
 otel.bsp.schedule.delay=200,
 otel.traces.exporter=none,
 otel.propagators=tracecontext,baggage,
 platform.tracing.javaagent.propagation.baggage.allowed-keys=tenant_class,
 spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration,org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration,
 smoke.baggage.receiver.url=http://127.0.0.1:52173/receive,
 platform.tracing.configuration.authority=HELM,
 platform.environment=LOCAL]
```

Negative (тот же набор **без** `otel.javaagent.extensions`; receiver port ephemeral `52188`):

```text
[otel.service.name=outbound-baggage-agent-smoke,
 otel.traces.exporter=otlp,
 otel.exporter.otlp.endpoint=http://127.0.0.1:1,
 otel.exporter.otlp.traces.endpoint=http://127.0.0.1:1/v1/traces,
 otel.exporter.otlp.protocol=http/protobuf,
 platform.tracing.queue.overflow-policy=UPSTREAM,
 otel.metrics.exporter=none,
 otel.logs.exporter=none,
 otel.javaagent.logging=application,
 org.slf4j.simpleLogger.defaultLogLevel=info,
 otel.bsp.schedule.delay=200,
 otel.traces.exporter=none,
 otel.propagators=tracecontext,baggage,
 platform.tracing.javaagent.propagation.baggage.allowed-keys=tenant_class,
 spring.autoconfigure.exclude=org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration,org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration,org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration,
 smoke.baggage.receiver.url=http://127.0.0.1:52188/receive,
 platform.tracing.configuration.authority=HELM,
 platform.environment=LOCAL]
```

Единственная причинной переменная между прогонами — наличие
`otel.javaagent.extensions`. Дублирование `spring.autoconfigure.exclude` (runner + explicit
extra) идентично в обоих прогонах. Platform Spring autoconfigurations дополнительно исключены
одинаково в `AgentSpringOutboundBaggageSmokeMain`.

### 3.4 Optional Agent DEBUG artifact

`-Dotel.javaagent.debug=true` **не** включался в PASS-прогоне. Extension SLF4J внутри
`ExtensionClassLoader` уходит в NOP (см. `BspDropOldestSafetyAgentSmokeTest`) — отсутствие
текста лога extension **не** FAIL. Primary evidence = behavioral positive/negative delta выше.

## 4. Companion SPI packaged E2E

```powershell
$env:DOCKER_HOST = "tcp://192.168.100.70:2375"
.\gradlew.bat :platform-tracing-e2e-tests:test -PrunE2e `
  --tests "space.br1440.platform.tracing.e2e.smoke.PlatformSpiAgentSmokeTest" `
  --no-daemon
```

Status **2026-08-08**: **BLOCKED** — Docker host unreachable. Не интерпретировать как PASS.
`OutboundBaggageAgentE2ETest` остаётся независимым PASS (не требует Collector).

## 5. Live docs / protocol updated

| Document | Change |
|---|---|
| `docs/decisions/ADR-named-spi-sampler-propagator.md` | builder → direct provider construction; OTel BOM upgrade protocol; rename `TraceControlPropagator*` |
| `docs/tracing/traceability.md` | P15-02 evidence без Builder; актуальные имена provider/propagator |
| `docs/decisions/ADR-baggage-filtering-spike-finding.md` | post-decision implementation note (spike results не переписаны) |
| `docs/tracing/otel-compatibility-matrix.md` | BOM upgrade protocol checklist item; SPI FQN provider |

Historical snapshots / phase-15 plan / baggage boundary plan/report **не** изменялись.

### 5.1 Naming clarity rename (post-Slice 5)

Явный rename (behavior unchanged, SPI wire name `platform-trace-control` сохранён):

| Was | Became | Why |
|---|---|---|
| `InboundTraceControlPropagator` | `TraceControlPropagator` | класс реализует полный `TextMapPropagator` (extract + inject), не только inbound |
| `InboundTraceControlPropagatorProvider` | `TraceControlPropagatorProvider` | provider производит двусторонний propagator |
| SPI service FQN | updated in `META-INF/services/...ConfigurablePropagatorProvider` | discoverable class FQN; **getName() unchanged** |

`InboundTraceControl` / `InboundTraceControlExtractor` (API inbound value model) **не** переименовывались.

## 6. Open risks (не закрыты этим cleanup)

| ID | Status | Note |
|---|---|---|
| `RG-PROP-PARTIAL-WRITE` / `FU-PROP-ATOMIC-INJECT-01` | **OPEN** | Non-atomic multi-header inject characterized; blocks production rollout until risk acceptance or mitigation |
| `PlatformSpiAgentSmokeTest` | **BLOCKED** | Docker unavailable on evidence machine |

## 7. Verdict (slice-local)

| Gate | Result |
|---|---|
| W3C outbound correction + conformance/integration | **PASS** |
| JMH full-profile before/after per-row | **PASS** (artifacts + SHA-256) |
| `OutboundBaggageAgentE2ETest` causal proof | **PASS** |
| Live ADR/traceability/BOM protocol | **PASS** |
| `tracingConfigurationInventoryVerify` / S-01 gates / `pr1` / `pr4` / SPI JAR | **PASS** |
| `.\gradlew.bat build` | **PASS** |
| `git diff --check` | **PASS** |
| `PlatformSpiAgentSmokeTest` | **BLOCKED** (Docker host unreachable; not skipped as green) |
| Aggregate verify-all | **PASS_WITH_WARNINGS** — cleanup complete; SPI packaged smoke requires Docker |
| Production rollout | **NOT READY** while `RG-PROP-PARTIAL-WRITE` OPEN and SPI smoke BLOCKED |
