# ADR: фактическая политика overflow стандартного BatchSpanProcessor (SDK 1.61.0) — finding

| Поле | Значение |
|------|----------|
| Статус | **Принято (finding)** |
| Дата | 2026-05-25 |
| Контекст | Wave R1+; backlog gate «BSP drop-oldest behavior validated on SDK 1.61.0» из `SUPPORTED.md` |
| Стек | OpenTelemetry Java SDK **1.61.0** (pinned через `gradle.properties#openTelemetryBomVersion`) |
| Re-validated | **SDK 1.62.0 / Agent 2.28.1** (train bump): probe перезапущен, политика drop-new подтверждена без изменений |
| Probe | [`BatchSpanProcessorOverflowPolicyProbeTest`](../../platform-tracing-otel/src/test/java/space/br1440/platform/tracing/core/bsp/BatchSpanProcessorOverflowPolicyProbeTest.java) |
| Related | [ADR-dual-channel-properties-v0.1.md](./ADR-dual-channel-properties-v0.1.md) |

## Контекст

В `TracingProperties.Queue.OverflowPolicy` есть значение `DROP_OLDEST` — оно отражает
требование «при переполнении очереди вытеснять самые старые span'ы». До этого finding'а
было неясно, реализует ли стандартный `BatchSpanProcessor` это поведение фактически.

Probe-тест намеренно построен как **guardrail на pinned SDK 1.61.0**, а не как
«доказательство drop-oldest». Если SDK изменит политику при upgrade — тест сломается и
потребует обновления ADR (раздел «При upgrade SDK»).

## Observed behavior (snapshot)

### Конфигурация probe

| Параметр | Значение |
|----------|----------|
| `maxQueueSize` | 4 |
| `maxExportBatchSize` | 2 |
| `scheduleDelay` | 10 минут (исключает background flush в фазе overflow) |
| `exporterTimeout` | 30 секунд |
| Exporter | блокирующий, освобождается тестом после фазы overflow |
| Кол-во испущенных span'ов | 34 (2 первого batch + 4 × 8 = 32 в фазе overflow) |

### Snapshot

```
[BSP overflow probe] SDK observed exported seq: [0, 1, 2, 3, 4, 5]
[BSP overflow probe] SDK observed exported count: 6
[BSP overflow probe] SDK observed export() call count: 3
```

### Интерпретация

- Экспортированы: первый batch (seq 0, 1) — заблокированный в exporter; затем seq 2, 3, 4, 5
  — первые 4 span'а, которые BSP успел принять в очередь до того, как очередь оказалась
  полностью занята (`maxQueueSize=4`).
- Все остальные 28 span'ов (seq 6..33), пытавшиеся встать в полную очередь, **были
  отброшены** на стадии enqueue.
- export() вызвался 3 раза: первый batch (0,1), затем (2,3), затем (4,5).

**Вывод:** стандартный `BatchSpanProcessor` SDK 1.61.0 реализует политику
**drop-new** (a.k.a. **drop-on-add**, **drop-current**) — при переполнении очереди
отбрасываются **новые** поступающие span'ы, сохраняются ранее принятые. Это **не**
соответствует семантике `DROP_OLDEST`.

## Outcome

**Outcome 2** из трёх рассмотренных в [ADR-dual-channel-properties-v0.1.md](./ADR-dual-channel-properties-v0.1.md):

> SDK behavior == drop-new / drop-current / other — требование `drop-oldest` не закрыто
> стандартным BSP. Решения:
> - v0.1.0 scope: переформулировать требование до `bounded-queue-with-drop` (без гарантии порядка);
> - custom `SpanProcessor` с гарантированным drop-oldest → backlog v1.x;
> - `TracingProperties.Queue.OverflowPolicy.DROP_OLDEST` пометить в Javadoc как «aspirational / v1.x».

## Решение

Для **v0.1.0**:

1. **Требование `drop-oldest` смягчается до `bounded-queue-with-drop`**: гарантируется
   только то, что очередь имеет ограниченный размер, и при переполнении новые span'ы
   отбрасываются без блокировки приложения и без unbounded memory growth.
2. **`TracingProperties.Queue.OverflowPolicy.DROP_OLDEST` остаётся в API** как aspirational
   контракт (значение enum не удаляется ради совместимости с YAML), но в Javadoc явно
   указывается: фактическая политика стандартного BSP — drop-new; гарантированный
   `DROP_OLDEST` требует custom `SpanProcessor` и попадает в backlog v1.x.
   (Поправка уже внесена в [TracingProperties.java](../../platform-tracing-spring-boot-autoconfigure/src/main/java/space/br1440/platform/tracing/autoconfigure/TracingProperties.java).)
3. **Эксплуатационная безопасность** (приложение не падает, нет OOM, есть diagnostic сигнал)
   валидируется отдельным e2e — `BspOverflowSafetyAgentSmokeTest`. Это и есть production
   guarantee v0.1.0.
4. **Custom `SpanProcessor` с реальным `DROP_OLDEST` — backlog v1.x.** Требует performance
   design и сравнения с альтернативами (priority queue / error-first eviction). Не делается
   в v0.1.0.

## Когда переоценивать

- При upgrade `openTelemetryBomVersion` (см. `gradle.properties`) — probe-тест выполняется
  как часть `./gradlew check` и упадёт при изменении SDK-поведения. В этом случае:
  1. зафиксировать новый snapshot в этом ADR (или создать новый ADR-finding);
  2. при необходимости пересмотреть outcome (1 или 3).
- При появлении production-инцидента, связанного с потерей конкретных «свежих» span'ов
  под высокой нагрузкой — рассмотреть custom `SpanProcessor` v1.x.

## v1.x outcome (опит-ин решение)

Custom `SpanProcessor` с гарантированной политикой `DROP_OLDEST` принят в работу в v1.x как
**opt-in**: не активируется по умолчанию, чтобы не менять silently overflow-semantics
относительно v0.1.0. Default v1.x = stock BSP (тот же drop-new, что и v0.1.0).

Включается явно: `platform.tracing.queue.overflow-policy=DROP_OLDEST`
(env `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=DROP_OLDEST`).

Полный дизайн, контракт, диагностика, тестовая стратегия и подтверждённые SPI-факты
см. [ADR-drop-oldest-export-processor-v1.md](./ADR-drop-oldest-export-processor-v1.md).

Probe-тест [`BatchSpanProcessorOverflowPolicyProbeTest`](../../platform-tracing-otel/src/test/java/space/br1440/platform/tracing/core/bsp/BatchSpanProcessorOverflowPolicyProbeTest.java)
**сохраняется** как guardrail на pinned SDK — он защищает оба контракта:
v0.1.0 (stock BSP = drop-new) и v1.x default (тот же stock BSP, без замены).

## Out of scope

- Default `DROP_OLDEST` в v1.x (рассмотрено отдельно — v2.x backlog).
- Priority export / error-first eviction (отдельный backlog).
- JMH baseline для overflow behavior (отдельный gate в SUPPORTED.md).
- Полный bridge Spring → Agent (см. ADR-dual-channel-properties-v0.1).
