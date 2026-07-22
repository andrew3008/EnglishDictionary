# ADR: Baggage outbound filtering — spike findings (PR-2a)

| Поле | Значение |
|------|----------|
| Статус | **Принято (go для PR-2)** |
| Дата | 2026-06-04 |
| Spike | `BaggageFilteringSpikeTest` (9/9), `spike/baggage/*` |
| Стек | OTel SDK autoconfigure **1.61.0**, SPI `addPropagatorCustomizer` |
| Re-validated | **SDK 1.62.0**: security fix #8378 (GHSA-rcgg-9c38-7xpx) применяет лимиты W3C baggage (`MAX_BAGGAGE_ENTRIES=64`, `MAX_BAGGAGE_BYTES=8192`) на `extract()`. `FilteringBaggagePropagator.extract()` делегирует stock-классу → лимиты наследуются автоматически, новый код не требуется. Regression: `FilteringBaggagePropagatorBaggageLimitsTest` (3/3) |

## Проблема

`BaggageSpanProcessor` фильтрует baggage→span attributes на входе, но stock `W3CBaggagePropagator` при исходящем `inject()` может утечь PII (`password`, `token`) во все downstream carriers.

## Метод spike

In-process тесты на реальном `AutoConfiguredOpenTelemetrySdk` + ручные composite propagator'ы. Исходники: `PropagatorConfiguration` (javap) — порядок вызова customizer'а.

## Findings

### F1. `addPropagatorCustomizer` — per-propagator, не на composite

`PropagatorConfiguration` для каждого имени из `otel.propagators`:

1. `propagator = getPropagator(name)`
2. `propagator = customizer.apply(propagator, config)`
3. Добавляет в `Set`, затем `TextMapPropagator.composite(set)`

**Следствие:** в callback приходит **один** propagator (`W3CTraceContextPropagator` или `W3CBaggagePropagator`), а не финальный `MultiTextMapPropagator`. Паттерн `composite(existing, filter)` в customizer **неверен**.

### F2. Inject order при ручном `composite(stock, filterAppend)`

При `composite(W3C+Baggage, SpikeFilteringBaggagePropagator)`:

- stock baggage inject **первым** (полный header с `password=...`);
- filter inject **вторым** → **перезаписывает** ключ `baggage` на HTTP `Map` carrier.

Итоговый header **не содержит** deny-keys. Append-filter после stock **работает** для map-carrier.

### F3. Рекомендуемая стратегия PR-2: **REPLACE в customizer callback**

```java
customizer.addPropagatorCustomizer((propagator, config) -> {
    if (propagator.getClass().getName().contains("W3CBaggagePropagator")) {
        return new FilteringBaggagePropagator(allowlist, deny, config);
    }
    return propagator;
});
```

Проверено spike-тестом `spiReplaceBaggageInCustomizerCallback`: финальный SDK chain содержит `SpikeFilteringBaggagePropagator`, stock `W3CBaggagePropagator` отсутствует; `password` не в outbound `baggage` header.

### F4. `otel.propagators` variants

| Config | Финальный chain |
|--------|-----------------|
| `tracecontext,baggage` | W3C + Baggage (2) |
| `tracecontext,baggage,b3` | W3C + Baggage + B3 (3) — требует `opentelemetry-extension-trace-propagators` на classpath |

Замена baggage в customizer **не ломает** B3: B3 проходит callback без изменений.

### F5. Unwrap `MultiTextMapPropagator`

Reflection на поле `textMapPropagators` — работает для introspection/tests. Production wiring **не должен** полагаться на unwrap для замены — только per-propagator callback (F3).

### F6. Fallback strategies (не выбраны для v1)

| Стратегия | Когда |
|-----------|-------|
| **A** `otel.propagators=tracecontext` + полностью custom chain | Если REPLACE в callback окажется недостаточным |
| **C** client-layer strip `baggage` header после agent inject | external-only deny, запасной путь |

## Решение для PR-2

1. Production `FilteringBaggagePropagator` в `platform-tracing-otel-javaagent-extension/.../propagation/`.
2. Регистрация через `PlatformPropagatorFactory` → `addPropagatorCustomizer` с **REPLACE** W3CBaggage (F3).
3. Allowlist/deny из `platform.tracing.propagation.baggage.*`.
4. E2E: outbound `baggage` без `password` (DoD #8).

## Отклонено

- `composite(existing, FilteringBaggagePropagator)` в SPI customizer — неверная модель (F1).
- `replaceBaggageWithFilter(existing)` на объекте из callback — `existing` не composite (F1).
- Strategy A как default — лишний breaking без необходимости (F3 достаточно).

## Audit

```bash
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "space.br1440.platform.tracing.otel.extension.spike.BaggageFilteringSpikeTest"
```

## Связанные артефакты

- `BaggageFilteringSpikeTest.java`
- `spike/baggage/SpikeFilteringBaggagePropagator.java` (spike-only, не публикуется)
- План: `otel_extension_refactor` PR-2a/PR-2
