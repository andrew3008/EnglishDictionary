# Supported Runtime Matrix — Platform Tracing v0.1.0

> **Pre-production compatibility envelope, not rollout approval.** Current architecture: [final active architecture](./architecture/platform-tracing-final-architecture.md). `RG-IDENTITY-TRUST OPEN`, `RG-CONTROLLED-AGENT OPEN`, **PRODUCTION ROLLOUT FORBIDDEN**.

| Поле | Значение |
|------|----------|
| **Product** | `spring-boot-platform-tracing` |
| **Version** | `0.1.0-SNAPSHOT` (Фаза 1) |
| **Дата** | 2026-08-05 |
| **Статус** | **Official compatibility envelope** для architecture board и потребителей |
| **P6/P7 note** | P5.9 PASS; P6.10 zero-legacy PASS; P6.11 coherent PASS (E2E/M10 reused from P5.9). P7 docs/scorecard under `docs/research/evidence/p7-docs/`. **Production rollout FORBIDDEN** until Architecture Committee GO (P7.8). |

---

## Production runtime (v0.1.0)

**Единственный поддерживаемый production path для v0.1.0 — agent-first:**

```text
Java 21
  + Spring Boot 3.5.x (validated: 3.5.16)
  + Controlled Platform Agent Distribution (pinned Agent 2.28.x + embedded platform extension)
  + platform-tracing-spring-boot-starter-servlet | -reactive
  + spring-boot-starter-platform-logging (MDC reader — recommended co-use)
  + OpenTelemetry Logback MDC Appender (camelCase keys — см. ADR-mdc-via-otel-agent-logback)
  + OpenTelemetry Collector (platform-tracing-collector-config)
```

SDK-only / без Controlled Agent Distribution не является поддерживаемым runtime. Для локальной
работы задайте `platform.tracing.enabled=false`; test-only SDK допустим только в непубликуемых
fixtures/source sets.

`RG-CONTROLLED-AGENT OPEN`; `RG-IDENTITY-TRUST OPEN`; `PRODUCTION ROLLOUT FORBIDDEN`. Поставка в pilot/production разрешается
только после независимого закрытия [Controlled Agent gate](./architecture/rg-controlled-agent-release-gate.md) и [identity trust gate](./architecture/rg-identity-trust-release-gate.md).

---

## Supported combinations

| Dimension | Supported | Validated on | Notes |
|-----------|-----------|--------------|-------|
| **JDK (runtime)** | **21** | 21 | Platform corporate standard |
| **JDK (compile)** | **21** | 21 | Gradle toolchain enforced |
| **Spring Boot** | **3.5.x** | **3.5.16** | BOM from `gradle.properties` |
| **Web — Servlet** | Tomcat via `starter-servlet` | Yes | MVC + Agent bytecode |
| **Web — Reactive** | Netty via `starter-reactive` | Yes | WebFlux + Agent bytecode |
| **OTel Java Agent** | **2.28.x** | 2.28.1 | Must match extension API version |
| **OTel SDK (via Agent/BOM)** | **1.62.0** | 1.62.0 | `openTelemetryBomVersion` |
| **OTel Instrumentation BOM** | **2.28.1** | 2.28.1 | Lockstep with agent |
| **Extension API** | **2.28.1-alpha** | 2.28.1-alpha | `opentelemetry-javaagent-extension-api` |
| **Spring Cloud release train** | 2025.0.x (optional) | 2025.0.0 | Совместим с Boot 3.5.x; Config/Bus подключаются сервисом явно |
| **Spring Cloud Context** | 4.3.x (optional) | 4.3.0 | Управляется Spring Cloud BOM; не входит транзитивно в tracing starters |
| **Collector** | **OTel Collector Contrib 0.154.0** (pin) | e2e module | Tail sampling / redaction / k8sattributes / routing connector YAML from `platform-tracing-collector-config`; Track B+ adoption 2026-06-10; boundary-контракт SDK ↔ Collector — `PlatformSamplingReasons` + `CollectorPolicyContractTest` ([ADR-collector-boundary](decisions/ADR-collector-boundary.md), runbook `collector-pipeline-production.md`) |

---

## Explicitly NOT supported (v0.1.0)

| Dimension | Status | Rationale |
|-----------|--------|-----------|
| **Spring Boot 2.7.x** | ❌ Not supported | Different tracing stack; no CI; no prod demand documented |
| **Java 11 / 17** | ❌ Not supported | Toolchain 21; no CI matrix |
| **Spring Boot 3.0 – 3.4.x** | ⚠️ Untested | May work; not validated; use 3.5.x for production |
| **Spring Boot 4.x** | ❌ Not supported yet | Spike when corporate migration announced |
| **SDK-only (no agent) as prod standard** | ❌ Not supported v0.1.0 | Reduced bytecode coverage; v1.1+ evaluate |
| **GraalVM native image** | ❌ Not supported v0.1.0 | Requires SDK-only path (backlog) |
| **Dual Java agent** | ❌ Not supported | OTel agent conflict — upstream limitation |

**Expanding this matrix requires:** architecture board approval, CI matrix row, regression + e2e green, documentation update.

---

## Required launcher (production)

```text
/opt/platform-agent/platform-agent-launcher.sh \
  -Dotel.instrumentation.logback-mdc.enabled=false \
  -jar /app/application.jar
```

Launcher подключает embedded Agent из distribution. Отдельные `-javaagent`,
`otel.javaagent.extensions` и `OTEL_JAVAAGENT_EXTENSIONS` запрещены: они обходят атомарность
distribution. In-repo pre-JVM CLI verifier удалён по решению архитекторов; supply-chain
enforcement для закрытия `RG-CONTROLLED-AGENT` должен быть внешним (signing / admission /
immutable registry). Этот deployment path не разрешён для pilot/production до закрытия
`RG-CONTROLLED-AGENT`.

> **Изменено (Фаза 12 / context-first).** Флаг `-Dotel.instrumentation.http.server.capture-request-headers=X-Trace-On,X-QA-Trace`
> **больше не требуется** для сэмплирования: `CompositeSampler` получает `InboundTraceControl` из OTel Context
> (через `InboundTraceControlPropagator`), не из span-атрибутов. См. [ADR-context-first-propagation.md](./decisions/ADR-context-first-propagation.md).

Рекомендуемые agent-флаги (Фаза 12, опционально):

```text
# Kafka async: consumer начинает новый trace + span link на producer (OTel-рекомендация)
-Dotel.instrumentation.messaging.experimental.receive-telemetry.enabled=true
# Подавление дублей вложенных span'ов (default semconv) — anti-double-instrumentation
-Dotel.instrumentation.experimental.span-suppression-strategy=semconv
```

> **Фаза 13 (typed span API / semantic layer).** Типобезопасные escape-hatch builder'ы
> (`httpClientSpan()`, `databaseSpan()`, `kafkaProducerSpan()` и т.д.) полностью совместимы с
> agent-first path: span'ы по умолчанию создаёт Агент, а builder'ы предназначены лишь для операций
> вне покрытия Агента и помечаются `@SuppressAgentInstrumentation`. Первичная защита от двойной
> инструментации — флаг `span-suppression-strategy=semconv` (выше) + runtime anti-double guard
> (модель B: degradation в enrich при platform-маркере той же категории); ArchUnit-правило
> `ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION` — defense-in-depth на стороне потребителя. Семантическая
> валидация в проде — `WARN` (не роняет трафик), в test/CI — `STRICT`. Подробности:
> [ADR-typed-span-api-semantic-layer.md](./decisions/ADR-typed-span-api-semantic-layer.md),
> [semconv-mapping.md](./semconv-mapping.md).

**Операционное требование (defense-in-depth).** Держите лимит размера HTTP-заголовков на server/gateway ~8 KiB
(Tomcat/Netty/Jetty default) — защита от amplification через `baggage`/headers (W3C baggage лимиты 64 entries / 8192 байт
применяются stock-классом, Java advisory `GHSA-rcgg-9c38-7xpx`, исправлено в OTel 1.62.0).

**Outbound propagation — требование к HTTP-клиентам (Фаза 12).** Исходящая инжекция платформенных заголовков
(`X-Request-Id` и пр.) и трейсинг Агентом применяются ТОЛЬКО к клиентам, построенным из авто-конфигурированных
Spring-builder'ов: `RestTemplateBuilder` / `RestClient.Builder` / инжектируемый `WebClient.Builder`. Клиенты,
созданные вручную (`new RestTemplate()`, `WebClient.create()`), НЕ получают ни W3C-трейсинг, ни платформенные
заголовки. Используйте инжектируемые builder'ы (см. Spring Boot docs про micrometer-tracing propagation).

**Edge trust boundary — стрип входящего trace-context от недоверенных источников (эксплуатация).** Платформа
в agent-compatible режиме делегирует extract W3C `traceparent`/`tracestate` Агенту и доверяет входящему контексту —
это корректно для **внутренних** сервисов за mesh/gateway. Для **публичных/edge** сервисов, принимающих трафик от
недоверенных клиентов/партнёров, входящий W3C trace-context надо **удалять/рестартовать на границе** (ingress / API
gateway / Cloudflare), иначе Агент усыновит «фантомного» родителя из чужой инфраструктуры (Honeycomb «Phantom Spans»).
Это **операционная ответственность gateway**, а НЕ задача платформенной библиотеки: платформа сознательно не строит
второй W3C-pipeline (см. [ADR-outbound-propagation.md](./decisions/ADR-outbound-propagation.md), раздел Edge / trust
boundary).

**Edge correlation id — Istio ≥1.25 (эксплуатация).** Семантика edge-stable `X-Request-Id` зависит от того,
сохраняет ли ingress входящий заголовок. Istio ≥1.25 по умолчанию **не доверяет** внешнему `x-request-id`; для
сохранения клиентского значения нужен `preserve_external_request_id` на ingress + корректно описанные internal CIDR.
Иначе значение будет перезаписано на edge, и «forward unchanged» начнётся уже от mesh-сгенерированного id.

При `suppress-micrometer-tracing=true` MDC заполняется через `OpenTelemetryAppender` с ключами `traceId`/`spanId`/`traceFlags` (см. [ADR-mdc-via-otel-agent-logback.md](./decisions/ADR-mdc-via-otel-agent-logback.md)). Agent auto-instrumentation `logback-mdc` отключается, чтобы не inject'ить snake_case (`trace_id`, …), несовместимый с `spring-boot-starter-platform-logging` v1.

Additional `OTEL_*` / `platform.tracing.*` — see [runbook/mdc-logging-production.md](./runbook/mdc-logging-production.md) (SRE). Spring yaml and Agent env **must stay consistent** for service identity and sampling (P1 rollout gate).

---

## Agent runtime: named SPI и `platform.tracing.enabled`

В agent/autoconfigure-режиме платформенные sampler и propagator доступны через стандартные OTel-ключи
(`ConfigurableSamplerProvider`/`ConfigurablePropagatorProvider`), помимо inline-customizer'ов:

```text
# Платформенный head-sampler по имени (CompositeSampler: force/QA/drop/route/ratio).
# Необязательно — по умолчанию работает compose-over-existing (named — явный opt-in).
-Dotel.traces.sampler=platform

# Платформенный управляющий пропагатор (X-Trace-On / X-QA-Trace / X-Request-Id).
# Дефолт дописывается автоматически (ENV-aware), указывать явно не обязательно.
-Dotel.propagators=tracecontext,baggage,platform-trace-control
```

| Ключ | По умолчанию | Поведение |
|------|--------------|-----------|
| `otel.traces.sampler` | не задан → compose-over-existing | `platform` — явный opt-in; рантайм существующих деплоев не меняется |
| `otel.propagators` | `tracecontext,baggage` → дописывается `platform-trace-control` | `none` → платформенный не добавляется; дубль не создаётся |

`platform.tracing.enabled` (default `true`) задаёт strict ownership. При `enabled=true` требуется
READY Controlled Platform Agent; application SDK запрещён. При `enabled=false` Agent должен
отсутствовать в startup validation window; публикуется NoOp facade с причиной
`platform.tracing.enabled=false`. Dynamic Agent attach после Spring startup не поддерживается.
Состояние видно в `/actuator/tracingStatus`.

`platform.tracing.mode` и `platform.tracing.configuration.enabled` удалены и fail-fast; aliases
не поддерживаются.
[ADR-named-spi-sampler-propagator.md](./decisions/ADR-named-spi-sampler-propagator.md),
[ADR-sdk-mode-detection.md](./decisions/ADR-sdk-mode-detection.md),
[ADR-config-precedence.md](./decisions/ADR-config-precedence.md),
[otel-compatibility-matrix.md](./tracing/otel-compatibility-matrix.md) (раздел Extension SPI).

---

## Platform-logging co-use checklist (production)

Tracing-стартер **не** пишет `traceId`/`spanId`/`traceFlags` в MDC при `suppress-micrometer-tracing=true`. Сервис **обязан** настроить writer + reader:

| # | Item | Owner |
|---|------|-------|
| 1 | `spring-boot-starter-platform-logging` (≥ 1.0.4) | App team |
| 2 | `opentelemetry-logback-mdc-1.0` (`2.28.1-alpha`) | App team |
| 3 | `logback-spring.xml`: include platform defaults + `OpenTelemetryAppender` camelCase | App team / SRE overlay |
| 4 | `-Dotel.instrumentation.logback-mdc.enabled=false` | SRE (Helm) |
| 5 | `platform.tracing.suppression.suppress-micrometer-tracing=true` | App team |
| 6 | Smoke: `traceId` в `%maskedMDC` совпадает с Jaeger | SRE post-deploy |

Подробный runbook: [runbook/mdc-logging-production.md](./runbook/mdc-logging-production.md).

**Не** ожидайте транзитивного `micrometer-tracing-bridge-otel` из tracing-стартера (Wave 2c) —
см. [ADR-mdc-via-otel-agent-logback.md](./decisions/ADR-mdc-via-otel-agent-logback.md).

---

## Recommended Spring configuration (production)

```yaml
platform:
  tracing:
    enabled: true
    configuration:
      authority: HELM
    suppression:
      suppress-micrometer-tracing: true   # required with Java Agent
```

---

## Configuration authority and runtime policy (P6 cutover)

Exactly one technological authority is selected at startup via
`platform.tracing.configuration.authority`. Authority fallback and dual writers are forbidden.
Agent-side enum uses `DEV_ACTUATOR` for the Spring `ACTUATOR` authority.

| Authority | When | Spring private-control discovery | Mutation path |
|-----------|------|----------------------------------|---------------|
| **HELM** | Production / shared startup-only | **None** (no Spring writer beans) | Agent bootstrap parser builds initial Category A policy from env/system properties; no Spring runtime write; no Actuator write surface |
| **CONFIG_SERVER** | Complete eight-leaf manifest delivery | Locator / codec / snapshot client / coalescer | Complete manifest → private `applySnapshot` only |
| **ACTUATOR** (+ `platform.environment=LOCAL`) | Local/dev typed leaves only | Actuator management controller + private snapshot client | Eight typed Category A leaves → private `applySnapshot` (Agent authority label `DEV_ACTUATOR`) |
| **TRACING ENABLED** | `platform.tracing.enabled=true` | Selected authority discovery | READY Controlled Agent required; functional facade |
| **TRACING DISABLED** | `platform.tracing.enabled=false` | **No** Config Server / Actuator private-control discovery | Agent absent during startup validation window; NoOp facade (`platform.tracing.enabled=false`); incompatible with `CONFIG_SERVER` / `ACTUATOR` |

**Category A** live policy is owned by Agent `AcceptedPolicyAccess`. The sole mutation operation is
private `applySnapshot` (plus `readAcceptedComplete`) on the private control plane.
Platform-domain JMX MBeans and the Control read projection are **READ-ONLY**; operators must not
treat platform JMX as a writer.

**Category B** (startup Agent parser, not Spring Category A leaves) final names live under
`platform.tracing.javaagent.*`, including:

- `platform.tracing.javaagent.scrubbing.enabled` / `.built-in-rules`
- `platform.tracing.javaagent.sampling.drop-paths` / `.force-record-values`
- `platform.tracing.javaagent.propagation.baggage.enabled` / `.allowlist-keys` / `.deny-patterns`

**No-alias policy.** Legacy property names, dual-read bindings, deprecated bridges, and
compatibility aliases are not supported. Deleted names fail fast or are ignored by Spring
with no silent remap — migrate to the final name in the same release train.

**Whole-train rollback.** After the P6 cutover, rollback unit is the **entire unreleased release
train**. State, property names, writers, manifests, and readers must not be rolled back
independently. Accepted policy is process-local and rebuilt from the selected authority after restart.

Deleted legacy writers (do not document or call): `RuntimeConfigApplier` / RefreshScope mutation,
Actuator `@WriteOperation` on `/actuator/tracing`, public `api.control.protocol` /
`otel.control.protocol`, `ScrubbingPolicyHolder`, Spring Category B scrubbing/sampling bindings.
Evidence: `docs/research/evidence/p6-cutover/`.

`GET /actuator/tracingStatus` is the read diagnostics surface (the legacy `/actuator/tracing` read
endpoint was removed together with its dual-channel sections; see
[ADR-remove-dual-channel-diagnostics.md](./decisions/ADR-remove-dual-channel-diagnostics.md) and
[runbook/tracing-status-diagnostics.md](./runbook/tracing-status-diagnostics.md)). Topology vs policy
semantics for startup-only Agent keys are unchanged in spirit; see
[ADR-runtime-config-policy-vs-topology.md](./decisions/ADR-runtime-config-policy-vs-topology.md)
for historical rationale (implementation details in that ADR may predate the private-writer cutover).

Operator runbooks and final-name examples:
[runbook/runtime-policy-control.md](./runbook/runtime-policy-control.md),
[runbook/jmx-consumer-migration.md](./runbook/jmx-consumer-migration.md),
[examples/README.md](./examples/README.md).

---

## Production readiness gates (not part of “supported”, but required before mass rollout)

| Gate | Priority | Status (2026-05-25) |
|------|----------|---------------------|
| This matrix published | P0 | ✅ This document |
| E2E: Agent → Collector → Jaeger | P1 | ✅ `TracingE2ETest` + agent smokes (26/26, `-PrunE2e`, Agent 2.28.1, 2026-06-08) |
| MDC: tracing + platform-logging + Agent | P1 | ✅ `AgentMdcPlatformLoggingAgentE2ETest` (G2-MDC-e2e) |
| Force header visible before `shouldSample` | P1 | ✅ `ForceSamplingAgentSmokeTest` |
| Production Collector YAML e2e-паритет (Фаза 16) | P1 | ✅ `CollectorProductionPolicyE2ETest` (production gateway YAML + recordpolicy gate; P0-регресс forced-traces) + `CollectorPolicyContractTest` (JVM-only contract) |
| Resilience при недоступном Collector'е | P1 | ✅ `CollectorUnavailableResilienceTest` (Agent + extension + dead OTLP endpoint; probe SLA < 2s, graceful exit) |
| WebFlux Reactor context (Agent path) | P1 | ✅ `ReactorContextPropagationAgentE2ETest` (G2-05-e2e) |
| BSP overflow policy on SDK 1.62.0 (probe-finding) | P0/P1 | v0.1.0 default: stock BSP = drop-new (probe-confirmed, re-validated on 1.62.0). **v1.x default**: `PlatformDropOldestExportSpanProcessor` с гарантированной семантикой drop-oldest (§2.5 требований) — активируется автоматически через SPI supplier; для возврата к stock BSP задайте явно `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM` (single-exporter only; multi-exporter → WARN + fallback). См. [ADR-drop-oldest-export-processor-v1.md](decisions/ADR-drop-oldest-export-processor-v1.md) и [ADR-bsp-overflow-policy-finding.md](decisions/ADR-bsp-overflow-policy-finding.md) |
| Duplicate spans regression suite | P1 | OK | Startup matrix (`DuplicateSpansRegressionMatrixTest` Servlet/WebFlux + `TracingObservationSuppressStartupTest`); e2e subprocess WARN-smoke (`DuplicateHttpSpanAgentSmokeTest`, `-PrunE2e`). Jaeger-level «2 scopes» — best-effort only при Agent premain |
| JMH baseline in CI | P1 | ✅ Сюита 12 классов + `jmhSaveBaseline`/`jmhCompareBaseline` per hardware-profile ([jmh-suite.md](tracing/jmh-suite.md)); строгий режим `-PjmhFailOnRegression` |
| Performance assurance model (Фаза 17) | P0 | Модель развёрнута: [ADR-performance-model](decisions/ADR-performance-model.md), матрица, бюджеты, perf-стенд; hard-бюджеты в PENDING до official-прогонов на reference-лаборатории |

See `docs/architecture/` archive: `alignment-memo-report-v2-vs-platform-traces.md` §6.

---

## Performance pre-release checklist (Фаза 17, PR-6)

Релиз по производительности считается одобренным, когда выполнены ВСЕ пункты
(числовые пороги — [performance-budgets.yaml](tracing/performance-budgets.yaml),
определения — [ADR-performance-model.md](decisions/ADR-performance-model.md)):

| # | Пункт | Команда / источник | Owner |
|---|-------|--------------------|-------|
| 1 | Контракт бюджетов и waivers целостен (поля, expiry, ссылки на REQ-*) | `./gradlew :platform-tracing-bench:test` (`PerformanceBudgetsContractTest`, всегда активен) | Platform |
| 2 | Калибровка шума: пара M0/M0 на reference-лаборатории, Δ CPU ≤ 1% | `run-perf-scenario.ps1 -Scenario m0` ×2 + `analyze-perf-run.ps1` | Platform + SRE (стенд) |
| 3 | Sign-off tier матрицы прогнан (M4, M5, M5w, M6, M8a–c, M9, M10, S1, queue saturation ×2 ветки), все прогоны `runValid=true` | `platform-tracing-perf-tests` (README) | Platform |
| 4 | Hard-бюджеты переведены из PENDING в PASS (evidence-ссылки) или WAIVER (board) | правка `performance-budgets.yaml` через PR | Platform + board |
| 5 | **Release-гейт зелёный** | `./gradlew :platform-tracing-bench:performanceReleaseGate` | Platform |
| 6 | JMH-регрессий нет против committed baseline | `./gradlew :platform-tracing-bench:jmh jmhCompareBaseline -PjmhFailOnRegression` | Platform |
| 7 | Evidence-tier артефакты приложены (M11/M12/M13, flamegraphs near-budget прогонов, JFR) | `docs/tracing/perf-results/` | Platform |
| 8 | Waivers непросрочены, у каждого expires/approvedBy/evidence | гарантируется пунктом 1 | Board |

Waiver governance — контракт: запись в `waivers[]` без `expires`, `approvedBy`, `evidence`
или с истёкшей датой роняет пункт 1 (и, как следствие, обычный `test`).

---

## Version pin source of truth

`gradle.properties`:

```properties
springBootVersion=3.5.16
openTelemetryBomVersion=1.62.0
openTelemetryInstrumentationBomVersion=2.28.1
openTelemetryInstrumentationAlphaVersion=2.28.1-alpha
```

BOM changes require architect review + full `./gradlew test` + e2e smoke.

---

## FAQ (architecture board)

**Q: Java 17?**  
A: Not in v0.1.0 matrix. Request via separate epic if business requires.

**Q: Spring Boot 3.2 LTS?**  
A: Untested. Production target is 3.5.x aligned with platform BOM.

**Q: Can we run without Java Agent?**  
A: При `platform.tracing.enabled=true` — нет: требуется READY Controlled Agent Distribution.
Для local/dev без Agent используйте `platform.tracing.enabled=false`; application SDK не
является поддерживаемым fallback.

**Q: Кто пишет traceId в MDC?**  
A: Production: `OpenTelemetryAppender` (camelCase). Tracing-стартер не пишет trace-ключи при `suppress=true`. Runbook: [runbook/mdc-logging-production.md](./runbook/mdc-logging-production.md).

**Q: Spring Boot 4?**  
A: Not yet. Quarterly spike when platform announces migration.
