# Traceability matrix: требования → реализация

> Источник требований: `Platform_Traces_Archive\Traces Requests.txt` — единственный авторитетный документ.
>
> Цель: сопоставить каждый пункт требований с конкретным файлом/классом/конфигом
> в репозитории `Platform_Traces`. Используется командой архитекторов на ревью и
> SRE для оценки готовности к выходу в продакшен.
>
> Легенда статусов:
>
> - **OK** — реализовано в коде или в YAML-конфиге Collector'а.
> - **OK (Collector)** — закрыто на стороне OpenTelemetry Collector'а (зона SRE,
>   но входит в поставку модуля `platform-tracing-collector-config`).
> - **Out of Java scope** — закрывает не Java-разработчик
>   (например, ответственность OTel Java Agent / OTel Collector / SRE / архитекторов).

---

## §1. Генерация и управление Span'ами

| # | Требование | Статус | Реализация |
|---|---|---|---|
| 1 | API создания / завершения Span'ов (Open/Close, Defer) | OK | `platform-tracing-api` — `TraceOperations` фасад; OTel Java Agent инструментирует Spring MVC/WebFlux/JDBC автоматически |
| 1.1 | Обязательные атрибуты span'а: `trace_id`, `span_id`, `service.name`, `host.name`, `container.id`, `service.version`, `deployment.environment.name`, `platform.trace.type`, `platform.trace.result` | OK | `PlatformResourceProvider` (resource attrs incl. `container.id` omit-if-unconfigured, `host.name`, `deployment.environment.name`); `EnrichingSpanProcessor` (`platform.trace.result`, MDC `platform.remote.service`); OTel SDK — `trace_id`/`span_id` |
| 2 | Типизированные span'ы: HTTP / DB / RPC / Exception | OK | OTel Java Agent instrumentation (auto): `http.*`, `db.*`, `rpc.*`, `exception.*` согласно semconv. **Фаза 13:** typed span API для случаев вне покрытия Агента — `httpServerSpan()`/`httpClientSpan()`/`databaseSpan()`/`rpcServerSpan()`/`rpcClientSpan()`/`kafkaProducerSpan()`/`kafkaConsumerSpan()` + `ExceptionRecorder` (sanitized event); семантика валидируется `AttributePolicy`/`CategoryContracts` |
| 3 | Передача контекста (W3C TraceContext) + флаги сэмплирования + `X-Trace-On` | OK | OTel Java Agent (W3C TraceContext default); `platform-tracing-otel-javaagent-extension/.../sampler/CompositeSampler.java` обрабатывает `X-Trace-On` и `X-QA-Trace` |
| 3.1 | Outbound propagation платформенных заголовков (Фаза 12, agent-compatible) | OK | Secure-by-default DENY + trusted-host/topic gating: контракты `OutboundPropagationPolicy`/`TraceControlHeaderInjector`/`TrustedDestinationMatcher` (`platform-tracing-api`), реализация `Default*` (`platform-tracing-otel.propagation.control`); HTTP — `PlatformOutboundHttpInterceptor` (webmvc) / `PlatformOutboundExchangeFilterFunction` (webflux); Kafka — `PlatformKafkaProducerInterceptor`. `propagate-force-trace=false` (sampled-flag несёт решение) |
| 3.2 | `X-Request-Id` = edge-stable correlation id (`request_id ≠ trace_id`), generate-if-absent + validate (CWE-113) | OK | `RequestIdSupport` (reject-and-regenerate); `TraceResponseHeader*Filter` (response `X-Request-Id` = correlation id, `X-Trace-Id` = trace-id); inbound-валидация унифицирована в `DefaultInboundTraceControlExtractor.fromHeaders` (HTTP+Kafka). ADR-request-id-correlation-id |

## §2. Изоляция и отказоустойчивость

| # | Требование | Статус | Реализация |
|---|---|---|---|
| 1 | Неблокирующее поведение, ошибки только логируются | OK | `platform-tracing-otel-javaagent-extension/.../processor/PlatformCompositeSpanProcessor.java` — обёртка-предохранитель span-pipeline (глотает исключения делегатов); `exporter/SafeSpanExporter.java` — изоляция исключений транспортного экспортёра (Фаза 10) |
| 2 | Асинхронный экспорт | OK | OTel SDK `BatchSpanProcessor` / платформенный `PlatformDropOldestExportSpanProcessor` (фоновый worker); конфигурируется через PR-1.A `addPropertiesSupplier` |
| 3 | Таймауты операций (~100 мс) | OK | `TracingProperties.queue.exportTimeout = 100ms` (default) → `OTEL_BSP_EXPORT_TIMEOUT` |
| 4 | Автоматический fallback (тихий пропуск) | OK | drop-on-overflow (`PlatformDropOldestExportSpanProcessor` drop-oldest / stock BSP drop-new) + `PlatformCompositeSpanProcessor` + `SafeSpanExporter` глотают исключения |
| — | CPU < 3 %, Memory < 10 % | **OK (Фаза 17, evidence)** | Performance assurance model: определения SLA и статпротокол — [ADR-performance-model.md](../decisions/ADR-performance-model.md); матрица «требование → evidence → gate» — [requirements-traceability.md](requirements-traceability.md); бюджеты — [performance-budgets.yaml](performance-budgets.yaml) (`PerformanceBudgetsContractTest`). Окружение нагрузочных прогонов — зона SRE |
| 5 | Фиксированная очередь span'ов с drop-oldest | **OK (v1.x default)** | v0.1.0 default: стоковый `BatchSpanProcessor` = drop-new (probe-confirmed, ADR-bsp-overflow-policy-finding.md). v1.x **default**: `PlatformDropOldestExportSpanProcessor` (BSP-lite, bounded `ArrayDeque`, drop-oldest, single-exporter only) — активируется автоматически через `PlatformTracingDefaultsProvider` SPI supplier. Для возврата к stock BSP: `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`. См. `ADR-drop-oldest-export-processor-v1.md` и `MIGRATION.md` |
| 6 | Принудительный сброс при переполнении | OK | OTel BSP — встроенное поведение; rate-limited warning log в SDK 1.61+ |
| 7 | Соответствие OTel semantic conventions | OK | `platform-tracing-otel-javaagent-extension/.../processor/ValidatingSpanProcessor.java` — runtime-валидация |
| 8 | Валидация обязательных полей (`service.name`) | OK (Collector) | `platform-tracing-collector-config/.../otel-collector-gateway-tail-sampling.yaml` — `transform/platform-semconv-backstop` (backfill `platform.trace.type`, удаление `url.full` на SERVER); на стороне SDK — `ValidatingSpanProcessor` |

## §2.1–§2.5. Контроль объёма данных в span'е

| # | Требование | Default | Статус | Реализация |
|---|---|---|---|---|
| 2.1 | Лимит атрибутов на span | 50 | OK | `TracingProperties.Limits.maxAttributes=50` → `OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` (через PR-1.A `platformDefaults`) |
| 2.2 | Лимит длины значения атрибута | 1000 | OK | `TracingProperties.Limits.maxAttributeValueLength=1000` → `OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT` |
| 2.3 | Лимит количества событий | 10 | OK | `TracingProperties.Limits.maxEvents=10` → `OTEL_SPAN_EVENT_COUNT_LIMIT` |
| 2.4 | Автомаскирование чувствительных данных (логины, пароли, email, токены, PII) | enabled | OK | 1-я линия: `platform-tracing-otel-javaagent-extension/.../processor/ScrubbingSpanProcessor.java` + `BuiltInSpanAttributeScrubbingRules` (`password`, `jwt`, `email`, `pan`, `phone`); 2-я линия (Фаза 16): `redaction/platform-second-line` в gateway YAML (blocked_values: JWT/Bearer/e-mail/PAN, `summary: info`) |
| 2.5.1 | Span timeout 30s | 30s | OK | `TracingProperties.Limits.spanTimeout=PT30S` → `SpanWatchdogProcessor` |
| 2.5.2 | Trace timeout 60s | 60s | OK | `TracingProperties.Limits.traceTimeout=PT60S` → `SpanWatchdogProcessor` |

## §3. Экспорт и доставка span'ов

| # | Требование | Статус | Реализация |
|---|---|---|---|
| 3.1 | Быстрый ответ при отправке (не блокировать) | OK | async export через `BatchSpanProcessor` / `PlatformDropOldestExportSpanProcessor` (worker-поток); транспорт обёрнут в `SafeSpanExporter` (изоляция исключений + метрики), без блокировки бизнес-потока |
| 3.2 | Локальная буферизация | OK | bounded in-memory очередь процессора (`maxSize=2048` default через `TracingProperties.Queue.maxSize`); durable-буферизация — зона Collector (см. ADR Фазы 10) |
| 3.3 | Retry с exponential backoff | OK | `otel.java.exporter.otlp.retry.disabled=false` (default since OTel SDK 1.59); `TracingProperties.Exporter.Otlp.Retry` + Collector `retry_on_failure`. **Источник истины retry — OTLP-клиент и Collector** (SDK-обёртка не дублирует retry, см. ADR-safe-span-exporter-v1) |
| 3.4 | Обработка backpressure (429/503) | OK (Collector) | OTel OTLP exporter + Collector `sending_queue` (`queue_size=10000`) + `memory_limiter` (429 клиентам). SDK-side circuit breaker **сознательно не реализован** (ресурсные ограничения CPU<3%/Mem<10%); `SafeSpanExporter` лишь изолирует и считает отказы. См. ADR-safe-span-exporter-v1 |
| 3.5 | Приоритизация: errors > slow > success | OK (Collector) | SDK проставляет `platform.trace.priority`/`duration_class` (`ClassificationSpanProcessor`); `tail_sampling` policies в `otel-collector-gateway-tail-sampling.yaml` (errors-always, slow-traces, **platform-high-priority**, probabilistic-default). SDK-side priority queue не строим (риск orphaned spans) |

## §4. Конфигурируемость

| # | Требование | Статус | Реализация |
|---|---|---|---|
| 4 | Гибкие настройки через конфиг (без правок кода) | OK | `TracingProperties` (`platform.tracing.*`); extension SPI `addPropertiesSupplier` — платформенные дефолты BSP/limits; `GET /actuator/tracing`: `otelEffective` (факт) + `otelEnvHints` (маппинг Spring→`OTEL_*` через `DurationToMillis`) |
| 4 | Dynamic reconfig sampling-ratio на лету | OK | `TracingActuatorEndpoint.updateTracing("samplingRatio", ...)` → JMX `MutableRatioSampler` |

## §5–§7 (управление сэмплированием, требования из «Параметров для управления»)

| # | Требование | Статус | Реализация |
|---|---|---|---|
| % сэмплирования трейса (head sampling) | head ratio в SDK | OK | `MutableRatioSampler` (default 0.1) + `CompositeSampler` + `platform.sampling.reason` |
| Errors **всегда** сэмплируются | предпочтительно на Collector'е | OK (Collector) | `tail_sampling.policies.errors-always-sample: status_code=ERROR` в `otel-collector-gateway-tail-sampling.yaml` |
| Header X-Trace-On для force-sampling | 100% при наличии | OK | `CompositeSampler.forceHeader = X-Trace-On` → `platform.sampling.reason=force_header`; политика `forced-traces` (`[force_header, qa_trace]`, P0-фикс Фазы 16) + contract-тест |
| QA-ручка возвращает Request ID | заголовок `X-QA-Trace` | OK | `CompositeSampler.qaHeader = X-QA-Trace`; `TracingProperties.Response.headerName = X-Request-Id`; политика `qa-trace` в Collector |
| Динамическое управление ratio | через JMX/Actuator | OK | `MutableRatioSampler` + `TracingActuatorEndpoint` (write-операция) |

---

## Out of Java scope (явно НЕ задача ведущего Java-разработчика)

| Зона | Ответственный | Почему |
|---|---|---|
| Tail-sampling политики и их параметры (`decision_wait`, `num_traces`, latency thresholds) | SRE | YAML-конфиг Collector'а; зависит от p99 latency и RPS реального окружения |
| Helm-чарт и rendering `OTEL_*` env vars | SRE / DevOps | Хранится в `Skaffold*` репозиториях |
| Развёртывание Jaeger storage и его TTL | SRE | `otel-collector-config-ttl-tiers.yaml` — пример; конкретный TTL за SRE |
| Performance baselines (CPU/Memory overhead) | SRE | Нагрузочные тесты, не входят в задачу Java-dev |
| Grafana дашборды для Collector'а | SRE | Observability infrastructure |

---

## GAP-перечень (Wave 1 — PR-GAP-1, закрыто)

| GAP | Статус | Реализация |
|---|---|---|
| G-01 `container.id` в Resource | OK | `PlatformResourceProvider` + `TracingProperties.Service.containerId`; omit-if-unconfigured |
| G-02 MDC `platform.remote.service` | OK | `RemoteServiceMdc` + `EnrichingSpanProcessor` + response filters; ADR WebFlux |
| M-01 `@Traced` → `platform.traced.method` | OK | `PlatformAttributes.PLATFORM_TRACED_METHOD` + `TracedAspect` |

Pod UID не подставляется в `container.id` — отдельный атрибут `k8s.pod.uid` (Wave 4).

## GAP-перечень (Wave 2 — PR-GAP-2, закрыто)

| GAP | Статус | Реализация |
|---|---|---|
| N-01–N-03 semconv rename | OK | `PlatformAttributes` + Collector YAML + semconv-lint + docs; ADR resource override |
| A-02 `SpanResult` extend | OK | `TIMEOUT`, `CANCELLED`, `REJECTED`, `SKIPPED`; `SpanWatchdogProcessor` → `platform.trace.result=timeout` |
| A-03 `platform.sampling.reason` | OK | `ForwardingSamplingResult` + `CompositeSampler`; semconv-lint WARNING optional |

## GAP-перечень (Wave 3 — PR-GAP-3, закрыто)

| GAP | Статус | Реализация |
|---|---|---|
| A-01 `SpanRelationship` API | OK | `SpanRelationship` + `startRootSpan` / `startChildSpan` / `startDetachedSpan` |
| G-03 Span Links (Kafka batch) | OK | `RemoteSpanLink`, `startSpanWithLinks`, `addLink`; `DefaultTraceOperations` + `createFromRemoteParent` |
| Compile-safety | OK | default no-op methods на `TraceOperations`; `NoopTraceOperations` наследует автоматически |

Документация: [links-kafka.md](./links-kafka.md).

## GAP-перечень (Wave 4 — PR-GAP-4, закрыто)

| GAP | Статус | Реализация |
|---|---|---|
| G-01b procfs `container.id` | OK | `ProcfsContainerIdDetector` + fallback в `PlatformResourceProvider` |
| G-01c `k8s.pod.uid` | OK | `K8sAttributes.K8S_POD_UID` + `PROP_K8S_POD_UID` / `KUBERNETES_POD_UID`; omit-if-unconfigured |
| G-02b WebFlux MDC mirror | OK | `RemoteServiceTraceMirror` + `RemoteServiceContextPropagation` + `RemoteServiceReactorContext` |
| G-04 Baggage allowlist | OK | `BaggageSpanProcessor` + `platform.tracing.baggage.*` |
| N-04 RequestId ADR | OK | [ADR-request-id-correlation-id.md](../decisions/ADR-request-id-correlation-id.md) (supersedes ADR-request-id-equals-trace-id.md) |
| M-02 RPC SpanKind docs | OK | [semconv-mapping.md](../semconv-mapping.md) |

## GAP-перечень (Фаза 2 — Context Propagation, Wave 1–2 закрыто)

| GAP | Статус | Реализация |
|---|---|---|
| G2-01 high-level `inSpan()` API (Runnable / ThrowingSupplier / SpanRelationship overloads) | OK | `TraceOperations.inSpan(...)` — default-методы поверх `startSpan()` + `SpanHandle.close()`; `ThrowingSupplier` (`platform-tracing-api/util`) для lambda-совместимости с checked-исключениями; покрыто `DefaultTraceOperationsInSpanTest` |
| G2-02 фасад explicit context propagation | OK | `PlatformContextPropagation` (`platform-tracing-api/propagation`) — `wrap(Runnable)`, `wrap(ThrowingSupplier)`, `contextAware(Executor / ExecutorService)`. ADR boundary: API-модуль без Spring/Micrometer. |
| G2-03 OTel-native реализация фасада | OK | `OtelPlatformContextPropagation` — `Context.current().wrap(...)` + `Context.taskWrapping(...)`; `NoOpPlatformContextPropagation` для degraded mode; авто-регистрация в `TracingCoreAutoConfiguration` |
| G2-04 opt-in `TaskDecorator` композиция для `@Async` | OK | `TracingAsyncContextAutoConfiguration` + `ThreadPoolTaskExecutorContextPropagationBeanPostProcessor` (Ordered.LOWEST_PRECEDENCE, composition через reflection чтение existing `taskDecorator`) + `PlatformContextTaskDecorator` (Micrometer `ContextSnapshotFactory.captureAll()`). Default `enabled=false`. ADR: [ADR-async-task-decorator-opt-in.md](../decisions/ADR-async-task-decorator-opt-in.md) |
| G2-05 WebFlux `publishOn`/`subscribeOn` OTel Context (Agent path) | OK | `ReactorContextPropagationAgentE2ETest` (G2-05-e2e) — subprocess WebFlux + Agent + `suppress-micrometer-tracing=true`; in-process bridge-only: `BridgeOtelReactorContextPropagationIntegrationTest` `@Tag("bridge-otel-path")` |
| G2-MDC traceId в platform-logging при suppress + Agent | OK | `AgentMdcPlatformLoggingAgentE2ETest` (G2-MDC-e2e) — tracing + logging + OpenTelemetryAppender camelCase; ADR: [ADR-mdc-via-otel-agent-logback.md](../decisions/ADR-mdc-via-otel-agent-logback.md) |
| G2-MDC Wave 2c bridge-otel slimming | OK | `micrometer-tracing-bridge-otel` `compileOnly` в autoconfigure; opt-in: [MIGRATION.md](../MIGRATION.md); `@Tag("bridge-otel-path")` для dev-only smoke |
| G2-MDC Wave 2d runbook + docs | OK | [runbook/mdc-logging-production.md](../runbook/mdc-logging-production.md); `SUPPORTED.md` logging checklist; README; e2e 13/13 post-2c |
| G2-06/07/08 unit-тесты nested-stack / CompletableFuture / Executor propagation | OK | `OtelPlatformContextPropagationTest`, `NoOpPlatformContextPropagationTest` + tests для BPP и `PlatformContextTaskDecorator` (composition order, Spring Boot 3.5 guard, повторная инициализация) |

ADR'ы Фазы 2:
- [ADR-mdc-via-otel-agent-logback.md](../decisions/ADR-mdc-via-otel-agent-logback.md)
- [ADR-async-task-decorator-opt-in.md](../decisions/ADR-async-task-decorator-opt-in.md)
- [ADR-reactor-no-inspan-v0.1.0.md](../decisions/ADR-reactor-no-inspan-v0.1.0.md)

## GAP-перечень (PR-цепочка инфраструктуры — закрыто)

| GAP | PR | Статус | Реализация |
|---|---|---|---|
| BSP/limits дефолты для OTel agent | **PR-1.A** | OK | `PlatformTracingDefaultsProvider.supply()` → `PlatformAutoConfigurationCustomizer` (`addPropertiesSupplier`); тесты `PlatformTracingDefaultsProviderTest`, `PlatformAutoConfigurationCustomizerTest` |
| Helper `Duration` → integer-ms (OTel SPEC) | **PR-1.B** | OK | `DurationToMillis` + `OtelEnvHintsBuilder` (actuator); тесты `DurationToMillisTest`, `OtelEnvHintsBuilderTest` |
| Эффективный OTel-конфиг в `/actuator/tracing` | **PR-1.C** | OK | секция `otelEffective` (`OtelEffectiveConfigSnapshot`); контракт `OtelEffectiveConfigSnapshotDefaultsContractTest` |
| E2E SDK → Collector → Jaeger | **PR-3** | OK | `TracingE2ETest` + agent smokes (13/13, `-PrunE2e`) |

**R1 dual-channel (Wave R1+, закрыто):** `platform.tracing.queue.export-timeout` выровнен на 5000ms (= agent BSP export timeout default via `PlatformTracingDefaultsProvider` / `OtelSdkDefaults`). Targeted alignment-test (`SharedDefaultsAlignmentTest`) + diagnostic WARN (`DualChannelDriftDiagnostics`, whitelist + 3 условия) + ADR ([ADR-dual-channel-properties-v0.1.md](../decisions/ADR-dual-channel-properties-v0.1.md)) + runbook. Full bridge Spring → `-Dotel.*` явно отвергнут (см. ADR, рассмотренный вариант C). См. [CHANGELOG.md](../../CHANGELOG.md) и [MIGRATION.md](../MIGRATION.md).

**BSP overflow policy (Wave R1+, finding):** probe-тест на SDK 1.62.0 показал фактическую политику **drop-new** (не drop-oldest). Требование смягчено до `bounded-queue-with-drop` для v0.1.0; custom processor для гарантированного `DROP_OLDEST` — backlog v1.x. Probe — `BatchSpanProcessorOverflowPolicyProbeTest`, finding — [ADR-bsp-overflow-policy-finding.md](../decisions/ADR-bsp-overflow-policy-finding.md). Эксплуатационная безопасность валидируется e2e: `BspOverflowSafetyAgentSmokeTest`.

**DROP_OLDEST custom processor (v1.x default, GO):** SPI spike (8/8) подтвердил безопасность замены стокового `BatchSpanProcessor` на `PlatformDropOldestExportSpanProcessor` (BSP-lite c гарантированной drop-oldest семантикой). **DEFAULT v1.x** (§2.5 требований): `PlatformTracingDefaultsProvider` через SPI supplier автоматически выставляет `platform.tracing.queue.overflow-policy=DROP_OLDEST`; для возврата к stock BSP задайте явно `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`. Multi-exporter → WARN + fallback на стоковый BSP (single-exporter only). После замены стоковый BSP явно `shutdown()`-ится (нет double-export — `BspDropOldestNoDoubleExportTest`). Spring-side `DropOldestAspirationDiagnostics` пишет WARN только при явном `UPSTREAM` vs Spring `DROP_OLDEST`. Полный набор тестов: `BspReplacementSpikeTest` (SPI gate, 11+1 обновлён), контракт `PlatformDropOldestExportSpanProcessor{OverflowPolicy,Lifecycle,BuilderValidation}Test`, `PlatformAutoConfigurationCustomizerExportProcessorTest`, `DropOldestAspirationDiagnosticsTest`, e2e `BspDropOldestSafetyAgentSmokeTest`. ADR — [ADR-drop-oldest-export-processor-v1.md](../decisions/ADR-drop-oldest-export-processor-v1.md).

**Duplicate spans regression suite (Wave R1+, OK):** startup-контракт 2×2 (Agent on/off × suppress on/off) покрыт `DuplicateSpansRegressionMatrixTest` для Servlet и WebFlux + `TracingObservationSuppressStartupTest` (WARN-сторона). HTTP-уровневый smoke — `DuplicateHttpSpanAgentSmokeTest` в e2e (P1 recommended перед broad rollout).

## GAP-перечень (Фаза 13 — Typed Span API / Semantic Layer, закрыто)

| GAP | Статус | Реализация |
|---|---|---|
| P13-01 typed escape-hatch builders (HTTP/DB/RPC/Kafka) | OK | контракты `platform-tracing-api/api.manual`, реализации `platform-tracing-otel/core.manual`, вход через `TraceOperations.spans()` / `SpanFactory` |
| P13-02 единый источник истины семконвенций | OK | internal `core.semconv.SemconvKeys` + `core.semconv.policy.CategoryContracts`/`CategoryContract`; публичным реестром строковых имён остаётся `PlatformAttributes` |
| P13-03 runtime-валидация атрибутов + режимы | OK | `AttributePolicy.validateAndNormalize` → `ValidatedAttributes`; `SemconvValidationMode` STRICT/WARN/DISABLED; метрики `platform.tracing.semconv.violations`; `AttributePolicyTest`. ADR: [ADR-semconv-validation-modes.md](../decisions/ADR-semconv-validation-modes.md) |
| P13-04 anti-double instrumentation (agent-first) | OK | primary guard — agent-флаг `span-suppression-strategy=semconv` + e2e no-dup; manual spans используют явную topology (`child`/`root`/`detached`), marker-based degradation удалена |
| P13-05 enrichment активного span'а | OK | API-owned `SpanEnricher.enrichCurrentSpan` + `GenericSpanEnrichment` (`requestId`, `userHash`, `result`); category-specific OTel path удалён |
| P13-06 безопасная запись исключений (PII) | OK | `ExceptionRecorder` + `ExceptionMessagePolicy` (message/stacktrace off-by-default, обрезка); закрывает gap «`ScrubbingSpanProcessor` чистит attrs, но не events»; `ExceptionRecorderTest` |
| P13-07 unsafe-attribute escape-hatch + аудит | OK | `unsafeAttribute(String,String)` → метрика `platform.tracing.unsafe_attributes{key_class}`; в STRICT запрещён без `allow-unsafe-attributes` |
| P13-08 STRICT в test/CI, WARN в проде | OK | `SemconvStrictTestAutoConfiguration` (`platform-tracing-test`) публикует бин `SemconvValidationMode.STRICT` с приоритетом над property; `SemanticLayerAutoConfigurationTest` |
| P13-09 санитизация URL/SQL | REVISED | package-private `core.manual.UrlSanitizer` редактирует userinfo/query для `DefaultHttpTracing`; неиспользуемый `SqlSanitizer` удалён |

ADR'ы Фазы 13:
- [ADR-typed-span-api-semantic-layer.md](../decisions/ADR-typed-span-api-semantic-layer.md)
- [ADR-semconv-validation-modes.md](../decisions/ADR-semconv-validation-modes.md)
- [ADR-semconv-governance-weaver.md](../decisions/ADR-semconv-governance-weaver.md) (открытый вопрос — отложен на SRE-встречу)

## GAP-перечень (Фаза 15 — autoconfigure & extension SPI, закрыто)

| GAP | Требование | Статус | Реализация |
|---|---|---|---|
| P15-01 named sampler SPI `platform` | §4 (конфигурируемость без кода) | OK | `sampler/PlatformSamplerProvider` (`ConfigurableSamplerProvider`, `getName()="platform"`) + `META-INF/services/...traces.ConfigurableSamplerProvider`; сборка через общий `PlatformSamplerBuilder`; тесты `PlatformSamplerProviderTest`, `PlatformSpiAutoconfigureIntegrationTest` |
| P15-02 named propagator SPI `platform-trace-control` | §3 (передача контекста) | OK | `propagation/InboundTraceControlPropagatorProvider` (`ConfigurablePropagatorProvider`) + `META-INF/services/...ConfigurablePropagatorProvider`; `InboundTraceControlPropagatorBuilder`; тест `InboundTraceControlPropagatorProviderTest` |
| P15-03 ENV-aware дефолт `otel.propagators` | §4 | OK | `propagation/PlatformPropagatorsDefaultsCustomizer` через `addPropertiesCustomizer` (не supplier); always-append удалён из `PlatformPropagatorFactory`; тест `PlatformPropagatorsDefaultsCustomizerTest` |
| P15-04 idempotency-guard sampler (no double-compose) | §3/§4 | OK | маркер `sampler/PlatformManagedSampler` (`SafeSampler`/`CompositeSampler`) + `PlatformManagedSamplers.findComposite/isPlatformManaged`; `PlatformSamplerFactory` переиспользует composite для JMX |
| P15-05 SDK mode detection + double-SDK guard | §4 (диагностика) | OK | `autoconfigure/support/SdkMode`/`SdkModeResolver`/`SdkModeDiagnostics`; `TracingCoreAutoConfiguration` (лог + facade-vs-NoOp); секция `sdk` в `/actuator/tracing`; тесты `SdkModeResolverTest`, `SdkModeDetectionAutoConfigurationTest` |
| P15-06 «extension без Spring» инвариант | архитектурный | OK | ArchUnit `ExtensionNoSpringDependencyArchTest` |
| P15-07 упаковка/видимость 4 SPI в agent jar | §4 | OK | Gradle `verifyExtensionSpiRegistration`; e2e (gated) `PlatformSpiAgentSmokeTest` |
| P15-08 precedence-контракт + compatibility matrix | §1/§4 | OK | [ADR-config-precedence.md](../decisions/ADR-config-precedence.md); раздел «Extension SPI» в [otel-compatibility-matrix.md](./otel-compatibility-matrix.md); инвариант `ResourceMergeIntegrationTest.otel_service_name_побеждает_platform` |

ADR'ы Фазы 15:
- [ADR-named-spi-sampler-propagator.md](../decisions/ADR-named-spi-sampler-propagator.md)
- [ADR-sdk-mode-detection.md](../decisions/ADR-sdk-mode-detection.md)
- [ADR-config-precedence.md](../decisions/ADR-config-precedence.md)

## GAP-перечень (Фаза 16 — Collector boundary, закрыто)

| GAP | Требование | Статус | Реализация |
|---|---|---|---|
| P16-00 Track B+: pin Collector contrib 0.154.0 | reproducibility | OK | `validateCollectorConfigs` + Testcontainers + docs; spike-баseline на Gentoo; [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md) |
| P16-01 P0-баг forced-traces (`x_trace_on` → `force_header`) | §3 X-Trace-On = 100% retention | OK | gateway + e2e YAML `[force_header, qa_trace]`; баг доказан Spike S3 (Harness `/force`); e2e-регресс `CollectorProductionPolicyE2ETest.force_header_сохраняется_при_baseline_0_процентов` |
| P16-02 контракт SDK ↔ Collector | защита от рассинхронизации | OK | `PlatformSamplingReasons` (`platform-tracing-api`) + `CollectorPolicyContractTest` (`platform-tracing-collector-config/src/test`, JVM-only) |
| P16-03 redaction 2-я линия | §2.4 | OK | `redaction/platform-second-line` (blocked_values-only, `summary: info`) в gateway, до tail_sampling; Spike S2 GO |
| P16-04 k8sattributes agent-tier | ADR-resource-merge-precedence | OK | блок `k8sattributes` (pod IP matching) в agent YAML; RBAC — runbook |
| P16-05 TTL-tiers → routing connector | future-proofing + CI | OK | variant-b (transform→resource, `context: resource`, анти-фрагментация Spike S1); все 4 YAML в `validateCollectorConfigs` (`-PstrictValidation` в CI) |
| P16-06 e2e-паритет с production | §«Что тестировать» | OK | `CollectorProductionPolicyE2ETest` — production gateway YAML + env-overrides + feature gate recordpolicy (6 сценариев) |
| P16-07 resilience при недоступном Collector | §«Что тестировать» | OK | `CollectorUnavailableResilienceTest` — Agent + extension + dead OTLP endpoint; SLA probe < 2s, graceful exit |
| P16-08 операторский runbook | §10 теории | OK | [runbook/collector-pipeline-production.md](../runbook/collector-pipeline-production.md) (метрики/алерты/тюнинг/Head+Tail) |

ADR Фазы 16: [ADR-collector-boundary.md](../decisions/ADR-collector-boundary.md).

---

## Версионирование

Этот документ обновляется одновременно с изменениями в `Traces Requests.txt`. При появлении нового требования в авторитетном источнике добавляется строка в соответствующую секцию матрицы; при удалении — строка переносится в Out of Java scope или удаляется с пометкой «отменено».
