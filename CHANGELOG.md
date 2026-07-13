# Changelog

Все заметные изменения платформенного модуля трассировки фиксируются здесь.

Формат — [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), версии — [Semantic Versioning](https://semver.org/lang/ru/).

## [Unreleased] — Wave R1+ (dual-channel alignment)
### Changed (PR-B2 - scrubbing SPI naming)

- Pre-production SPI rename: `SensitiveDataRule` -> `SpanAttributeScrubbingRule`.
  Custom rule JARs must implement the new SPI and use
  `META-INF/services/space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule`.
  No compatibility alias or duplicate service descriptor is provided.
### Changed (PR-B1 - API context/propagation naming)

- Pre-production API rename slice: `TracingRequestContext` -> `RequestTraceContextSnapshot`,
  `TraceContextView` -> `ActiveTraceContextView`, `PlatformTraceControl` -> `InboundTraceControl`,
  `PlatformPropagationDecision` -> `OutboundPropagationDecision`, `PlatformOutboundInjector` ->
  `TraceControlHeaderInjector`, `RemoteContext` -> `TraceparentParser`, and builder
  `fromRemoteContext(...)` -> `fromTraceparent(...)`. No compatibility aliases were added.

### Fixed (Wave A — Scrubbing/PII boundary)

- **Defense-in-depth PII: app-side exception-events больше не утекают raw `exception.message`/
  `exception.stacktrace`.** `ExceptionRecorder` (секьюр-дефолт: message/stacktrace off) был
  спроектирован как guardrail, но фасад его обходил — `DefaultPlatformTracing.recordException`,
  `OwningSpanScope`, `NonOwningSpanScope` и `KafkaBatchLinksAspect` вызывали raw
  `Span.recordException(t)`, который пишет НЕскрабленный exception-event (events не скрабятся
  `ScrubbingSpanProcessor`'ом — только attributes). Все эти пути переведены на `ExceptionRecorder`.

### Changed (Wave A — Scrubbing/PII boundary)

- **Контракт exception-event'а на app-span'ах** (следствие fix выше): при секьюр-дефолте event
  `exception` теперь содержит только `exception.type` (FQN, безопасно); `exception.message`/
  `exception.stacktrace` публикуются лишь при явном включении `platform.tracing.semantic.exception.
  include-message`/`include-stacktrace`.
- **Status description**: `recordException` через фасад больше не пишет `simpleName` исключения в
  description статуса (секьюр-дефолт — без description; sanitized message — только при включённой
  policy). Дашборды, опиравшиеся на текст description, могут потребовать ревизии.
- На error-span'ах теперь проставляется semconv-атрибут `error.type` (FQN исключения).

### Added (Wave B — Measure / H1 baseline)

- **JMH baseline H1** (`CompositePipelineBenchmark`): расширен с 4 noop-делегатов до
  декомпозиции на 4 замера (noop-обвязка, prod-like attrs, real без scrubbing, full real).
  GC-профайлер (`gc.alloc.rate.norm`) включён в `platform-tracing-bench`. Результаты и выводы
  зафиксированы в `docs/tracing/h1-composite-pipeline-jmh-baseline.md`. **ProcessorContext
  refactor не делаем** — H1-slice (~2 µs, ~1.5 KB/span) умеренна; scrubbing доминирует
  (+11.7 µs, +15.7 KB/span).

### Added (Wave A — Scrubbing/PII boundary)

- **ArchUnit Rule 5** `NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER` (`OtelDirectIntegrationRules`):
  запрет raw `Span.recordException(..)` в platform-namespace — единственная легальная точка записи
  исключения — `ExceptionRecorder`. Подключено в core, autoconfigure, otel-extension.

### Fixed (Фаза 16 — Collector boundary)

- **P0: политика `forced-traces` ссылалась на несуществующее значение `x_trace_on`** —
  SDK никогда его не эмитит (фактический контракт: `force_header`). Forced-трейсы по
  `X-Trace-On` не имели гарантии retention на gateway (доказано эмпирически Spike S3
  на Gentoo: `GET /force` + `X-Trace-On: on` при baseline=0% → трейс дропался).
  Исправлено в `otel-collector-gateway-tail-sampling.yaml` и e2e-конфиге:
  `values: [force_header, qa_trace]`. `parent_sampled` сознательно не включён
  (решение архитектурного ревью: иначе forced-policy вырождается в keep-all).
- **docs drift**: `traceability.md` ссылался на несуществующие
  `otel-collector-config.yaml`/`attributes/scrubbing`/`transform/normalize` — приведено
  к фактическим артефактам.

### Added (Фаза 16 — Collector boundary)

- **`PlatformSamplingReasons`** (`platform-tracing-api`): канонические значения
  `platform.sampling.reason` (EXPORTED/DROPPED/метрические) — единый source of truth;
  правила `CompositeSampler` и метрики переведены на константы (строки не изменились).
- **`CollectorPolicyContractTest`** (`platform-tracing-collector-config/src/test`, JVM-only):
  машинный контракт SDK ↔ Collector YAML — ключи/значения string_attribute-политик,
  порядок процессоров (memory_limiter первый, batch последний, transform/redaction до
  tail_sampling), анти-фрагментация routing (только resource-атрибуты), отсутствие
  дрейфа e2e ↔ production.
- **`redaction/platform-second-line`** в gateway (2-я линия защиты sensitive data,
  Spike S2 GO): blocked_values-only (JWT/Bearer/e-mail/PAN), `summary: info`,
  до tail_sampling. Первая линия остаётся в SDK (`ScrubbingSpanProcessor`).
- **`k8sattributes`** на agent-tier (реализация ADR-resource-merge-precedence):
  pod-IP matching, метаданные `k8s.pod.name/namespace/node/uid`; RBAC — в runbook.
- **TTL-tiers → routing connector** (deprecated `routing` processor невалиден на пине
  0.154.0): схема variant-b Spike S1 — transform эскалирует failure-признак на resource,
  routing по `context: resource` (анти-фрагментация: `context: span` расщепляет
  multi-span трейс между backend'ами, доказано Harness `/nested-trace`).
- **`validateCollectorConfigs`** расширен на все 4 YAML (gateway, agent, ttl-tiers, e2e)
  + feature gate recordpolicy; в CI — `-PstrictValidation`. Env-синтаксис конфигов
  мигрирован на `${env:VAR:-default}` (легаси `${VAR:default}` ломает contrib ≥ 0.123).
- **e2e-паритет**: `CollectorProductionPolicyE2ETest` поднимает Collector с НАСТОЯЩИМ
  production gateway YAML (env-overrides только endpoint/decision_wait/проценты,
  feature gate `+processor.tailsamplingprocessor.recordpolicy`); 6 сценариев:
  health-drop/health-error/priority/backstop/redaction/forced (P0-регресс).
- **`CollectorUnavailableResilienceTest`**: Spring Boot + Agent + extension при мёртвом
  OTLP endpoint → READY, probe < 2s SLA, graceful exit, без OOM/SOE.
- **Runbook** `docs/runbook/collector-pipeline-production.md`: топология, порядок
  процессоров, env-тюнинг, метрики/алерты tail_sampling, паттерн Head+Tail для
  health-check'ов (риск R3), GitOps-процесс, чек-лист on-call.
- **ADR-collector-boundary** дополнен boundary-контрактом: таблица ответственности,
  минимальный контракт атрибутов, иерархия каналов конфигурации, anti-patterns,
  правило spanmetrics-before-sampling, trade-off head sampling vs error retention.
- **Track B+ (PR-0.5)**: pin Collector contrib 0.110.0 → **0.154.0** во всех артефактах
  (validateCollectorConfigs, Testcontainers, e2e YAML, SUPPORTED) + e2e-регресс на Gentoo.

### Added (Фаза 15 — OpenTelemetry autoconfigure & extension SPI)

- **Named sampler SPI `platform`** (`ConfigurableSamplerProvider`): `otel.traces.sampler=platform`
  поднимает платформенный `CompositeSampler` через общий `PlatformSamplerBuilder` (force/QA/drop/route/ratio
  из `ConfigProperties`, runtime-ratio через тот же `SamplerStateHolder`/JMX). Дефолтом **не** ставится —
  compose-over-existing остаётся default (`ADR-sampler-compose`); named — явный opt-in.
- **Named propagator SPI `platform-trace-control`** (`ConfigurablePropagatorProvider`):
  `otel.propagators=...,platform-trace-control` делает `InboundTraceControlPropagator` discoverable.
  Дефолт дописывается **ENV-aware** через `addPropertiesCustomizer` (не `addPropertiesSupplier`) — корректно
  при задании `otel.propagators` через ENV. Always-append из `addPropagatorCustomizer` удалён.
- **Idempotency-guard sampler** через маркер `PlatformManagedSampler` (`SafeSampler`/`CompositeSampler`):
  при `otel.traces.sampler=platform` inline-customizer не оборачивает повторно (без двойной композиции),
  переиспользует `CompositeSampler`/`SamplerStateHolder` для JMX. Маркер stateless — корректен при
  многократной autoconfigure-сборке в одном JVM (без статического флага).
- **Reusable builders** (framework-agnostic, единый источник истины inline ↔ named SPI):
  `PlatformSamplerBuilder`, `InboundTraceControlPropagatorBuilder`.
- **SDK mode detection** (`platform.tracing.sdk.mode`: `AUTO|AGENT|STARTER|EXTERNAL|DISABLED`):
  `SdkModeResolver` + `SdkModeDiagnostics`, лог на старте, секция `sdk` в `/actuator/tracing`. Диагностика и
  явность — **не** создание SDK (agent-first). `NoOpPlatformTracing` — только для `DISABLED`; в остальных
  режимах фасад делегирует в `GlobalOpenTelemetry`/пользовательский `OpenTelemetry` bean.
- **ArchUnit-инвариант** `ExtensionNoSpringDependencyArchTest`: `platform-tracing-otel-extension` не зависит
  от `org.springframework..` (грузится `ExtensionClassLoader`'ом без Spring).
- **Gradle `verifyExtensionSpiRegistration`**: проверяет, что все 4 SPI (`META-INF/services` + класс) реально
  упакованы в self-contained `agentExtensionJar`.
- **e2e (gated) `PlatformSpiAgentSmokeTest`**: агент + extension + `-Dotel.traces.sampler=platform` +
  `-Dotel.propagators=...,platform-trace-control` → span с `force_header` при ratio=0 (подтверждает видимость
  named SPI из `ExtensionClassLoader`; fail-fast).
- **ADR**: `ADR-named-spi-sampler-propagator.md`, `ADR-sdk-mode-detection.md`, `ADR-config-precedence.md`;
  раздел «Extension SPI» в `docs/tracing/otel-compatibility-matrix.md`.

### Added (Фаза 14 — Dynamic Configuration: runtime-mutable policy)

- **Policy vs topology**: изменяемая в рантайме «policy» (sampling ratio/route-ratios/drop/force,
  scrubbing enable+rules, validation enable/strict, export-gate, platform-propagation gate,
  diagnostic log-level) отделена от startup-only «topology» (exporter endpoint, цепочка процессоров
  и пропагаторов). См. `ADR-runtime-config-policy-vs-topology.md`.
- **`DomainConfigHolder<T extends Versioned>`** (`platform-tracing-api/config`): lock-free
  атомарный апдейт неизменяемых версионированных снимков через `AtomicReference`+CAS с
  last-known-good (LKG) откатом при `null`/исключении из builder'а/валидатора. Контракт
  side-effect-free для `UnaryOperator`/`Predicate`. Используется sampler/scrubbing/validation.
- **Per-domain holders + immutable snapshots**: `SamplerState` (с пред-скомпилированными
  `traceIdRatioBased` сэмплерами — без аллокаций на hot-path), `ScrubbingSnapshot`
  (пред-скомпилированные правила, ReDoS-защита через `PatternSyntaxException`→LKG),
  `ValidationSnapshot` (enabled/strict).
- **Атомарные JMX-апдейты «один домен = один вызов»**: `updateSamplingPolicy(...)`,
  `updateScrubbingPolicy(...)`, `updateValidationPolicy(...)` — публикуют согласованный снимок без
  промежуточных неполных состояний.
- **Granular kill-switches (политика, не топология)**: export-gate в `SafeSpanExporter`
  (выключение экспорта без поломки propagation; счётчики `gated`/`export_enabled`), shared
  `PlatformPropagationGate` (платформенные заголовки; W3C-контекст Агента не затрагивается),
  facade no-op через `DefaultPlatformTracing.setFacadeEnabled(false)` (+`NoOpSpanScope`).
- **Dynamic log level**: app-CL — штатный Spring Boot `/actuator/loggers`; agent/bootstrap-CL —
  `PlatformLogControl` (shared) + JMX `setPlatformLogLevel(...)`, через который
  `RateLimitedLogger` гейтит диагностику платформы (rate-limited, сама смена уровня — тоже).
- **Config-reload observability**: `ConfigReloadDiagnostics` (счётчики applied/rejected,
  источник/время последнего изменения, bounded audit-trail) → JMX (`ConfigReloadMetrics`,
  `ConfigAuditTrail`) → polling `PlatformTracingConfigMetricsBinder`
  (`platform.tracing.config.*`) + секция `config` в `/actuator/tracing`.
- **`RuntimeConfigApplier`** (Spring, тонкий): на `RefreshScopeRefreshedEvent` читает свежие
  значения из refresh-scoped `TracingProperties` (без stale-снимка) и пушит изменяемые домены в
  агент one-call-per-domain (best-effort, per-domain изоляция ошибок). Actuator POST расширен
  изменяемыми полями: `exportEnabled`, `propagationEnabled`, `logLevel`.
- **Startup-init из agent `ConfigProperties`**: начальные значения sampling-политики читаются на
  старте агента (`platform.sampling.enabled`, per-route ratios), закрывая startup-window до первого
  refresh.
- **`TracingProperties`** дополнен: `facade.enabled`, `sampling.enabled`, `sampling.route-ratios`,
  `exporter.enabled`, `propagation.enabled`, `validation.strict`, `diagnostics.log-level`.

### Added (Фаза 13 — Typed Span API + Semantic Layer)

- **Typed escape-hatch span builders** на фасаде `PlatformTracing`: `httpServerSpan()`,
  `httpClientSpan()`, `databaseSpan()`, `rpcServerSpan()`, `rpcClientSpan()`, `kafkaProducerSpan()`,
  `kafkaConsumerSpan()`, `internalSpan()`. Agent-first: предназначены для операций вне покрытия
  OTel Java Agent. API-интерфейсы в `platform-tracing-api/span/builder` с `default`-сеттерами,
  policy-backed реализации (`*Impl`) в `platform-tracing-core`, `Facade*` fallback в api.
- **Семантический слой governance**: `CategoryContract`/`CategoryContracts`
  (`platform-tracing-api/semconv`) — единственный источник истины (allowlist / required /
  requiredAnyOf / forbidden) для runtime-policy и статического линтера; `SemconvKeys` —
  типобезопасные `AttributeKey`-константы.
- **`AttributePolicy`** (runtime-валидация и нормализация атрибутов) с режимами `ValidationMode`
  (`STRICT` / `WARN` / `DISABLED`), метриками `platform.tracing.semconv.violations` и
  `platform.tracing.unsafe_attributes{key_class}`. Дефолт прод — `WARN`, test/CI — `STRICT`
  (через `SemconvStrictTestAutoConfiguration` в `platform-tracing-test`; бин `ValidationMode`
  имеет приоритет над property).
- **Anti-double instrumentation (модель B)**: platform-маркер категории в OTel Context
  (`PlatformSpanContextKeys`) + degradation builder'а в enrich при re-entry той же категории;
  ArchUnit `TracingArchRules.ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION` + аннотация
  `@SuppressAgentInstrumentation` как defense-in-depth.
- **`SpanEnricher`**: `enrichCurrentSpan` (`GenericEnrichScope` — только platform-safe:
  requestId/userHash/result/businessTag) и `enrichCurrentSpanIfPlatformCategory` (`EnrichScope` —
  категорийное обогащение с allowlist-валидацией).
- **`ExceptionRecorder` + `ExceptionMessagePolicy`**: ручная сборка sanitized exception-event;
  `exception.message`/`exception.stacktrace` **off-by-default** (опт-ин), закрывает gap «scrubbing
  не покрывал span events».
- **Санитайзеры в api**: `UrlSanitizer` (redaction userinfo/query) и `SqlSanitizer` (литералы → `?`)
  перенесены из core в `platform-tracing-api/span/sanitize`, применяются lazy в builder'ах.
- ADR: `ADR-typed-span-api-semantic-layer.md`, `ADR-semconv-validation-modes.md`,
  `ADR-semconv-governance-weaver.md` (открытый вопрос Weaver-codegen — отложен на SRE-встречу).

### Changed (Фаза 13 — разделение RPC/messaging категорий)

- `SpanCategory.RPC` разделён на `RPC_SERVER` / `RPC_CLIENT`; добавлены `KAFKA_PRODUCER` /
  `KAFKA_CONSUMER`. `PlatformTracing.startRpc(...)` → `startRpcServer(...)` / `startRpcClient(...)`.
  `EnrichingSpanProcessor` маппит `SpanKind.PRODUCER`→`kafka_producer`, `CONSUMER`→`kafka_consumer`.
  Решение не было в проде — переход чистый, без migration-compat. `semconv-mapping.md` обновлён.

### Added (Фаза 12 — Custom propagation для HTTP и Kafka, agent-compatible)

- **Policy-driven outbound propagation** платформенных заголовков (`X-Trace-On`, `X-QA-Trace`,
  `X-Request-Id`) на доверенные HTTP-хосты и Kafka-топики. Платформа НЕ создаёт span'ы и НЕ
  инжектит W3C `traceparent`/`tracestate` (зона OTel Java Agent) — добавляет только policy-слой.
  Secure-by-default: `platform.tracing.propagation.outbound.enabled=false`.
- **Единый источник истины outbound-контрактов в `platform-tracing-api`**: `TrustedDestinationMatcher`
  (перенесён из agent-модуля; hardening — host canonicalization, label-aware glob, запрет IP-литералов),
  `OutboundPropagationPolicy`, `TraceControlHeaderInjector`, `RequestIdSupport`.
  `InboundTraceControlPropagator.inject()` делегирует в `TraceControlHeaderInjector`.
- **HTTP outbound**: `PlatformOutboundHttpInterceptor` (RestTemplate/RestClient, Servlet-стек),
  `PlatformOutboundExchangeFilterFunction` (WebClient, Reactive-стек, Reactor Context bridge).
- **Kafka outbound**: `PlatformKafkaProducerInterceptor` + `PlatformKafkaHeaderSetter`,
  policy через producer-config map. Режим `platform.tracing.kafka.mode=agent-compatible|disabled`.
- **`propagateForceTrace=false` по умолчанию**: решение о записи переносит sampled-flag в `traceparent`
  (parent-based sampling downstream); проброс `X-Trace-On` — opt-in escape hatch.
- `ADR-outbound-propagation.md`, `ADR-request-id-correlation-id.md`.

### Changed (Фаза 12 — семантика X-Request-Id)

- **`X-Request-Id` = edge-stable correlation id** (входящий валидируется и форвардится без изменений;
  при отсутствии — UUIDv4), **НЕ** trace-id. Авторитетный trace-id — в `X-Trace-Id`. Инвариант
  `request_id ≠ trace_id`. `ADR-request-id-equals-trace-id` **superseded** (решение не было в проде —
  переход чистый, без migration-compat). Security: reject-and-regenerate невалидных значений (CWE-113).
- `TracingProperties.Propagation.Outbound.propagateToExternal` -> `enabled` (master switch);
  добавлены `propagate-force-trace`/`propagate-qa-trace`/`propagate-request-id`/`allow-ip-literals`.

### Added (Фаза 10 — Export pipeline: SafeSpanExporter + наблюдаемость экспорта)

- **`SafeSpanExporter`** — тонкая защитная обёртка вокруг транспортного OTLP-экспортёра:
  любой `Throwable` делегата конвертируется в `CompletableResultCode.ofFailure()` (исключение
  не покидает export pipeline), rate-limited логирование, счётчики наблюдаемости. Семантика
  строгая: `failures` считаются на вызов (batch), `dropped`/`exported` — на span. Обёртывание —
  в `PlatformExportProcessorFactory.captureExporter` (применяется и к `PlatformDropOldestExportSpanProcessor`,
  и к stock BSP в режиме UPSTREAM). Паттерн `MultiSpanExporter` из OTel Java, без копирования
  исходников (гард — ArchUnit `OtelDirectIntegrationRules`).
- **Наблюдаемость export pipeline** через JMX `PlatformTracingControlMBean`
  (`getExportFailuresTotal`, `getExportTimeoutsTotal`, `getExportDroppedOverflowTotal`,
  `getExportDroppedAfterShutdownTotal`, `getExportQueueCapacity`/`getExportQueueSize`,
  `getSafeExporterMetrics`) и секция `export` в `GET /actuator/tracing`. Late-binding:
  export-компоненты создаются позже регистрации MBean — `PlatformTracingControl` читает их лениво
  через холдеры `PlatformTracingJmxRegistrar`. При отсутствии MBean actuator грациозно отдаёт
  `export: { status: "not_ready" }` (без HTTP 500).
- **`OtelEnvHintsBuilder`**: добавлены подсказки `OTEL_EXPORTER_OTLP_ENDPOINT`,
  `OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED` и `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY`.
- **Collector `tail_sampling`**: обязательная политика `platform-high-priority`
  (`key=platform.trace.priority`, `values=[high]`) в `otel-collector-gateway-tail-sampling.yaml` —
  Collector теперь учитывает бизнес-приоритет, вычисляемый `ClassificationSpanProcessor`.
- `ADR-safe-span-exporter-v1.md` — решение Conservative: SafeSpanExporter + сознательный отказ
  от SDK-side `BackpressureAwareExporter`/`CircuitBreaker` (429/503 — на OTLP+Collector) и
  `PrioritySpanProcessor` (приоритизация — на Collector tail-sampling, во избежание orphaned spans).

### Changed (OTel train bump: SDK 1.61.0 → 1.62.0, Instrumentation/Agent 2.27.0 → 2.28.1)

- `gradle.properties`: согласованный поезд OTel — SDK BOM `1.62.0`, instrumentation BOM `2.28.1`,
  alpha `2.28.1-alpha`. Re-validated findings: BSP drop-new (probe), SPI autoconfigure
  инварианты, DB semconv default (legacy `db.system`), full e2e 26/26 на Gentoo Docker.
- DevOps: runtime Java Agent синхронизирован с `openTelemetryInstrumentationBomVersion`
  (см. [docs/tracing/otel-compatibility-matrix.md](docs/tracing/otel-compatibility-matrix.md)).

### Security & reliability (автоматические выигрыши OTel 1.62.0)

- **Security #8378 (GHSA-rcgg-9c38-7xpx):** лимиты W3C baggage (`MAX_BAGGAGE_ENTRIES=64`,
  `MAX_BAGGAGE_BYTES=8192`) на входящем `extract()` теперь активны и наследуются
  `FilteringBaggagePropagator` через делегирование stock-пропагатору — защита от unbounded-парсинга
  недоверенного `baggage`-заголовка. Зафиксировано regression-тестом
  `FilteringBaggagePropagatorBaggageLimitsTest` и заметкой в
  [ADR-baggage-filtering-spike-finding.md](docs/decisions/ADR-baggage-filtering-spike-finding.md).
- **Reliability:** OTLP `JdkHttpSender` с bounded thread pool (#8276); fix race при shutdown
  `PeriodicMetricReader` (#8299); оптимизация аллокации `parentContext` на span start (#8332).
  Изменений кода не требуют — выигрыш на стороне SDK/агента.
- Оценка применимости 1.62.0 (что N/A) — в
  [docs/tracing/otel-compatibility-matrix.md](docs/tracing/otel-compatibility-matrix.md), секция «1.62.0 impact assessment».

### Added (Context-First Propagation & v1.0 Compliance)
- **`InboundTraceControlPropagator`**: Извлечение платформенных управляющих заголовков (`X-Trace-On`, `X-QA-Trace`, `X-Request-Id`) перенесено из анти-паттерна "HTTP Attributes" в OTel Context API.
- **`FilteringBaggagePropagator`**: Защита от утечки PII (пароли, токены) через механизм `Baggage` при исходящих запросах (outbound isolation).
- **`KafkaBatchLinksAspect`**: (Opt-in) Создание корректных связей (span links) для каждого сообщения при `batch`-обработке Kafka. Включается через `platform.tracing.kafka.batch-links-enabled=true`.
- **MDC Correlation ID**: Автоматическое сохранение `X-Request-Id` во входящий MDC (`correlation_id`).
- `ADR-context-first-propagation.md` — зафиксированы решения по Context-first модели.

### Changed
- **`CompositeSampler` (Breaking Internal)**: Полностью переписан на Context-first API. Больше не зависит от `otel.instrumentation.http.server.capture-request-headers`. Форсированное сэмплирование по заголовкам теперь работает единообразно для HTTP и Kafka.
- **Ужесточение `X-Trace-On`**: По умолчанию платформенный стартер принимает **только** значение `on` для активации принудительной записи (настраивается через `force-record-values`).
- Декомпозиция `PlatformAutoConfigurationCustomizer` на отдельные фабрики для удобства сопровождения.

### Added (v1.x preview — opt-in DROP_OLDEST export processor)

- **`PlatformDropOldestExportSpanProcessor`** — custom export-processor (BSP-lite) с
  гарантированной политикой `DROP_OLDEST` при переполнении: bounded `ArrayDeque` под
  `ReentrantLock`, daemon worker, batch-export по `maxExportBatchSize` или `scheduleDelay`,
  изолированная обработка ошибок exporter'а, lifecycle-методы (`forceFlush`/`shutdown`) с
  bounded `exportTimeout`, счётчики `droppedSpansOverflow` / `droppedSpansAfterShutdown` /
  `exportFailures` / `exportTimeouts`.
- **SPI wiring (default = DROP_OLDEST)**: `PlatformAutoConfigurationCustomizer` через
  `addSpanExporterCustomizer` + `addSpanProcessorCustomizer` подменяет стандартный
  `BatchSpanProcessor` на платформенный. Default v1.x — `DROP_OLDEST` (§2.5 требований);
  для возврата к stock BSP задайте явно `platform.tracing.queue.overflow-policy=UPSTREAM`
  (или env-var `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM`).
  Multi-exporter сценарий → WARN + fallback на стоковый BSP. После замены стоковый BSP
  явно `shutdown()`-ится — нет утечки worker-потока и double-export.
- `DropOldestAspirationDiagnostics` — startup-WARN если оператор явно задал
  `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM` при Spring `queue.policy=DROP_OLDEST`;
  когда политики совпадают (default или явный DROP_OLDEST) — optional INFO (aligned).
  Управление: `platform.tracing.diagnostics.drop-oldest-aspiration-{warn,info}` (default `true`).
- `ADR-drop-oldest-export-processor-v1.md` — архитектурное решение, SPI spike (8/8 GO),
  контракт processor'а, opt-in rationale; раздел `v1.x outcome` в
  `ADR-bsp-overflow-policy-finding.md`.
- Тесты: `BspReplacementSpikeTest` (SPI go/no-go gate, 11 кейсов), контракт-тесты
  `PlatformDropOldestExportSpanProcessor{OverflowPolicy,Lifecycle,BuilderValidation}Test`,
  customizer-тест `PlatformAutoConfigurationCustomizerExportProcessorTest`,
  `DropOldestAspirationDiagnosticsTest`, `BspDropOldestNoDoubleExportTest` (in-extension
  counting exporter), e2e `BspDropOldestSafetyAgentSmokeTest`.

### Changed

- **Default platform.tracing.queue.export-timeout changed from 100 ms to 5000 ms**
  to align with OTel agent extension/BSP default
  (`PlatformTracingDefaultsProvider` / `OtelSdkDefaults`).

  Это **behavior change for default configuration, not API breaking change**. YAML-ключ
  и Java API (`TracingProperties.Queue#getExportTimeout`) не изменились; меняется только
  значение по умолчанию, если пользователь не задал его явно. Сделано для устранения
  расхождения dual-channel (Spring vs OTel extension SPI) — теперь и Spring-сторона, и
  agent-сторона возвращают одинаковое значение в `/actuator/tracing`
  (`source: default-platform`). Подробнее: [docs/MIGRATION.md](docs/MIGRATION.md),
  [docs/decisions/ADR-dual-channel-properties-v0.1.md](docs/decisions/ADR-dual-channel-properties-v0.1.md).

### Added

- `SharedDefaultsAlignmentTest` — targeted alignment-контракт по whitelist shared values
  между `TracingProperties` и agent extension defaults (BSP queue + span limits). Не universal
  drift-test — только перечисленный набор пар.
- `DualChannelDriftDiagnostics` — startup-WARN при расхождении Spring и effective OTel
  значений по whitelist shared properties (три AND-условия, см. ADR), управляется
  `platform.tracing.diagnostics.dual-channel-warn` (default `true`).
- `ADR-dual-channel-properties-v0.1.md` — решение по dual-channel lifecycle (Spring vs Agent),
  отказ от full bridge и universal drift-test.
- `BatchSpanProcessorOverflowPolicyProbeTest` + `ADR-bsp-overflow-policy-finding.md` — SDK 1.61.0
  drop-new (не drop-oldest); требование смягчено до `bounded-queue-with-drop`.
- `BspOverflowSafetyAgentSmokeTest` — e2e safety при overflow + unavailable OTLP endpoint.
- `DuplicateSpansRegressionMatrixTest` (Servlet/WebFlux) + `DuplicateHttpSpanAgentSmokeTest`
  (subprocess WARN-smoke, `-PrunE2e`).
- `AgentHttpSpringSmokeProcessRunner` — универсальный subprocess launcher для HTTP smoke-тестов.

## [0.1.1-SNAPSHOT] — Wave 2c/2d (2026-05-25)

### Changed

- `micrometer-tracing-bridge-otel`: `api` → `compileOnly` в
  `platform-tracing-spring-boot-autoconfigure`. Production path (Agent + suppress)
  не требует bridge; для dev/staging SDK-only path — явный opt-in (см.
  [docs/MIGRATION.md](docs/MIGRATION.md)).

### Added

- `docs/runbook/mdc-logging-production.md`, `docs/runbook/actuator-tracing-diagnostics.md`.
- `docs/SUPPORTED.md` logging checklist.
