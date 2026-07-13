# Semconv mapping: platform-tracing ↔ OpenTelemetry

Документ фиксирует соответствие платформенных enum/атрибутов семантическим конвенциям OpenTelemetry.
Cross-reference: Javadoc `SpanCategory`, `SpanResult`, `PlatformAttributes`.

## SpanCategory ↔ OTel SpanKind

| SpanCategory | OTel SpanKind | `platform.trace.type` | Рекомендуемые semconv attrs |
|--------------|---------------|----------------------|-----------------------------|
| HTTP_SERVER | SERVER | `http_server` | `http.request.method`, `http.response.status_code`, `http.route` |
| HTTP_CLIENT | CLIENT | `http_client` | `url.full`, `server.address`, `http.response.status_code` |
| DATABASE | CLIENT | `database` | `db.system.name` (stable) или `db.system` (legacy), `db.operation.name` |
| RPC_SERVER | SERVER | `rpc_server` | `rpc.system`, `rpc.service`, `rpc.method` |
| RPC_CLIENT | CLIENT | `rpc_client` | `rpc.system`, `rpc.service`, `rpc.method` |
| KAFKA_PRODUCER | PRODUCER | `kafka_producer` | `messaging.system`, `messaging.destination.name`, `messaging.operation` |
| KAFKA_CONSUMER | CONSUMER | `kafka_consumer` | `messaging.system`, `messaging.destination.name`, `messaging.operation` |
| INTERNAL | INTERNAL | `internal` | — |

> **Фаза 13 (typed span API).** Прежняя единая категория `RPC` разделена на `RPC_SERVER`/`RPC_CLIENT`
> (синхронный RPC) и `KAFKA_PRODUCER`/`KAFKA_CONSUMER` (messaging). Источник истины по требуемым/
> запрещённым атрибутам каждой категории — реестр `CategoryContracts`
> (`platform-tracing-api`, пакет `semconv`), а не этот документ. Таблица — человекочитаемое зеркало.

### RPC / messaging SpanKind (M-02)

OpenTelemetry различает **PRODUCER** и **CONSUMER** для messaging/RPC:

| SpanKind | Когда использовать | Примеры | Typed builder |
|----------|-------------------|---------|---------------|
| **PRODUCER** | Отправка сообщения в брокер / исходящий RPC без ожидания ответа в том же span | Kafka producer send, gRPC client streaming write | `kafkaProducerSpan()` |
| **CONSUMER** | Получение и обработка сообщения / входящий RPC handler на стороне сервиса | `@KafkaListener`, gRPC service method | `kafkaConsumerSpan()` |
| **SERVER** | Входящий синхронный RPC handler | gRPC unary service method | `rpcServerSpan()` |
| **CLIENT** | Синхронный request-response RPC (HTTP, unary gRPC с блокирующим вызовом) | RestTemplate, blocking gRPC stub | `rpcClientSpan()` |

Для HTTP используйте SERVER/CLIENT (`httpServerSpan()`/`httpClientSpan()`) — не PRODUCER/CONSUMER.

> Все builder'ы — **escape-hatch** (agent-first): по умолчанию сетевые/db/messaging span'ы создаёт
> OTel Java Agent. Ручное создание оправдано лишь там, где Агент не покрывает операцию; такие места
> помечаются `@SuppressAgentInstrumentation` (страхуется ArchUnit-правилом
> `TracingArchRules.ESCAPE_HATCH_BUILDERS_REQUIRE_SUPPRESSION`).

Guidance для потребителей:

- Kafka consumer → spanKind **CONSUMER** (или link-based batch processing — см. [links-kafka.md](./tracing/links-kafka.md)).
- Kafka producer → spanKind **PRODUCER**.
- gRPC server → **SERVER** (unary) или **CONSUMER** (streaming receive), по semconv instrumentation.
- gRPC client → **CLIENT** (unary) или **PRODUCER** (fire-and-forget).

### Reverse mapping (auto-instrumentation → platform.trace.type)

`EnrichingSpanProcessor` на `onStart` выставляет `platform.trace.type` по `SpanKind`:

| SpanKind | Default `platform.trace.type` |
|----------|-------------------------------|
| SERVER | `http_server` |
| CLIENT | `http_client` |
| PRODUCER | `kafka_producer` |
| CONSUMER | `kafka_consumer` |
| INTERNAL | `internal` |

На `onEnding` для `SpanKind.CLIENT` выполняется уточнение:

- если присутствует `db.system.name` **или** `db.system` → `platform.trace.type=database` (если не переопределено прикладным кодом)

Подробности spike: [ADR-db-semconv-detection.md](./decisions/ADR-db-semconv-detection.md).

### Удалено в v1.0

| Было | Статус |
|------|--------|
| `SpanCategory.PLATFORM` | **Удалён** — использовать `INTERNAL` |

## SpanResult ↔ OTel StatusCode

| SpanResult | OTel StatusCode | `platform.trace.result` |
|------------|-----------------|-------------------------|
| SUCCESS | OK (или unset) | `success` |
| FAILURE | ERROR | `failure` |
| TIMEOUT | ERROR | `timeout` |
| CANCELLED | ERROR | `cancelled` |
| REJECTED | ERROR | `rejected` |
| SKIPPED | OK | `skipped` |

`EnrichingSpanProcessor` выставляет `platform.trace.result` на `onEnding`, если не задан явно.
`SpanWatchdogProcessor` при force-close выставляет `platform.trace.result=timeout` и `platform.trace.timeout=span|trace`.

## Resource attrs (Wave 2 rename)

| Было | Станет | Слой |
|------|--------|------|
| `platform.environment` | `deployment.environment.name` | Resource |
| `platform.host` | `host.name` | Resource |

См. [ADR-platform-resource-override.md](./decisions/ADR-platform-resource-override.md).

## Sampling reason

| Значение `platform.sampling.reason` | Источник |
|-------------------------------------|----------|
| `force_header` | `CompositeSampler` — X-Trace-On |
| `qa_header` | `CompositeSampler` — X-QA-Trace |
| `ratio` | Head sampling без valid parent |
| `parent` | Parent span sampled |
| `parent_drop` | Parent span not sampled (delegate path only) |

Атрибут optional; semconv-lint — WARNING при нестандартном значении.

## MDC keys (v1 breaking)

| Ключ | Semconv / стандарт |
|------|-------------------|
| `traceId` | W3C Trace Context / Micrometer Tracing |
| `spanId` | W3C Trace Context / Micrometer Tracing |
| `traceFlags` | W3C `traceflags` |
| `correlation_id` | Platform (без изменений) |
| `platform.remote.service` | Platform attribute mirror |

## Exception recording roadmap

Контекст: OTel Semconv переводит exception recording со span events на **logs signal** (industry trend 2025+).

| Версия | Поведение | Конфигурация Agent |
|--------|-----------|-------------------|
| **v1.0** | Span **events** с `exception.*` attrs (legacy) | Agent 2.28.x default |
| **v1.1** | Рассмотреть dual mode: events + logs | `OTEL_SEMCONV_EXCEPTION_SIGNAL_OPT_IN=dup` после bump Agent ≥ 2.30 |
| **v2.0+** | Переход на logs signal | `OTEL_SEMCONV_EXCEPTION_SIGNAL_OPT_IN=logs` |

Platform `TraceOperations.recordException()` в v1.0 делегирует в OTel Span API (`recordException`) — поведение наследуется от Agent/SDK без platform-specific fork.

**Backlog issue (v1.1):** «Evaluate OTEL_SEMCONV_EXCEPTION_SIGNAL_OPT_IN migration» — оценить impact на scrubbing rules (`exception.message`, stacktrace) и log correlation.

## Семантическая валидация (Фаза 13)

Семантический слой проверяет атрибуты span'ов против `CategoryContracts` в трёх режимах
(`ValidationMode`):

| Режим | Поведение при нарушении | Где по умолчанию |
|-------|-------------------------|------------------|
| `STRICT` | бросает `SemconvViolationException` | test / CI (`SemconvStrictTestAutoConfiguration`) |
| `WARN` | логирует (rate-limited) + метрика `platform.tracing.semconv.violations`, span создаётся | прод (дефолт) |
| `DISABLED` | валидация выключена (аварийный opt-out), пишется метрика-сигнал | только по `disabled-reason` |

Конфигурация: `platform.tracing.semantic.validation-mode`, `platform.tracing.semantic.disabled-reason`,
`platform.tracing.semantic.allow-unsafe-attributes`. Подробности и обоснование отказа от «мягкого»
LENIENT — в [ADR-semconv-validation-modes.md](./decisions/ADR-semconv-validation-modes.md).

`unsafeAttribute(String, String)` (строковый escape-hatch вне типобезопасного allowlist) аудируется
метрикой `platform.tracing.unsafe_attributes{key_class}` и в `STRICT` запрещён, если
`allow-unsafe-attributes=false`.

## Enrichment текущего span'а (Фаза 13)

`SpanEnricher` обогащает уже активный span (в т.ч. созданный Агентом) без создания нового:

- `enrichCurrentSpan(...)` — только платформенно-безопасные атрибуты (`GenericSpanEnrichment`:
  `requestId`, `userHash`, `result`, `businessTag`); произвольные `AttributeKey` недоступны.
- `enrichCurrentSpanIfPlatformCategory(category, ...)` — категорийное обогащение (`SpanEnrichment`),
  применяется только если активный span помечен platform-маркером той же категории; атрибуты
  проходят allowlist-валидацию `AttributePolicy`.

## Запись исключений (Фаза 13)

`ExceptionRecorder` формирует sanitized exception-event вручную (а не делегирует «как есть» в
`Span.recordException`), управляется `ExceptionMessagePolicy`:

| Свойство | Дефолт | Назначение |
|----------|--------|------------|
| `platform.tracing.semantic.exception.include-message` | `false` | `exception.message` (обрезается, опт-ин) |
| `platform.tracing.semantic.exception.include-stacktrace` | `false` | `exception.stacktrace` (опт-ин) |

Секьюр-дефолт: ни message, ни stacktrace в span не попадают, пока явно не включены — устранён
gap, при котором `ScrubbingSpanProcessor` чистил атрибуты, но не events.

## Связанные ADR

- [ADR-typed-span-api-semantic-layer.md](./decisions/ADR-typed-span-api-semantic-layer.md)
- [ADR-semconv-validation-modes.md](./decisions/ADR-semconv-validation-modes.md)
- [ADR-semconv-governance-weaver.md](./decisions/ADR-semconv-governance-weaver.md)
- [ADR-db-semconv-detection.md](./decisions/ADR-db-semconv-detection.md)
- [ADR-platform-resource-override.md](./decisions/ADR-platform-resource-override.md)
- [ADR-suppress-micrometer-tracing.md](./decisions/ADR-suppress-micrometer-tracing.md)
