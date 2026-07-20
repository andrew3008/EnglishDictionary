# Platform Tracing — справочник конфигурации autoconfigure-модулей

| Поле | Значение |
|------|----------|
| **Модули** | `platform-tracing-spring-boot-autoconfigure`, `platform-tracing-autoconfigure-webmvc`, `platform-tracing-autoconfigure-webflux` |
| **Версия анализа** | v0.1.0 / v3 (agent-first) |
| **Дата** | 2026-07-12 |
| **Источник** | `TracingProperties`, `OtelEnvHintsBuilder`, `OtelEffectiveConfigSnapshot`, `ResourceEffectiveSnapshot`, `docs/SUPPORTED.md` |

---

## Архитектура конфигурации

Три модуля делят роли так:

| Модуль | Роль |
|--------|------|
| `platform-tracing-spring-boot-autoconfigure` | Единый источник свойств — `TracingProperties` (`platform.tracing.*`), core-бины, actuator, Kafka, async |
| `platform-tracing-autoconfigure-webmvc` | Servlet: фильтр `X-Request-Id`, Observation conventions, outbound для `RestTemplate`/`RestClient` |
| `platform-tracing-autoconfigure-webflux` | Reactive: `WebFilter`, Observation conventions, outbound для `WebClient`, Reactor context |

WebMvc/WebFlux **не добавляют** своих `@ConfigurationProperties` — только условные бины по тем же `platform.tracing.*`.

Решение **agent-first**: Spring YAML управляет UX-слоем (фасад, фильтры, actuator), а OTel Agent читает **`OTEL_*` / `-Dotel.*`** в отдельном classloader. Spring-значения **не пробрасываются** в Agent автоматически (dual-channel, см. `OtelEnvHintsBuilder` и runbook `/actuator/tracing`).

Связанные документы:

- [SUPPORTED.md](../SUPPORTED.md)
- [runbook/actuator-tracing-diagnostics.md](../runbook/actuator-tracing-diagnostics.md)
- [ADR-dual-channel-properties-v0.1.md](../decisions/ADR-dual-channel-properties-v0.1.md)
- [MIGRATION.md](../MIGRATION.md)

---

## Обязательные параметры

### «Обязательные для старта» vs «обязательные для production»

В коде **нет** `@NotNull` / fail-fast на `TracingProperties` — модуль стартует с дефолтами (`enabled=true`, sampling 0.1, OTLP endpoint `http://otel-collector:4317` и т.д.).

Для **production** (по `docs/SUPPORTED.md`) обязательны:

#### 1. JVM / Agent (не `application.yml`)

```text
-javaagent:/path/to/opentelemetry-javaagent.jar
-Dotel.javaagent.extensions=/path/to/platform-tracing-otel-extension-*-agent.jar
-Dotel.instrumentation.logback-mdc.enabled=false
```

#### 2. `application.yml` (минимальный production-контракт)

```yaml
spring:
  application:
    name: my-service          # strongly recommended → service.name

platform:
  tracing:
    enabled: true
    suppression:
      suppress-micrometer-tracing: true   # обязательно с OTel Agent
```

#### 3. Переменные окружения для экспорта и идентичности (Helm/K8s)

| Переменная | Зачем |
|------------|-------|
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Куда Agent шлёт traces (Spring default не попадает в Agent) |
| `OTEL_SERVICE_NAME` **или** `PLATFORM_TRACING_SERVICE_NAME` **или** `spring.application.name` | Логическое имя сервиса в backend |
| `PLATFORM_TRACING_SERVICE_ENVIRONMENT` | Среда (`dev`/`stage`/`prod`) — рекомендуется для fleet |

**Резолв имени сервиса** (`PlatformLocalServiceNameProvider`):

1. `platform.tracing.service.name`
2. `spring.application.name`
3. Fallback: `unknown_service`

#### 4. Условно обязательные

| Условие | Что задать |
|---------|------------|
| `platform.tracing.propagation.outbound.enabled=true` | `trusted-host-patterns` (иначе outbound inject не имеет смысла) |
| `platform.tracing.kafka.propagate-platform-headers=true` | `trusted-topic-patterns` |
| `platform.tracing.semantic.validation-mode=DISABLED` | `disabled-reason` (желателен для аудита) |
| WebMvc vs WebFlux | Зависимость `starter-servlet` или `starter-reactive` (не property, но обязательный выбор стека) |

---

## Рекомендуемый `application.yml` для микросервиса

### Production (Servlet + Agent)

```yaml
spring:
  application:
    name: orders-service

platform:
  tracing:
    enabled: true
    service:
      environment: prod
      version: ${BUILD_VERSION:unknown}
    suppression:
      suppress-micrometer-tracing: true
    # опционально — если переопределяете Spring-side sampling (Agent — source of truth через JMX)
    sampling:
      ratio: 0.1
```

### Production (WebFlux + Agent)

Тот же YAML; модуль `platform-tracing-autoconfigure-webflux` активируется автоматически при `spring.main.web-application-type=reactive`.

### Dev без Agent (не production-standard)

```yaml
platform:
  tracing:
    enabled: true
    suppression:
      suppress-micrometer-tracing: false
# + явный opt-in micrometer-tracing-bridge-otel (см. docs/MIGRATION.md)
```

---

## Полная таблица переменных окружения

Формат Spring Boot relaxed binding: `platform.tracing.x.y-z` → `PLATFORM_TRACING_X_Y_Z`.

### A. Spring / платформа (`PLATFORM_TRACING_*` + `SPRING_*`)

| YAML (`platform.tracing.*`) | Переменная окружения | Default | Обяз. | Канал | Описание |
|----------------------------|----------------------|---------|-------|-------|----------|
| — | `SPRING_APPLICATION_NAME` | — | **Prod: да** | Spring | Fallback для `service.name` |
| `enabled` | `PLATFORM_TRACING_ENABLED` | `true` | Нет | Spring | Глобальный выключатель |
| `sdk.mode` | `PLATFORM_TRACING_SDK_MODE` | `AGENT` | Нет | Spring (ownership) | `AGENT\|DISABLED`; обязано согласовываться с `enabled` |
| **service** | | | | | |
| `service.name` | `PLATFORM_TRACING_SERVICE_NAME` | — | **Prod: да*** | Spring + Agent bridge | Логическое имя сервиса |
| `service.version` | `PLATFORM_TRACING_SERVICE_VERSION` | — | Рек. | Spring + Agent bridge | `service.version` в resource |
| `service.environment` | `PLATFORM_TRACING_SERVICE_ENVIRONMENT` | — | **Prod: да** | Spring + Agent bridge | `deployment.environment.name` |
| `service.c-group` | `PLATFORM_TRACING_SERVICE_C_GROUP` | — | Нет | Spring + Agent bridge | Орг. группа (C-Group) |
| `service.container-id` | `PLATFORM_TRACING_SERVICE_CONTAINER_ID` | — | Нет | Spring | `container.id` (обычно из k8sattributes) |
| **resource** | | | | | |
| `resource.policy-version` | `PLATFORM_TRACING_RESOURCE_POLICY_VERSION` | — | Нет | Agent bridge | Версия resource-policy |
| `resource.normalize-environment` | `PLATFORM_TRACING_RESOURCE_NORMALIZE_ENVIRONMENT` | `true` | Нет | Spring | Нормализация environment |
| `resource.validation-mode` | `PLATFORM_TRACING_RESOURCE_VALIDATION_MODE` | `LENIENT` | Нет | Spring | `LENIENT\|STRICT` |
| `resource.detect-container-id` | `PLATFORM_TRACING_RESOURCE_DETECT_CONTAINER_ID` | `false` | Нет | Spring | Opt-in procfs-detect |
| **facade** | | | | | |
| `facade.enabled` | `PLATFORM_TRACING_FACADE_ENABLED` | `true` | Нет | Spring (runtime) | Kill-switch фасада |
| **sampling** | | | | | |
| `sampling.enabled` | `PLATFORM_TRACING_SAMPLING_ENABLED` | `true` | Нет | Spring → Agent JMX | Kill-switch sampler |
| `sampling.ratio` | `PLATFORM_TRACING_SAMPLING_RATIO` | `0.1` | Нет | Spring → Agent JMX | Head sampling ratio |
| `sampling.route-ratios` | `PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS` | `{}` | Нет | Spring → Agent JMX | Per-route ratios (map) |
| `sampling.force-record-header` | `PLATFORM_TRACING_SAMPLING_FORCE_RECORD_HEADER` | `X-Trace-On` | Нет | Spring | Имя force-header |
| `sampling.force-record-header-values` | `PLATFORM_TRACING_SAMPLING_FORCE_RECORD_HEADER_VALUES` | `on` | Нет | Spring → Agent JMX | Значения force-header |
| `sampling.qa-force-header` | `PLATFORM_TRACING_SAMPLING_QA_FORCE_HEADER` | `X-QA-Trace` | Нет | Spring | QA 100% header |
| `sampling.drop-paths` | `PLATFORM_TRACING_SAMPLING_DROP_PATHS` | actuator paths | Нет | Spring → Agent JMX | Path-префиксы для DROP |
| **limits** | | | | | |
| `limits.max-attributes` | `PLATFORM_TRACING_LIMITS_MAX_ATTRIBUTES` | `50` | Нет | Spring hint → `OTEL_*` | Max attrs/span |
| `limits.max-attribute-value-length` | `PLATFORM_TRACING_LIMITS_MAX_ATTRIBUTE_VALUE_LENGTH` | `1000` | Нет | Spring hint → `OTEL_*` | Max длина значения |
| `limits.max-events` | `PLATFORM_TRACING_LIMITS_MAX_EVENTS` | `10` | Нет | Spring hint → `OTEL_*` | Max events/span |
| `limits.span-timeout` | `PLATFORM_TRACING_LIMITS_SPAN_TIMEOUT` | `30s` | Нет | Spring | Watchdog span timeout |
| `limits.trace-timeout` | `PLATFORM_TRACING_LIMITS_TRACE_TIMEOUT` | `60s` | Нет | Spring | Watchdog trace timeout |
| **queue** | | | | | |
| `queue.max-size` | `PLATFORM_TRACING_QUEUE_MAX_SIZE` | `2048` | Нет | Spring hint → `OTEL_*` | BSP queue size |
| `queue.policy` / `overflow-policy` | `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY` | `DROP_OLDEST` | Нет | **Agent** | `DROP_OLDEST\|UPSTREAM` |
| `queue.export-batch-size` | `PLATFORM_TRACING_QUEUE_EXPORT_BATCH_SIZE` | `512` | Нет | Spring hint → `OTEL_*` | BSP batch size |
| `queue.export-timeout` | `PLATFORM_TRACING_QUEUE_EXPORT_TIMEOUT` | `5000ms` | Нет | Spring hint → `OTEL_*` | BSP export timeout (ms) |
| **scrubbing** | | | | | |
| `scrubbing.enabled` | `PLATFORM_TRACING_SCRUBBING_ENABLED` | `true` | Нет | Spring → Agent JMX | PII scrubbing |
| `scrubbing.built-in-rules` | `PLATFORM_TRACING_SCRUBBING_BUILT_IN_RULES` | 12 rules | Нет | Spring → Agent JMX | Список правил |
| `scrubbing.rules-config` | `PLATFORM_TRACING_SCRUBBING_RULES_CONFIG` | — | Нет | Agent | Путь к `.properties` |
| **exporter** | | | | | |
| `exporter.enabled` | `PLATFORM_TRACING_EXPORTER_ENABLED` | `true` | Нет | Spring (runtime) | Export kill-switch |
| `exporter.otlp.endpoint` | `PLATFORM_TRACING_EXPORTER_OTLP_ENDPOINT` | `http://otel-collector:4317` | Нет | Spring hint → `OTEL_*` | OTLP endpoint |
| `exporter.otlp.retry.enabled` | `PLATFORM_TRACING_EXPORTER_OTLP_RETRY_ENABLED` | `true` | Нет | Spring hint | Retry toggle |
| `exporter.otlp.retry.initial-backoff` | `PLATFORM_TRACING_EXPORTER_OTLP_RETRY_INITIAL_BACKOFF` | `1s` | Нет | Spring | Initial backoff |
| `exporter.otlp.retry.max-backoff` | `PLATFORM_TRACING_EXPORTER_OTLP_RETRY_MAX_BACKOFF` | `30s` | Нет | Spring | Max backoff |
| **response** (WebMvc + WebFlux) | | | | | |
| `response.expose-request-id-header` | `PLATFORM_TRACING_RESPONSE_EXPOSE_REQUEST_ID_HEADER` | `true` | Нет | Spring | `X-Request-Id` в ответе |
| `response.header-name` | `PLATFORM_TRACING_RESPONSE_HEADER_NAME` | `X-Request-Id` | Нет | Spring | Имя заголовка ответа |
| **service-names** | | | | | |
| `service-names.record-remote-on-client-error` | `PLATFORM_TRACING_SERVICE_NAMES_RECORD_REMOTE_ON_CLIENT_ERROR` | `true` | Нет | Spring | `platform.remote.service` на ERROR |
| `service-names.remote-attribute-priority` | `PLATFORM_TRACING_SERVICE_NAMES_REMOTE_ATTRIBUTE_PRIORITY` | peer/rpc/server | Нет | Spring | Приоритет атрибутов |
| `service-names.ignore-server-address-if-ip` | `PLATFORM_TRACING_SERVICE_NAMES_IGNORE_SERVER_ADDRESS_IF_IP` | `true` | Нет | Spring | Игнор IP как имени |
| **aop** | | | | | |
| `aop.mode` | `PLATFORM_TRACING_AOP_MODE` | `ENRICH_CURRENT` | Нет | Spring | `@Traced` режим |
| **suppression** (WebMvc + WebFlux) | | | | | |
| `suppression.suppress-micrometer-tracing` | `PLATFORM_TRACING_SUPPRESSION_SUPPRESS_MICROMETER_TRACING` | `false` | **Prod+Agent: да** | Spring | Подавление дублей HTTP spans |
| **enriching** | | | | | |
| `enriching.enabled` | `PLATFORM_TRACING_ENRICHING_ENABLED` | `true` | Нет | Agent (mirror) | EnrichingSpanProcessor |
| `enriching.remote-service-priority` | `PLATFORM_TRACING_ENRICHING_REMOTE_SERVICE_PRIORITY` | peer/rpc/server | Нет | Agent (mirror) | Приоритет upstream |
| **validation** | | | | | |
| `validation.enabled` | `PLATFORM_TRACING_VALIDATION_ENABLED` | `true` | Нет | Spring → Agent JMX | ValidatingSpanProcessor |
| `validation.strict` | `PLATFORM_TRACING_VALIDATION_STRICT` | `false` | Нет | Spring → Agent JMX | Strict mode |
| `validation.strict-runtime-allowed` | `PLATFORM_TRACING_VALIDATION_STRICT_RUNTIME_ALLOWED` | `false` | Нет | Agent (startup) | Разрешить runtime strict |
| **semantic** | | | | | |
| `semantic.validation-mode` | `PLATFORM_TRACING_SEMANTIC_VALIDATION_MODE` | `WARN` | Нет | Spring | `WARN\|STRICT\|DISABLED` |
| `semantic.disabled-reason` | `PLATFORM_TRACING_SEMANTIC_DISABLED_REASON` | — | Cond. | Spring | При `DISABLED` |
| `semantic.allow-unsafe-attributes` | `PLATFORM_TRACING_SEMANTIC_ALLOW_UNSAFE_ATTRIBUTES` | `false` | Нет | Spring | Escape-hatch attrs |
| `semantic.exception.include-message` | `PLATFORM_TRACING_SEMANTIC_EXCEPTION_INCLUDE_MESSAGE` | `false` | Нет | Spring | Publ. exception.message |
| `semantic.exception.include-stacktrace` | `PLATFORM_TRACING_SEMANTIC_EXCEPTION_INCLUDE_STACKTRACE` | `false` | Нет | Spring | Publ. stacktrace |
| **watchdog** | | | | | |
| `watchdog.enabled` | `PLATFORM_TRACING_WATCHDOG_ENABLED` | `true` | Нет | Agent | SpanWatchdogProcessor |
| `watchdog.scan-interval` | `PLATFORM_TRACING_WATCHDOG_SCAN_INTERVAL` | `5s` | Нет | Agent | Интервал сканирования |
| **propagation** | | | | | |
| `propagation.enabled` | `PLATFORM_TRACING_PROPAGATION_ENABLED` | `true` | Нет | Spring (runtime) | Платформенные заголовки |
| `propagation.platform-headers.force-trace-header` | `PLATFORM_TRACING_PROPAGATION_PLATFORM_HEADERS_FORCE_TRACE_HEADER` | `X-Trace-On` | Нет | Spring | Force trace header |
| `propagation.platform-headers.qa-trace-header` | `PLATFORM_TRACING_PROPAGATION_PLATFORM_HEADERS_QA_TRACE_HEADER` | `X-QA-Trace` | Нет | Spring | QA header |
| `propagation.platform-headers.request-id-header` | `PLATFORM_TRACING_PROPAGATION_PLATFORM_HEADERS_REQUEST_ID_HEADER` | `X-Request-Id` | Нет | Spring | Request ID header |
| **propagation.outbound** (WebMvc: RestClient/RestTemplate; WebFlux: WebClient) | | | | | |
| `propagation.outbound.enabled` | `PLATFORM_TRACING_PROPAGATION_OUTBOUND_ENABLED` | `false` | Cond. | Spring | Outbound inject (secure default off) |
| `propagation.outbound.trusted-host-patterns` | `PLATFORM_TRACING_PROPAGATION_OUTBOUND_TRUSTED_HOST_PATTERNS` | `[]` | **Cond.** | Spring | Glob доверенных хостов |
| `propagation.outbound.allow-ip-literals` | `PLATFORM_TRACING_PROPAGATION_OUTBOUND_ALLOW_IP_LITERALS` | `false` | Нет | Spring | Разрешить IP в trusted |
| `propagation.outbound.propagate-force-trace` | `PLATFORM_TRACING_PROPAGATION_OUTBOUND_PROPAGATE_FORCE_TRACE` | `false` | Нет | Spring | Проброс `X-Trace-On` |
| `propagation.outbound.propagate-qa-trace` | `PLATFORM_TRACING_PROPAGATION_OUTBOUND_PROPAGATE_QA_TRACE` | `false` | Нет | Spring | Проброс `X-QA-Trace` |
| `propagation.outbound.propagate-request-id` | `PLATFORM_TRACING_PROPAGATION_OUTBOUND_PROPAGATE_REQUEST_ID` | `true` | Нет | Spring | Проброс `X-Request-Id` |
| **propagation.mdc** | | | | | |
| `propagation.mdc.put-request-id` | `PLATFORM_TRACING_PROPAGATION_MDC_PUT_REQUEST_ID` | `true` | Нет | Spring | X-Request-Id → MDC |
| `propagation.mdc.request-id-key` | `PLATFORM_TRACING_PROPAGATION_MDC_REQUEST_ID_KEY` | `correlation_id` | Нет | Spring | MDC key |
| **propagation.baggage** | | | | | |
| `propagation.baggage.enabled` | `PLATFORM_TRACING_PROPAGATION_BAGGAGE_ENABLED` | `true` | Нет | Agent (mirror) | Baggage filter |
| `propagation.baggage.allowed-keys` | `PLATFORM_TRACING_PROPAGATION_BAGGAGE_ALLOWED_KEYS` | 3 keys | Нет | Agent (mirror) | Allowlist |
| `propagation.baggage.deny-patterns` | `PLATFORM_TRACING_PROPAGATION_BAGGAGE_DENY_PATTERNS` | password/secret/token | Нет | Agent (mirror) | Deny patterns |
| **kafka** | | | | | |
| `kafka.batch-links-enabled` | `PLATFORM_TRACING_KAFKA_BATCH_LINKS_ENABLED` | `false` | Нет | Spring | Batch @KafkaListener links |
| `kafka.mode` | `PLATFORM_TRACING_KAFKA_MODE` | `agent-compatible` | Нет | Spring | `agent-compatible\|disabled` |
| `kafka.propagate-platform-headers` | `PLATFORM_TRACING_KAFKA_PROPAGATE_PLATFORM_HEADERS` | `false` | Cond. | Spring | Producer header inject |
| `kafka.trusted-topic-patterns` | `PLATFORM_TRACING_KAFKA_TRUSTED_TOPIC_PATTERNS` | `[]` | **Cond.** | Spring | Glob доверенных топиков |
| **context-propagation.async** | | | | | |
| `context-propagation.async.enabled` | `PLATFORM_TRACING_CONTEXT_PROPAGATION_ASYNC_ENABLED` | `false` | Нет | Spring (bootstrap) | @Async TaskDecorator |
| `context-propagation.async.mode` | `PLATFORM_TRACING_CONTEXT_PROPAGATION_ASYNC_MODE` | `propagate-current-context` | Нет | Spring | Режим propagation |
| **diagnostics** | | | | | |
| `diagnostics.dual-channel-warn` | `PLATFORM_TRACING_DIAGNOSTICS_DUAL_CHANNEL_WARN` | `true` | Нет | Spring | WARN при drift Spring↔Agent |
| `diagnostics.drop-oldest-aspiration-warn` | `PLATFORM_TRACING_DIAGNOSTICS_DROP_OLDEST_ASPIRATION_WARN` | `true` | Нет | Spring | WARN DROP_OLDEST mismatch |
| `diagnostics.drop-oldest-aspiration-info` | `PLATFORM_TRACING_DIAGNOSTICS_DROP_OLDEST_ASPIRATION_INFO` | `true` | Нет | Spring | INFO DROP_OLDEST status |
| `diagnostics.log-level` | `PLATFORM_TRACING_DIAGNOSTICS_LOG_LEVEL` | — | Нет | Spring → Agent JMX | Уровень логов platform.* |
| **actuator** | | | | | |
| `actuator.mutation-enabled` | `PLATFORM_TRACING_ACTUATOR_MUTATION_ENABLED` | `false` | Нет | Spring | POST `/actuator/tracing/*` |

\* Если не задано — используйте `SPRING_APPLICATION_NAME`; иначе в backend будет `unknown_service`.

---

### B. OpenTelemetry Agent (`OTEL_*`) — production-critical

Эти переменные читает **Agent**, не Spring. Для синхронизации с YAML смотрите секцию `otelEnvHints` в `GET /actuator/tracing`.

| Переменная | YAML-аналог (hint) | Default (platform) | Обяз. | Описание |
|------------|-------------------|-------------------|-------|----------|
| `OTEL_SERVICE_NAME` | `platform.tracing.service.name` | из resource | **Prod: да*** | `service.name` |
| `OTEL_RESOURCE_ATTRIBUTES` | — | — | Нет | Доп. resource attrs (`key=value,...`) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `platform.tracing.exporter.otlp.endpoint` | `http://otel-collector:4317` | **Prod: да** | OTLP gRPC/HTTP endpoint |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | — | SDK default | Нет | `grpc` / `http/protobuf` |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | — | SDK default | Нет | Export timeout |
| `OTEL_EXPORTER_OTLP_HEADERS` | — | — | Cond. | Auth headers (`authorization=Bearer ...`) |
| `OTEL_JAVA_EXPORTER_OTLP_RETRY_DISABLED` | `exporter.otlp.retry.enabled` (инверсия) | `false` | Нет | Отключение retry |
| `OTEL_BSP_MAX_QUEUE_SIZE` | `queue.max-size` | `2048` | Нет | BSP queue |
| `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` | `queue.export-batch-size` | `512` | Нет | BSP batch |
| `OTEL_BSP_EXPORT_TIMEOUT` | `queue.export-timeout` | `5000` (ms) | Нет | BSP export timeout |
| `OTEL_BSP_SCHEDULE_DELAY` | — | `5000` (ms) | Нет | BSP schedule delay |
| `OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` | `limits.max-attributes` | `50` | Нет | Span attr limit |
| `OTEL_SPAN_ATTRIBUTE_VALUE_LENGTH_LIMIT` | `limits.max-attribute-value-length` | `1000` | Нет | Attr value length |
| `OTEL_SPAN_EVENT_COUNT_LIMIT` | `limits.max-events` | `10` | Нет | Events/span |
| `OTEL_SPAN_LINK_COUNT_LIMIT` | — | `32` | Нет | Links/span |
| `OTEL_ATTRIBUTE_COUNT_LIMIT` | — | `16` | Нет | Global attr limit |
| `OTEL_TRACES_SAMPLER` | — | compose-over-existing | Нет | Opt-in: `platform` |
| `OTEL_TRACES_SAMPLER_ARG` | `sampling.ratio` | — | Нет | Sampler argument |
| `OTEL_PROPAGATORS` | — | auto + `platform-trace-control` | Нет | W3C + platform control |
| `OTEL_JAVAAGENT_ENABLED` | — | `true` | Нет | Kill-switch agent |
| `OTEL_JAVAAGENT_EXTENSIONS` | — | — | **Prod: да** | Путь к platform extension JAR |

---

### C. JVM system properties (часто через `JAVA_TOOL_OPTIONS`)

| System property | ENV-эквивалент | Обяз. (prod) | Описание |
|-----------------|----------------|--------------|----------|
| `-javaagent:...` | — | **Да** | OTel Java Agent |
| `otel.javaagent.extensions` | `OTEL_JAVAAGENT_EXTENSIONS` | **Да** | Platform extension |
| `otel.instrumentation.logback-mdc.enabled=false` | — | **Да** | Не ломать camelCase MDC |
| `otel.instrumentation.messaging.experimental.receive-telemetry.enabled=true` | — | Рек. (Kafka) | Consumer links |
| `otel.instrumentation.experimental.span-suppression-strategy=semconv` | — | Рек. | Anti double-instrumentation |
| `otel.traces.sampler=platform` | `OTEL_TRACES_SAMPLER` | Нет | Явный platform sampler |

---

## Пример Helm env для production

```yaml
env:
  - name: SPRING_APPLICATION_NAME
    value: orders-service
  - name: PLATFORM_TRACING_SUPPRESSION_SUPPRESS_MICROMETER_TRACING
    value: "true"
  - name: PLATFORM_TRACING_SERVICE_ENVIRONMENT
    value: prod
  - name: OTEL_SERVICE_NAME
    value: orders-service
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: "http://otel-collector.observability:4317"
  - name: OTEL_BSP_MAX_QUEUE_SIZE
    value: "2048"
  - name: JAVA_TOOL_OPTIONS
    value: >-
      -javaagent:/otel/opentelemetry-javaagent.jar
      -Dotel.javaagent.extensions=/otel/platform-tracing-otel-extension-agent.jar
      -Dotel.instrumentation.logback-mdc.enabled=false
```

---

## Практические выводы

1. **Strictly required для старта Spring** — ничего из `platform.tracing.*` (всё с дефолтами).
2. **Strictly required для рабочего production tracing** — Agent JAR + extension, `suppress-micrometer-tracing=true`, имя сервиса, реальный `OTEL_EXPORTER_OTLP_ENDPOINT`.
3. **WebMvc vs WebFlux** — различаются только starter-модулем; свойства одинаковые, outbound inject включается одним флагом `propagation.outbound.enabled`.
4. **Dual-channel** — меняя только `application.yml`, вы не меняете BSP/limits/exporter в Agent; выравнивайте через `OTEL_*` или смотрите `GET /actuator/tracing` → `otelEnvHints` / `otelEffective`.

---

## Диагностика после деплоя

```http
GET /actuator/tracing
```

Ключевые секции:

| Секция | Назначение |
|--------|------------|
| `sdk` | Режим SDK (`AGENT` / `STARTER` / `DISABLED`), `agentDetected` |
| `otelEffective` | Фактически применённые OTel-значения (Agent classloader) |
| `otelEnvHints` | Рекомендуемые `OTEL_*` / `PLATFORM_TRACING_*` из текущего Spring-конфига |
| `resourceEffective` | Drift resource-идентичности между каналами |

Подробнее: [runbook/actuator-tracing-diagnostics.md](../runbook/actuator-tracing-diagnostics.md).
