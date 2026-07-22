# ADR: Context-first propagation & Platform Tracing V1 Compliance

| Поле | Значение |
|------|----------|
| Статус | **Принято** |
| Дата | 2026-06-04 |
| Компоненты | `platform-tracing-api`, `platform-tracing-otel-javaagent-extension`, `platform-tracing-spring-boot-autoconfigure` |
| Связанные ADR | `ADR-sampler-compose.md`, `ADR-baggage-filtering-spike-finding.md` |

## Контекст и Проблема
До этого ADR платформенные управляющие сигналы (такие как сэмплирование по заголовкам `X-Trace-On`, `X-QA-Trace` и drop paths) реализовывались как анти-паттерн:
1. OTel Java Agent захватывал HTTP заголовки как атрибуты span'а (`http.request.header.x_trace_on`).
2. Платформенный сэмплер читал эти атрибуты.
Это привязывало логику трассировки к транспортному слою (HTTP), не работало для Kafka и нарушало W3C Trace Context стандарты. Кроме того, отсутствовала фильтрация чувствительных данных в исходящем `baggage`.

## Решение (Context-First)
Рефакторинг `platform-tracing` в соответствие с требованиями "Фазы 3":

1. **TextMapPropagator для управляющих заголовков**: Внедрён `PlatformTraceControlPropagator`, который извлекает `X-Trace-On`, `X-QA-Trace` и `X-Request-Id` из carrier и помещает их в `OpenTelemetry Context` (объект `PlatformTraceControl`).
2. **Context-first Sampler**: `CompositeSampler` переведён на использование `PlatformTraceControl` из `Context`. Полностью удалена зависимость от `http.request.header.*`.
3. **Outbound Baggage Filtering**: Разработан `FilteringBaggagePropagator`, который безопасно фильтрует исходящие ключи `baggage`, исключая случайную утечку PII (`password`, `token`, `secret`).
4. **Outbound Propagation Decision**: Решение о передаче платформенных заголовков (`X-Trace-On`, `X-QA-Trace`, `X-Request-Id`) в другие сервисы принимается на стороне клиента (WebClient, RestTemplate, Kafka) на основании паттернов доверенных хостов.
5. **Kafka Batch Context Links**: Для обработки `ConsumerRecord` батчами добавлен `KafkaBatchLinksAspect`, который создаёт корректные span links для каждого отдельного сообщения.
6. **MDC и Correlation ID**: Заголовок `X-Request-Id` обрабатывается как Correlation ID, а не как Trace ID. Он переносится в MDC (`correlation_id`) и дублируется в span-атрибут `platform.request_id` с жестким ограничением (anti-cardinality explosion guardrail) для исключения его из метрик.

## Impact Section (Влияние на существующие системы)

**ВНИМАНИЕ: Breaking Internal Behavior (Pre-Production v1.0)**

- **Ужесточение X-Trace-On**: Дефолтное значение для активации принудительной записи теперь **только `on`**. Значения `1` и `true` убраны из списка по умолчанию (могут быть возвращены через `platform.tracing.sampling.force-record-header-values`).
- **Сэмплирование не читает span attributes**: `CompositeSampler` больше не проверяет `http.request.header.x_trace_on`. Если пользователь использует кастомные propagator'ы без `PlatformTraceControlPropagator`, форсированная запись не сработает.
- **Capture Request Headers**: `otel.instrumentation.http.server.capture-request-headers` более не навязывается со стороны `platform-tracing` по умолчанию.
- **Высококардинальный request_id**: `platform.request_id` зафиксирован как high-cardinality атрибут. Его использование в OTel Metrics (как dimension) строго запрещено и может привести к cardinality explosion.
