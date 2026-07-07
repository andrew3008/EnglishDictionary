# Migration Guide

Документ содержит несколько разделов, каждый соответствует одной волне изменений.

- [v0.1.0 default changes (Wave R1+)](#v010-default-changes-wave-r1) — alignment shared defaults Spring ↔ Agent
- [Wave 2c: `micrometer-tracing-bridge-otel` opt-in](#wave-2c-micrometer-tracing-bridge-otel-opt-in)
- [v1.x: `DROP_OLDEST` export processor (default)](#v1x-drop_oldest-export-processor-default)

---

## v1.x: `DROP_OLDEST` export processor (default)

| Поле | Значение |
|------|----------|
| **Тип изменения** | Платформенный default изменён: `DROP_OLDEST` активируется без явной настройки |
| **Default v1.x** | `DROP_OLDEST` — платформенный default (§2.5 требований): без явной настройки платформенный processor активируется автоматически |
| **Отключение** | `platform.tracing.queue.overflow-policy=UPSTREAM` (system property) **или** `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM` (env-var) — возврат к stock BSP |
| **Эффект** | стандартный `BatchSpanProcessor` подменяется на `PlatformDropOldestExportSpanProcessor` с гарантированной семантикой drop-oldest |
| **ADR** | [decisions/ADR-drop-oldest-export-processor-v1.md](./decisions/ADR-drop-oldest-export-processor-v1.md) |

### Зачем DROP_OLDEST включён по умолчанию

Требование §2.5: «при переполнении очереди автоматически отбрасываются самые **старые** span'ы».
Stock `BatchSpanProcessor` OTel SDK 1.62.0 не выполняет это требование (фактически drop-new).
В v1.x платформа включает custom processor автоматически, чтобы соответствовать требованиям.

Для отключения и возврата к stock BSP задайте явно `UPSTREAM` (см. ниже).

### Ограничения

- **Single-exporter only.** Если SDK сконфигурирован с несколькими exporter'ами (например,
  `otel.traces.exporter=otlp,logging`), платформенный customizer эмитит WARN и оставляет
  стоковый BSP — гарантия drop-oldest не активируется. Решение: для multi-export сценариев
  используйте OpenTelemetry Collector с fan-out, а в приложении оставляйте один exporter.
- **Только для export-фазы.** Заменяется только `BatchSpanProcessor`, который SDK создаёт
  для exporter'а. Другие `SpanProcessor`'ы (например, sampling-side processor'ы) не
  затрагиваются.
- **Stock BSP корректно завершается.** После замены платформенный customizer вызывает
  `shutdown()` стокового BSP — нет утечки worker-потока и double-export.

### Диагностика

- `DropOldestAspirationDiagnostics` (Spring-side) пишет **WARN** если оператор явно задал
  `PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM` при Spring `queue.policy=DROP_OLDEST`.
  В норме (DROP_OLDEST активен) — optional INFO `aligned`.
- При активации Agent эмитит INFO `Platform DROP_OLDEST export processor enabled`.
- Счётчики processor'а: `droppedSpansOverflow`, `droppedSpansAfterShutdown`,
  `exportFailures`, `exportTimeouts`, `queueSize`, `queueCapacity` — доступны программно
  через геттеры; Micrometer/JMX integration — follow-up.

### Откат / отключение DROP_OLDEST

Задайте явно `UPSTREAM` и перезапустите приложение — SDK инициализируется со стоковым `BatchSpanProcessor`:

```
PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=UPSTREAM
# или
-Dplatform.tracing.queue.overflow-policy=UPSTREAM
```

---

## v0.1.0 default changes (Wave R1+)

### `platform.tracing.queue.export-timeout`: 100ms → 5000ms

| Поле | Значение |
|------|----------|
| **Тип изменения** | Behavior change for default configuration, **not** API breaking change |
| **YAML ключ** | `platform.tracing.queue.export-timeout` (не изменился) |
| **Java API** | `TracingProperties.Queue#getExportTimeout()` (не изменился) |
| **Старый default** | `100 ms` |
| **Новый default** | `5000 ms` |
| **Причина** | Выравнивание с agent BSP export timeout default (`PlatformTracingDefaultsProvider` / `OtelSdkDefaults`), устранение dual-channel расхождения |
| **ADR** | [decisions/ADR-dual-channel-properties-v0.1.md](./decisions/ADR-dual-channel-properties-v0.1.md) |

#### Кого затрагивает

- Сервисы, которые **не задавали** `platform.tracing.queue.export-timeout` явно — получат новый default `5000ms`. Это совпадает с фактическим значением, которое OTel agent уже применял через extension SPI (`source: default-platform` в `/actuator/tracing`). Никаких runtime-сюрпризов в production не будет.
- Сервисы, которые **явно** выставили значение в YAML/env/`-D` — изменений нет.

#### Действия

1. Если требуется сохранить старое поведение (100ms — обычно тестовые/dev-среды с локальным Collector), явно задайте:

   ```yaml
   platform:
     tracing:
       queue:
         export-timeout: 100ms
   ```

2. Если сервис настраивает OTel agent через env `OTEL_BSP_EXPORT_TIMEOUT`, изменение Spring-default'а **не влияет**: agent читает свои env vars независимо.

3. Diagnostics: `/actuator/tracing` теперь покажет совпадение секций `queue` (Spring) и `otelEffective.otel.bsp.export.timeout` (Agent) при отсутствии override. См. [runbook/actuator-tracing-diagnostics.md](./runbook/actuator-tracing-diagnostics.md).

---

## Wave 2c: `micrometer-tracing-bridge-otel` opt-in

| Поле | Значение |
|------|----------|
| **Версия** | `0.1.0-SNAPSHOT` → `0.1.1-SNAPSHOT` (Wave 2c) |
| **Дата** | 2026-05-25 |
| **Grace period** | **2 релиза** (`0.1.1` … `0.1.2`) — до `0.2.0` bridge остаётся доступен как opt-in |
| **ADR** | [ADR-mdc-via-otel-agent-logback.md](./decisions/ADR-mdc-via-otel-agent-logback.md) |

---

## Что изменилось

До Wave 2c `platform-tracing-spring-boot-autoconfigure` **транзитивно** подтягивал:

```gradle
io.micrometer:micrometer-tracing-bridge-otel   // api → compileOnly
```

С Wave 2c зависимость **не попадает** в classpath сервиса автоматически. Это отражает production-контракт:

| Path | MDC writer | `bridge-otel` нужен? |
|------|------------|---------------------|
| **Production** (Agent + `suppress-micrometer-tracing=true`) | `OpenTelemetryAppender` (camelCase keys) | **Нет** |
| **Dev/staging** (SDK-only, `suppress=false`) | Micrometer Tracing bridge | **Да — явный opt-in** |

---

## Кого затрагивает

### Production (Agent-first) — **действий не требуется**

Если сервис уже на production path из [SUPPORTED.md](./SUPPORTED.md):

- `-javaagent:opentelemetry-javaagent.jar`
- `platform.tracing.suppression.suppress-micrometer-tracing=true`
- `spring-boot-starter-platform-logging` + `OpenTelemetryAppender` в `logback-spring.xml`
- `-Dotel.instrumentation.logback-mdc.enabled=false`

→ Удаление транзитивного `bridge-otel` **не влияет** на MDC в логах. Sign-off: `AgentMdcPlatformLoggingAgentE2ETest` (G2-MDC-e2e).

### Dev/staging без Agent — **нужен opt-in**

Если локально или в CI вы полагаетесь на Micrometer Observation для span'ов **и** MDC (`%X{traceId}`) **без** Agent:

1. Добавьте зависимость явно:

```gradle
dependencies {
    implementation 'space.br1440.platform.tracing:platform-tracing-spring-boot-starter-servlet' // или -reactive
    implementation 'io.micrometer:micrometer-tracing-bridge-otel'  // opt-in, версия из platform-tracing-bom
}
```

2. Убедитесь, что `suppress-micrometer-tracing=false` (или свойство отсутствует).
3. Этот path **не** является production-standard v0.1.0 — только dev/staging.

### Тесты с `@Tag("bridge-otel-path")`

In-process тесты, проверяющие bridge-only поведение, уже объявляют `testImplementation 'io.micrometer:micrometer-tracing-bridge-otel'` локально (см. `platform-tracing-autoconfigure-webflux`, `MicrometerTracingMdcBridgeSmokeTest`).

---

## Grace period (2 релиза)

| Релиз | Поведение |
|-------|-----------|
| **≤ 0.1.0** | `bridge-otel` транзитивен через `api` (legacy) |
| **0.1.1 (Wave 2c)** | `compileOnly` в autoconfigure; opt-in в `MIGRATION.md`; e2e green |
| **0.1.2** | Напоминание в release notes; аудит потребителей без Agent |
| **0.2.0 (план)** | Возможное полное удаление `compileOnly` из autoconfigure; только явная зависимость потребителя |

Platform-команда уведомляет владельцев сервисов, использующих SDK-only path, через release notes и architecture board.

---

## Checklist миграции для команды сервиса

- [ ] Определить runtime path: **production Agent** или **dev SDK-only**
- [ ] Production: проверить `logback-spring.xml` + OpenTelemetryAppender (см. ADR checklist)
- [ ] Dev SDK-only: добавить `implementation 'io.micrometer:micrometer-tracing-bridge-otel'`
- [ ] Прогнать smoke: лог содержит `traceId=` в `%maskedMDC` block (platform-logging)
- [ ] Убрать ожидание транзитивного bridge из dependency insight / SBOM

---

## FAQ

**Q: Пропадёт ли `%X{traceId}` в production после обновления стартера?**  
A: Нет, если используется Agent path + OpenTelemetryAppender. Bridge не участвовал в production MDC при `suppress=true`.

**Q: Нужен ли bridge для Reactor context propagation в production WebFlux?**  
A: Нет. Sign-off — `ReactorContextPropagationAgentE2ETest` (G2-05-e2e) с Agent + suppress.

**Q: Когда bridge будет удалён полностью из репозитория?**  
A: Не раньше `0.2.0` и только после аудита потребителей SDK-only path.

---

## Связанные артефакты

- [SUPPORTED.md](./SUPPORTED.md) — production matrix
- [runbook/mdc-logging-production.md](./runbook/mdc-logging-production.md) — SRE rollout
- [traceability.md](./tracing/traceability.md) — G2-MDC-e2e, G2-05-e2e
- `platform-tracing-e2e-tests/.../AgentMdcPlatformLoggingAgentE2ETest.java`
