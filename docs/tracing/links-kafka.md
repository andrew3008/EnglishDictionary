# Span Links: Kafka batch consumer

Руководство по связыванию span'ов при batch-обработке сообщений (Kafka и аналоги).

## Проблема

Batch consumer получает N сообщений за один poll. Каждое сообщение может нести собственный
W3C trace context в headers, но обработка выполняется одним вызовом listener'а.

Parent-child иерархия здесь не подходит: нельзя выбрать «главное» сообщение как parent.
OpenTelemetry рекомендует **links** — один processing span со ссылками на span'ы сообщений.

Industry ref: [opentelemetry-java-instrumentation#3922](https://github.com/open-telemetry/opentelemetry-java-instrumentation/pull/3922).

## API платформы

| Метод | Назначение |
|-------|------------|
| `startRootSpan` | Точка входа batch listener без входящего W3C context |
| `startSpanWithLinks` | Processing span с N links за один вызов |
| `addLink` | Добавить link на активный span (инкрементально) |
| `RemoteSpanLink` | DTO: traceId, spanId, traceFlags, traceState (optional) |

`SpanRelation.DETACHED` создаёт span без родителя; links **не** добавляются автоматически.

## Паттерн 1: links при создании span

```java
List<RemoteSpanLink> links = records.stream()
        .map(this::extractLinkFromHeaders)
        .flatMap(Optional::stream)
        .toList();

try (SpanScope scope = traceOperations.startSpanWithLinks(
        "kafka.batch.process", SpanCategory.RPC, links)) {
    for (ConsumerRecord<?, ?> record : records) {
        processRecord(record);
    }
    scope.setResult(SpanResult.SUCCESS);
}
```

## Паттерн 2: root span + инкрементальные links

```java
try (SpanScope scope = traceOperations.startRootSpan(
        "kafka.batch.process", SpanCategory.RPC)) {
    for (ConsumerRecord<?, ?> record : records) {
        extractLinkFromHeaders(record).ifPresent(traceOperations::addLink);
        processRecord(record);
    }
    scope.setResult(SpanResult.SUCCESS);
}
```

## Извлечение link из Kafka headers

```java
Optional<RemoteSpanLink> extractLinkFromHeaders(ConsumerRecord<?, ?> record) {
    Header traceparent = record.headers().lastHeader("traceparent");
    if (traceparent == null) {
        return Optional.empty();
    }
    // W3C traceparent: version-trace_id-span_id-flags
    String[] parts = new String(traceparent.value(), UTF_8).split("-");
    if (parts.length < 4) {
        return Optional.empty();
    }
    byte flags = (byte) Integer.parseInt(parts[3], 16);
    return Optional.of(new RemoteSpanLink(parts[1], parts[2], flags, null));
}
```

## SpanRelation: когда что использовать

| Сценарий | Метод |
|----------|-------|
| Обычная вложенная операция | `startChildSpan` / `startSpan` |
| Scheduled job, нет входящего context | `startRootSpan` |
| Batch без parent, links вручную | `startDetachedSpan` + `addLink` |
| Batch, все links известны заранее | `startSpanWithLinks` |

## Связанные документы

- [semconv-mapping.md](../semconv-mapping.md) — `SpanCategory.RPC` для brokered протоколов
- `TraceOperations` Javadoc — контракт default methods и compile-safety
