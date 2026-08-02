# Ревизия мёртвых классов: `platform-tracing-spring-boot-autoconfigure`

| Поле | Значение |
|------|----------|
| Дата | 2026-07-30 |
| Модуль | `platform-tracing-spring-boot-autoconfigure` |
| Вердикт | Есть чёткий набор `SAFE_DELETE` (6 production-артефактов); далее — human-gated кандидаты |
| Метод | Repo-wide `*.java` reference scan + сверка с уже удалённым control-plane (`RuntimeConfigApplier`, `TracingProperties.Exporter` / `Suppression`, …) |
| Статус | SAFE_DELETE (п. 1–3) **applied** 2026-07-30; LIKELY_DEAD / NEEDS_HUMAN остаются open |

## Контекст

После миграции control-plane (удаление `RuntimeConfigApplier`, Spring-mirror полей `Exporter` / `Suppression` / `Propagation.enabled` / `Kafka.mode`) в модуле остались классы и DTO, которые больше не имеют production-потребителей. Цель ревизии — зафиксировать кандидатов на удаление с явным disposition.

Sibling-модули (`autoconfigure-webmvc`, `autoconfigure-webflux`, starters) проверены на orphan public surface: orphan'ов не найдено.

---

## Сводка

| Disposition | Кол-во | Действие |
|-------------|--------|----------|
| `SAFE_DELETE` | 6 production + связанные unit-тесты | Удалить в ближайшем cleanup-PR |
| `LIKELY_DEAD` | 1 enum + поле `logLevel`; частично nested `Sampling` / `MandatorySpanAttributes` | Human gate |
| `NEEDS_HUMAN` | Dual actuator endpoints, dual reader interfaces | Не удалять без продуктового решения |
| `KEEP` | Остальной production surface | Без действия |

---

## SAFE_DELETE

### 1. `PlatformTracingJmxOperationException`

| | |
|---|---|
| FQCN | `space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxOperationException` |
| Path | `platform-tracing-spring-boot-autoconfigure/src/main/java/.../jmx/PlatformTracingJmxOperationException.java` |
| Почему | Исключение для мутирующих JMX-операций `PlatformTracingJmxClient`; мутации удалены (P6.1 / arch tests). |
| Evidence | Zero references вне собственного файла. |
| Action | Удалить класс. |
| Risk | Нет. |

### 2. `SamplingControlUnavailableException`

| | |
|---|---|
| FQCN | `space.br1440.platform.tracing.autoconfigure.sampling.SamplingControlUnavailableException` |
| Path | `.../sampling/SamplingControlUnavailableException.java` |
| Почему | HTTP-503 сигнал для удалённого JMX sampling write bridge. |
| Evidence | Zero references вне собственного файла. |
| Action | Удалить класс. |
| Risk | Нет. |

### 3. Tombstone-файл `TracingActuatorControlEndpoint.java`

| | |
|---|---|
| FQCN | *(нет типа — файл содержит только комментарии)* |
| Path | `.../configuration/actuator/TracingActuatorControlEndpoint.java` |
| Почему | Явно опустошён после удаления `@WebEndpoint`+`@Selector`; заменён на `TracingControlMvcController`. |
| Evidence | Нет объявления класса; нет Java-ссылок. |
| Action | Удалить файл. |
| Risk | Нет. |

### 4–6. Мёртвый Spring reconcile-кластер (post-`RuntimeConfigApplier`)

| FQCN | Path | Evidence |
|------|------|----------|
| `...sampling.SamplingRuntimeConfig` | `.../sampling/SamplingRuntimeConfig.java` | Prod refs: **none**. Тесты: `SamplingRuntimeConfigTest`; arch-тест ссылается на тип только чтобы доказать отсутствие метода. |
| `...sampling.ValidationRuntimeConfig` | `.../sampling/ValidationRuntimeConfig.java` | Prod refs: **none**. Тесты: `ValidationRuntimeConfigTest`. |
| `...sampling.SamplingRouteRatiosWire` | `.../sampling/SamplingRouteRatiosWire.java` | Package-private helper; используется **только** из `SamplingRuntimeConfig.from()`. |

| | |
|---|---|
| Disposition | `SAFE_DELETE` |
| Action | Удалить тройку + unit-тесты; переписать `ProductionControlPlaneNotMigratedArchTest` без ссылки на `SamplingRuntimeConfig.class`; обновить stale inventory strings (`SamplingRuntimeConfig -> CompositeSampler`, `ValidationRuntimeConfig -> ValidatingSpanProcessor`). |
| Risk | Low. |

---

## LIKELY_DEAD

### 7. `OtelAgentLogLevel` + `TracingProperties.logLevel`

| | |
|---|---|
| FQCN | `space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel` |
| Path | `.../diagnostics/OtelAgentLogLevel.java` + поле в `TracingProperties` |
| Почему | Исторический Spring→Agent push через удалённый `RuntimeConfigApplier` / actuator write. Живой leaf — `platform.tracing.policy.diagnostics.log-level` (ConfigServer / ACTUATOR control). |
| Evidence | Production `getLogLevel()` **нигде не вызывается**. Bind-only (`TracingPropertiesBindingTest`). |
| Disposition | `LIKELY_DEAD` |
| Action | Удалить enum + поле + binding-тесты после подтверждения, что Helm/docs больше не рекламируют `platform.tracing.log-level` как активный ключ; в inventory пометить как superseded by policy key. |
| Risk | Binding break для тех, кто ещё задаёт `platform.tracing.log-level` (сейчас это no-op). |

### 8. `TracingProperties.Sampling` / `MandatorySpanAttributes` как Spring mirrors

| | |
|---|---|
| FQCN | nested types в `TracingProperties` |
| Почему | Аналог уже удалённых `Exporter` / `Suppression`: Spring POJO больше не гоняет reconcile. Production-чтение getters — в основном legacy `/actuator/tracing` display (`TracingActuatorEndpoint`). Config Server читает `platform.tracing.policy.*` через `Environment`. |
| Evidence | Agent всё ещё объявляет `platform.tracing.sampling.*` / `platform.tracing.validation.*` в `ExtensionPropertyNames` — dual key space. |
| Disposition | `LIKELY_DEAD` как Spring reconcile mirrors; **`NEEDS_HUMAN`** для retirement property-ключей. |
| Action | Не удалять в том же PR, что SAFE_DELETE. Отдельное решение: перестать проецировать Spring-bound sampling/validation на `/actuator/tracing`, либо убрать nested types после полного перехода Agent на `platform.tracing.policy.*`. |
| Risk | High без Agent/key migration — операторы могут класть значения в `application.yml`, ожидая Agent pickup. |

---

## NEEDS_HUMAN (не удалять без решения)

### 9. Dual Actuator endpoints (historical; **DELETED**)

| Endpoint | Класс | Роль |
|----------|-------|------|
| `/actuator/tracing` | `...actuator.TracingActuatorEndpoint` | Legacy diagnostics (Spring desired + public JMX live reads) |
| `/actuator/tracingPolicy` | `...configuration.actuator.TracingActuatorEndpoint` | Secret-safe accepted-policy projection |

Продукт свернул diagnostics в единый read-only read-model `tracingStatus`. Оба legacy endpoint'а физически удалены (`P64ActuatorWriteAbsenceTest.legacyReadEndpointsAreRemoved`); `OtelEffectiveConfigSnapshot`/`ResourceEffectiveSnapshot` (единственные потребители `/actuator/tracing` §8 выше) удалены без восстановления — см. [ADR-remove-dual-channel-diagnostics.md](../decisions/ADR-remove-dual-channel-diagnostics.md). Disposition: **DELETED**.

### 10. Dual reader interfaces

| FQCN | Роль |
|------|------|
| `AcceptedTracingPolicyReader` | Secret-safe projection |
| `AcceptedTracingPolicySnapshotReader` | Complete snapshot для read-copy-change |

Оба реализованы `PrivateJmxTracingPolicySnapshotClient`. Disposition: **KEEP**.

---

## KEEP (проверены как «подозрительные», но живые)

| Тип | Evidence |
|-----|----------|
| `PlatformTracingJmxClient`, `PlatformTracingJmxObjectNames`, `TracingControlDomain` | `TracingCoreAutoConfiguration`; `tracingStatus` read-model + metrics |
| `SpanFactoryDiagnostics`, `TracingDiagnosticsMapper`, `TracingDiagnosticsView` | Actuator map |
| `SdkModeDiagnostics`, `AgentExtensionObserver`, `AgentExtensionDescriptor` | Startup path |
| `configuration.*` control plane (adapters, coalescer, private JMX, MVC, DTOs) | ACTUATOR + CONFIG_SERVER; webflux management router |
| Kafka / async / propagation / AOP / observation / metrics auto-configs | `AutoConfiguration.imports` + sibling usage |

---

## Рекомендуемый порядок удаления

1. **Tombstone:** `TracingActuatorControlEndpoint.java`
2. **Zero-ref exceptions:** `PlatformTracingJmxOperationException`, `SamplingControlUnavailableException`
3. **Reconcile cluster:** `SamplingRuntimeConfig` + `SamplingRouteRatiosWire` + `ValidationRuntimeConfig` (+ unit-тесты; fix arch/inventory)
4. **Human gate:** `OtelAgentLogLevel` / `logLevel`
5. **Позже (policy migration):** `TracingProperties.Sampling` / `MandatorySpanAttributes`

---

## Связанный cleanup, уже выполненный в этой ветке работы

Не кандидаты на удаление сейчас (уже сделано ранее):

- `TracingProperties.Exporter` / `Suppression`
- `Propagation.enabled`, `Kafka.mode`, `ThreadPoolTaskExecutorPropagation`
- `RuntimeConfigApplier`, `TracingRefreshScopeAutoConfiguration`, `SamplingControlClient`

---

## Качество evidence

| Claim | Status |
|-------|--------|
| Zero production refs для SAFE_DELETE set | PASS (repo-wide `*.java`) |
| `RuntimeConfigApplier` уже отсутствует | PASS (`ProductionControlPlaneNotMigratedArchTest`) |
| Inventory/docs всё ещё цитируют удалённые apply paths | PASS_WITH_WARNINGS (stale docs/strings, не runtime) |
| Sibling starters orphan types | PASS — не найдено |

---

## Applied: SAFE_DELETE (2026-07-30)

Удалены:
- `TracingActuatorControlEndpoint.java` (tombstone)
- `PlatformTracingJmxOperationException`
- `SamplingControlUnavailableException`
- `SamplingRuntimeConfig`, `SamplingRouteRatiosWire`, `ValidationRuntimeConfig`
- unit-тесты `SamplingRuntimeConfigTest`, `ValidationRuntimeConfigTest`

Обновлены:
- `ProductionControlPlaneNotMigratedArchTest` (assertions без ссылки на удалённые типы)
- inventory consumer labels для `sampling` / `validation`

## Следующий шаг

Human gate на пункт 4 (`OtelAgentLogLevel` / `logLevel`), затем отдельное решение по Spring mirrors `Sampling` / `MandatorySpanAttributes`. Dual actuator и dual policy readers не трогать.
