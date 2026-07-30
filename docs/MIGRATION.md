# Migration Guide

Документ содержит несколько разделов, каждый соответствует одной волне изменений.

- [P6 cutover: authorities, writers, Category B names](#p6-cutover-authorities-writers-category-b-names)
- [`context-propagation.async` → `context-propagation.thread-pool-task-executor`](#context-propagationasync--context-propagationthread-pool-task-executor)
- [`context-propagation.thread-pool-task-executor` → `thread-pool-task-executor` (flatten)](#context-propagationthread-pool-task-executor--thread-pool-task-executor-flatten)
- [`suppression.suppress-micrometer-tracing`: auto-derive из Agent presence](#suppressionsuppress-micrometer-tracing-auto-derive-из-agent-presence)
- [Removed Spring properties (TracingProperties purge)](#removed-spring-properties-tracingproperties-purge)
- [v0.1.0 default changes (Wave R1+)](#v010-default-changes-wave-r1) — alignment shared defaults Spring ↔ Agent
- [Wave 2c: `micrometer-tracing-bridge-otel` opt-in](#wave-2c-micrometer-tracing-bridge-otel-opt-in)
- [v1.x: `DROP_OLDEST` export processor (default)](#v1x-drop_oldest-export-processor-default)

---

## P6 cutover: authorities, writers, Category B names

| Поле | Значение |
|------|----------|
| **Тип изменения** | Breaking: writer ownership, public control surface, Category B property namespaces |
| **Статус verification** | Production cutover recorded; P5.9 PASS; P6.10 zero-legacy PASS; P6.11 coherent PASS. See `docs/research/evidence/p6-cutover/` and `p7-docs/`. Production rollout still requires committee GO (P7.8). |
| **Aliases** | **None** — dual-path / compatibility aliases forbidden |

### Authority selection

Set exactly one:

```properties
platform.tracing.configuration.authority=HELM
# or CONFIG_SERVER
# or ACTUATOR   # requires platform.environment=LOCAL (Agent label: DEV_ACTUATOR)
```

| Authority | Operator action |
|-----------|-----------------|
| HELM | Provide Category A / Category B as Agent startup env/`-D` only. Do **not** expect Spring private-control discovery or Actuator typed writes. |
| CONFIG_SERVER | Deliver a **complete** eight-leaf Category A manifest; Spring applies via private `applySnapshot` only. |
| ACTUATOR + LOCAL | Use the eight typed `/actuator/tracingControl/...` leaves only. No generic `@WriteOperation` on `/actuator/tracing`. |
| DISABLED (`mode=DISABLED` or `configuration.enabled=false`) | No Spring private-control discovery. Incompatible with CONFIG_SERVER / ACTUATOR. |

### Deleted mutation surfaces (do not call)

| Deleted | Replacement |
|---------|-------------|
| `RuntimeConfigApplier` / RefreshScope mutation | CONFIG_SERVER complete manifest → private `applySnapshot`, or HELM restart |
| Actuator `@WriteOperation` / `platform.tracing.actuator.mutation-enabled` | ACTUATOR+LOCAL typed leaves → private `applySnapshot` |
| Public `api.control.protocol` / `otel.control.protocol` / platform `applyPolicy` | Private writer + platform **read-only** JMX / Control projection |
| Domain JMX setters / mutating ops | Private `applySnapshot` only |
| `ScrubbingPolicyHolder` + Spring `platform.tracing.scrubbing.enabled` / `.built-in-rules` | Agent Category B `platform.tracing.javaagent.scrubbing.*` |

Platform JMX domain MBeans and Control projection are **READ-ONLY**.

### Final Category B property names

| Legacy / Spring-removed | Final Agent name |
|-------------------------|------------------|
| `platform.tracing.scrubbing.enabled` | `platform.tracing.javaagent.scrubbing.enabled` |
| `platform.tracing.scrubbing.built-in-rules` | `platform.tracing.javaagent.scrubbing.built-in-rules` |
| `platform.tracing.sampling.drop-paths` | `platform.tracing.javaagent.sampling.drop-paths` |
| `platform.tracing.sampling.force-record-values` / `force-record-header-values` | `platform.tracing.javaagent.sampling.force-record-values` |
| `platform.tracing.propagation.baggage.*` (Agent mirrors) | `platform.tracing.javaagent.propagation.baggage.*` |

### Rollback

Rollback unit after cutover = **entire unreleased release train**. Do not mix old writers with new property names.

See also [SUPPORTED.md](./SUPPORTED.md) § Configuration authority and runtime policy,
[runbook/runtime-policy-control.md](./runbook/runtime-policy-control.md),
[runbook/jmx-consumer-migration.md](./runbook/jmx-consumer-migration.md),
and [examples/README.md](./examples/README.md).

---

## Runtime ownership mode

Replace the former pair of startup properties with one source of truth:

```properties
platform.tracing.mode=AGENT
```

For an intentional local NoOp runtime without Java Agent use
`platform.tracing.mode=DISABLED`. The removed `platform.tracing.enabled` and
`platform.tracing.sdk.mode` properties have no compatibility aliases.

## `context-propagation.async` → `context-propagation.thread-pool-task-executor`

| Поле | Значение |
|------|----------|
| **Тип изменения** | Breaking: YAML-ключ и Java-путь изменены |
| **Старый YAML ключ** | `platform.tracing.context-propagation.async.enabled` |
| **Новый YAML ключ** | `platform.tracing.context-propagation.thread-pool-task-executor.enabled` |
| **Java API** | `TracingProperties.getContextPropagation().getAsync().isEnabled()` → `TracingProperties.getContextPropagation().getThreadPoolTaskExecutor().isEnabled()` |
| **Причина** | Настройка применяется только к Spring `ThreadPoolTaskExecutor`; короткое имя `async.enabled` ошибочно выглядело как общий переключатель async tracing. Новый путь называет конкретный ресурс и действие: перенос текущего контекста в задачи executor'а. |

### Кого затрагивает

- Сервисы, использующие `platform.tracing.context-propagation.async.enabled=true` — переименовать ключ в `platform.tracing.context-propagation.thread-pool-task-executor.enabled`. Старый и промежуточный `platform.tracing.async.enabled` игнорируются Spring Boot.
- Сервисы, не задававшие свойство — изменений не требуется (default `false` не изменился).

---

## `context-propagation.thread-pool-task-executor` → `thread-pool-task-executor` (flatten)

| Поле | Значение |
|------|----------|
| **Тип изменения** | Breaking: YAML-ключ и Java-путь изменены |
| **Старый YAML ключ** | `platform.tracing.context-propagation.thread-pool-task-executor.enabled` |
| **Новый YAML ключ** | `platform.tracing.thread-pool-task-executor.enabled` |
| **Java API** | `TracingProperties.getContextPropagation().getThreadPoolTaskExecutor().isEnabled()` → `TracingProperties.getThreadPoolTaskExecutor().isEnabled()` |
| **Причина** | `ContextPropagation` был обёрткой с ровно одним child и сам нигде не читался через POJO (значение потребляется `@ConditionalOnProperty` напрямую из `Environment`, минуя getter) — структурный overhead. Имя дополнительно коллизировало с несвязанным `TracingProperties.Propagation` (межсервисные HTTP-заголовки трассировки) и с нативным Spring `spring.reactor.context-propagation`. |

### Кого затрагивает

- Сервисы, использующие `platform.tracing.context-propagation.thread-pool-task-executor.enabled=true` — переименовать ключ в `platform.tracing.thread-pool-task-executor.enabled`. Старый ключ игнорируется Spring Boot.
- Сервисы, не задававшие свойство — изменений не требуется (default `false` не изменился).

---

## `suppression.suppress-micrometer-tracing`: auto-derive из Agent presence

| Поле | Значение |
|------|----------|
| **Тип изменения** | Behavior change для default-конфигурации; property остаётся, но default больше не статичен |
| **YAML ключ** | `platform.tracing.suppression.suppress-micrometer-tracing` (не изменился) |
| **Java API** | `TracingProperties.Suppression#getSuppressMicrometerTracing()` — тип изменён `boolean` → `Boolean` (nullable) |
| **Старый default** | `false` (статичный) |
| **Новый default** | не задано (`null`) → эффективное значение auto-derive'ится из присутствия OpenTelemetry Java Agent в JVM (`OtelAgentDetector`): Agent есть → `true`, Agent'а нет → `false` |
| **Причина** | Разработчики сервисов не должны разбираться в тонкостях 2×2-матрицы (suppress × agent) — типовой случай (Agent подключён → suppress включён автоматически) больше не требует явной настройки |

### Кого затрагивает

- Сервисы, которые **не задавали** свойство явно — поведение теперь соответствует фактическому окружению автоматически: с Agent'ом (production) suppression включается сам, без Agent'а (dev/staging SDK-only) остаётся выключенным. Ранее без явной настройки с Agent'ом возникало дублирование HTTP-span'ов (устранялось только startup-WARN'ом, не поведением).
- Сервисы, которые **явно** задали `true`/`false` — изменений нет: явное значение всегда имеет приоритет над auto-derive.

### Реализация

Auto-derive и активация `WebMvc`/`WebFluxSuppressMicrometerTracingAutoConfiguration` управляются единым `MicrometerTracingSuppressionResolver` (`space.br1440.platform.tracing.autoconfigure.support`), чтобы условие активации автоконфигурации и диагностический WARN (`TracingObservationSuppressStartupRunner`) не могли разойтись.

---

## Removed Spring properties (TracingProperties purge)

Spring `TracingProperties` больше не содержит зеркал agent-конфигурации. Ключи ниже **удалены из Spring binding**; если они остались в `application.yml`, Spring Boot их проигнорирует.

| Старый Spring-ключ | Действие |
|--------------------|----------|
| `platform.tracing.facade.*` | Удалить (мёртвый код, Spring не применял) |
| `platform.tracing.resource.*` | Мигрировать на agent: `platform.tracing.resource.*` / `OTEL_RESOURCE_*` |
| `platform.tracing.limits.*` | Мигрировать на agent: `OTEL_SPAN_*` / `PlatformTracingDefaultsProvider` |
| `platform.tracing.queue.max-size/export-batch-size/export-timeout/policy` | Мигрировать на agent: `OTEL_BSP_*` |
| `platform.tracing.queue.overflow-policy` | **Agent-канал** — не менялся; `-Dplatform.tracing.queue.overflow-policy=...` |
| `platform.tracing.service-names.*` | Удалить (мёртвый код) |
| `platform.tracing.enriching.*` | Мигрировать на agent: `platform.tracing.enriching.*` |
| `platform.tracing.watchdog.*` | Мигрировать на agent: `platform.tracing.watchdog.*` |
| `platform.tracing.control.*` (legacy Spring mirror / public protocol) | Public control protocol deleted. Runtime mutation: private `applySnapshot` via CONFIG_SERVER or ACTUATOR+LOCAL. Platform JMX is read-only. Remaining Agent gate keys (e.g. `platform.tracing.control.runtime-mutation.enabled`) stay Agent-side — not Spring Category A leaves |
| `platform.tracing.propagation.baggage.*` (Agent) | `platform.tracing.javaagent.propagation.baggage.*` (no Spring dual-bind) |
| `platform.tracing.exporter.otlp.*` | Мигрировать на agent/env: `OTEL_EXPORTER_OTLP_*` |
| `platform.tracing.service.version/environment/c-group/container-id` | Мигрировать на agent: `platform.tracing.service.*` |
| `platform.tracing.service.name` | Удалить из Spring binding; app-plane: `spring.application.name`; agent Resource: `OTEL_SERVICE_NAME` / `-Dplatform.tracing.service.name` / `PLATFORM_TRACING_SERVICE_NAME` |
| `platform.tracing.sampling.force-record-header/qa-force-header` | Header names: `platform.tracing.propagation.platform-headers.*` (where still bound); force-record **values**: `platform.tracing.javaagent.sampling.force-record-values` |
| `platform.tracing.sampling.drop-paths` / Spring `force-record-header-values` | `platform.tracing.javaagent.sampling.drop-paths` / `.force-record-values` |
| `platform.tracing.scrubbing.enabled` / `.built-in-rules` (Spring) | `platform.tracing.javaagent.scrubbing.enabled` / `.built-in-rules` |
| `platform.tracing.scrubbing.rules-config` | Agent-side `ScrubbingRulesLoader` (extension); key remains Agent Category B family where still defined |
| `platform.tracing.validation.strict-runtime-allowed` | **Agent-канал** — `-Dplatform.tracing.validation.strict-runtime-allowed=...` |
| `platform.tracing.diagnostics.dual-channel-warn` | Удалить (диагностика удалена) |
| `platform.tracing.diagnostics.drop-oldest-aspiration-*` | Удалить (диагностика удалена) |
| `platform.tracing.aop.mode` | Удалить. `TracedAspect` теперь всегда работает в единственном режиме (обогащение активного span'а без создания дочернего при наличии parent'а); legacy-режим создания отдельного дочернего span'а удалён как источник дублирования span'ов с OTel Java Agent |
| `platform.tracing.context-propagation.async.mode` | Удалить. Единственное поддерживаемое значение никогда не имело альтернативы и не влияло на поведение `ThreadPoolTaskExecutorContextPropagationBeanPostProcessor` (конструктор его не принимал) — свойство существовало только для WARN про само себя ради гипотетического будущего второго режима |

ADR: [decisions/ADR-spring-owns-only-what-spring-applies.md](./decisions/ADR-spring-owns-only-what-spring-applies.md)

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
