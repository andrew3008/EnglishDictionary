# ADR: подавление Micrometer HTTP observations при OTel Java Agent

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-05-23 |
| Контекст | Step 9.5 (D2, D7, D8), `WebMvcSuppressMicrometerTracingAutoConfiguration` |
| Стек | Spring Boot **3.5.5**, OTel Java Agent **2.27.x** |

## Проблема

При одновременном использовании OTel Java Agent и Spring WebMVC/WebFlux + `micrometer-tracing-bridge-otel` возникают **дублирующие HTTP span'ы** (Agent на уровне Tomcat/Netty + Micrometer Observation).

## Отвергнутые альтернативы

| Подход | Почему нет |
|--------|------------|
| `management.tracing.enabled=false` | **Broken в SB 3.2+** — property сохранён, но не отключает propagation/context; только для тестов |
| Auto-override через `EnvironmentPostProcessor` | Анти-паттерн для platform library (non-invasive Spring Boot) |
| Отключение Agent HTTP instrumentation | Ломает bytecode-level coverage; не platform decision |

## Решение

**Opt-in property:** `platform.tracing.suppression.suppress-micrometer-tracing=true`

Реализация:

- `WebMvcSuppressMicrometerTracingAutoConfiguration` — Servlet stack
- `WebFluxSuppressMicrometerTracingAutoConfiguration` — Reactive stack
- `ObservationRegistryCustomizer` + `ObservationPredicate` с **`instanceof`** по stack-specific `*ObservationContext`
- `@AutoConfiguration`, `@AutoConfigureAfter(ObservationAutoConfiguration.class)`
- `@ConditionalOnMissingBean(name = "platformMvcHttpObservationSuppressor")` — override hook для потребителей

### Роли компонентов (архитектурное разделение)

| Компонент | Ответственность |
|-----------|-----------------|
| **OTel Java Agent** | HTTP **spans** (SERVER/CLIENT), bytecode instrumentation |
| **Micrometer Observation** | Application-level observations, `@Observed`, conventions |
| **Platform suppress** | Убирает дублирующие HTTP observations когда Agent уже создаёт spans |

### D8: suppress без detected agent

Property **уважается** даже если `OtelAgentDetector.isAgentPresent() == false`:

- Легитимные кейсы: OTel Spring Boot Starter (без `-javaagent`), Zipkin native, staging
- One-shot WARN логируется при старте (см. validator tests)

`OtelAgentDetector` — package-private в `autoconfigure.support`, pattern `ClassUtils.isPresent("io.opentelemetry.javaagent.OpenTelemetryAgent", null)`.

## Trade-off (честное описание, W1/W4)

`ObservationPredicate → false` превращает observation в `Observation.NOOP`:

- **Не создаются** Micrometer tracing spans ✓
- **Не вызываются** metrics/tracing `ObservationHandler`'ы для этих observations
- Метрики `http.server.requests` / `http.client.requests` через тот же pipeline **исчезают**

**Следствие:** при `suppress=true` HTTP-метрики должны поступать из **OTel Agent metrics pipeline**, не из Micrometer HTTP observations.

Тест `SuppressMicrometerTracingMetricsTest` фиксирует отсутствие `http.server.requests` при suppress=true.

## Конфигурация (reference)

```yaml
platform:
  tracing:
    suppression:
      suppress-micrometer-tracing: true   # рекомендация для OTel Agent окружений
```

## Связанные ADR

- [ADR-response-headers.md](./ADR-response-headers.md) — response headers остаются на platform filters, не на Micrometer

## Связанные артефакты

- `TracingProperties.Suppression`
- `WebMvcSuppressMicrometerTracingAutoConfiguration.java`
- `OtelAgentDetector.java`
- `SuppressMicrometerTracingMetricsTest.java`
