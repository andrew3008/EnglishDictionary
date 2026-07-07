# External Opus Architecture Review: Runtime State Refactoring

**Дата:** 2026-07-02
**Тип:** read-only / planning (external review synthesis)
**Инструмент:** `java_mcp_opus.review_architecture_with_opus` (model `claude-opus-4-8`, requestId `4a496e41-a313-41d5-9056-0d43788d6f98`)
**Связанный план:** `.cursor/plans/versioned_runtime_state_refactor_5cd4e192.plan.md`
**Инвентаризация:** [versioned-interface-inventory.md](versioned-interface-inventory.md)

---

## 1. Входные факты (переданы во внешний review, подтверждены по коду)

- `api.config` содержит **только** [Versioned.java](../../platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/config/Versioned.java) (маркер `long version()`) и [DomainConfigHolder.java](../../platform-tracing-api/src/main/java/space/br1440/platform/tracing/api/config/DomainConfigHolder.java) (`<T extends Versioned>`, `AtomicReference` + CAS-цикл + last-known-good на всех ветках отказа, side-effect-free builder).
- Это **runtime-state publication primitive**, не application config. Живёт в `api`, т.к. виден и App CL (Spring), и Agent CL (OTel extension).
- Holder-managed states: `SamplerState`, `ScrubbingSnapshot`, `ValidationSnapshot` (каждый обёрнут доменным holder'ом, который композицией держит `DomainConfigHolder<T>`); версия реальна (JMX + монотонный CAS).
- `SamplingPolicySnapshot implements Versioned`, но **никогда не кладётся в holder** — это вложенный compiled-policy объект внутри `SamplerState.policySnapshot`. Реальная CAS-сущность sampling — `SamplerState`.
- Мёртвая цепочка версии: `SamplingPolicySnapshot.version()` не читается в проде (только 2 теста); `SamplingPolicyProperties.version` кормит только `SamplingPolicySnapshotFactory`; `SamplerPolicyUpdate.validateDomain` передаёт `0L`.
- OTel extension встраивает api/core jar в agent **без** package-specific relocation, ключённого на `api.config`. Application/starter код `Versioned`/`DomainConfigHolder` не импортирует (0 импортов).
- Инварианты поведения, которые НЕЛЬЗЯ менять: CAS, LKG, builder retry, инкремент версии `prev.version()+1`, JMX/wire schema, поведение sampling/scrubbing/validation.

---

## 2. Вердикт внешнего Opus

**APPROVE_WITH_CHANGES** → **GO при выполнении условий** перед стартом реализации.

Направление (rename + repackage, реальная чистка, без shim'ов) признано архитектурно верным для pre-production. Подтверждены как обоснованные: целевой пакет, имена маркера и holder'а, снятие маркера с `SamplingPolicySnapshot`, полное удаление мёртвой цепочки версии, три ArchUnit-правила. Требуются доработки (ниже).

### Подтверждённый финальный таргет (совпадает с нашим планом)

- Пакет: `space.br1440.platform.tracing.api.runtime.state` (в существующем модуле `platform-tracing-api`; **новый модуль не нужен**).
- Маркер: `VersionedState` (`long version()`).
- Holder: `VersionedStateHolder<T extends VersionedState>`.
- Тест: `DomainConfigHolderTest` → `VersionedStateHolderTest`.
- Closed set реализаций: `SamplerState`, `ScrubbingSnapshot`, `ValidationSnapshot`.
- Удаляется: пакет `api.config` целиком, `implements` + `version()` у `SamplingPolicySnapshot`, `version` у `SamplingPolicyProperties`.
- Не трогаем: `SamplerState/ScrubbingSnapshot/ValidationSnapshot.version`, CAS/LKG/builder-retry/`prev.version()+1`, dual-classloader контракт.

### Разбор именования (Opus)

- Маркер: `Versioned` — reject (называет свойство, не роль); `VersionedState` — **recommended** (роль как у `Comparable`); `RuntimeState` — слишком общий, теряет versioning; `VersionedRuntimeState` — избыточно (пакет уже `runtime.state`); `RuntimePolicyState` — доменно-связанный, «гниёт».
- Holder: `DomainConfigHolder` — reject (ложно про «config»); `VersionedStateHolder` — **recommended**; `AtomicRuntimeStateHolder` — reject (тащит `AtomicReference` в имя public API); `RuntimeStateHolder`/`RuntimePolicyStateHolder` — теряют versioning / доменно-связаны.
- Пакет: `api.config` — reject; `api.runtime.state` — **recommended**; `api.control.state`/`api.control.snapshot` — reject (ложно ассоциируют с control-plane/wire `api.control.wire`); отдельный модуль — over-engineering (YAGNI для 2 типов).

---

## 3. Сравнение с текущим планом

| Аспект | План (5cd4e192) | Внешний Opus | Статус |
|--------|-----------------|--------------|--------|
| Пакет `api.runtime.state` | да | да | СОВПАДЕНИЕ |
| `VersionedState` / `VersionedStateHolder` | да | да | СОВПАДЕНИЕ |
| Decouple `SamplingPolicySnapshot` | да | да | СОВПАДЕНИЕ |
| Полное удаление version chain (Option B) | да | да | СОВПАДЕНИЕ |
| Сохранить общий маркер + allowlist | да | да | СОВПАДЕНИЕ |
| Без `package-info.java` | да | да (зафиксировать в ADR) | СОВПАДЕНИЕ |
| Отдельный модуль | отвергнут | отвергнут (YAGNI) | СОВПАДЕНИЕ |
| 3 ArchUnit guardrail'а | да | да, но allowlist переписать на FQN | ПРИНЯТО (доработка) |
| Аудит не-`.java` ссылок (yaml/JMX/дашборды) | частично (grep-инвентаризация) | требуется явная **P1.5** | ПРИНЯТО (усиление) |
| Wire-schema аудит `SamplingPolicySnapshot.version` | не выделен | требуется **P2.5** | ПРИНЯТО |
| `equals/hashCode` snapshot после удаления version | не выделен | проверить | ПРИНЯТО (см. §5) |
| Тест `validateDomain(...0L)` | нет | добавить | ПРИНЯТО |
| Banned-import регрессия на `api.config.*` | acceptance-grep | превратить в CI-правило | ПРИНЯТО |
| Доп. правила `HOLDER_TYPES_REQUIRE_MARKER_BOUND`, `SNAPSHOT_FIELDS_ARE_FINAL` | нет | рекомендованы | ПРИНЯТО (low-cost) |
| `BUILDER_RETRY_IS_PURE` (фитнес-тест, не config) | нет | рекомендован | ОТКЛОНЁН (см. §6) |

---

## 4. Принятые изменения (войдут в план)

1. **P1.5 — Classloader/asset аудит.** Repo-wide grep по НЕ-`.java` артефактам (`.properties/.yaml/.yml/.json/.xml/.md/.adoc`, JMX `ObjectName`/MBean-атрибуты, log/MDC-ключи, Micrometer-теги, Grafana JSON, alerting-правила) на `api.config.(Versioned|DomainConfigHolder)` и на `SamplingPolicySnapshot.version`. Rename не идёт дальше P3, пока не 0 hits. Закрывает главный риск agent-embedded jar, который source-grep пропускает.
2. **P2.5 — Wire-schema аудит.** Убедиться, что `api.control.wire` не сериализует `SamplingPolicySnapshot.version` как атрибут. Если да — заменить на `SamplerState.version` до удаления.
3. **Allowlist по FQN.** `VERSIONED_STATE_IMPLS_ALLOWLIST` переписать как позитивный список классов (`SamplerState`, `ScrubbingSnapshot`, `ValidationSnapshot`) и явно исключить test-sources (`*Test`, `*Tests`, `*IT`), а не выдавать пакетам-исключениям право за счёт test-fixture.
4. **Тест `SamplerPolicyUpdate.validateDomain`.** Ассерт, что version-аргумент в `SamplingPolicyProperties` теперь единственный фиктивный (`0L`) и его удаление не требует компенсаций.
5. **CI banned-import.** Постоянное правило (ArchUnit `noClasses().should().dependOnClassesThat().resideInAPackage("..api.config..")` или regex-gate), блокирующее возврат `api.config.(Versioned|DomainConfigHolder)`.
6. **Доп. ArchUnit (low-cost):** `HOLDER_TYPES_REQUIRE_MARKER_BOUND` (запрет `VersionedStateHolder<Object>`/дрейфа bound'а) и `SNAPSHOT_FIELDS_ARE_FINAL` (регрессионный guard иммутабельности снимков).
7. **ADR до P3.** `ADR-versioned-state-runtime-package.md` пишется и ревьюится ДО переименования: значение пакета, роль маркера, allowlist + как добавлять новый state, почему нет `package-info.java`, почему нет shim'ов, dual-classloader факт.

---

## 5. Проверка замечания про equals/hashCode (résolue)

[SamplingPolicySnapshot.java](../../platform-tracing-core/src/main/java/space/br1440/platform/tracing/core/sampling/model/SamplingPolicySnapshot.java) — это `@Getter public final class` **без** переопределённых `equals`/`hashCode`/`toString`. Поле `version` не участвует ни в каком контракте идентичности. Значит удаление `version` не меняет семантику равенства/логирования снимка — риск, отмеченный Opus в §5, для нашего кода **нулевой** (identity-based equality сохраняется). Фиксируем как проверенный факт, отдельный тест не требуется.

---

## 6. Отклонённые / отложенные предложения

- **Отдельный модуль `platform-tracing-runtime-api`** — отклонён (совпадает с Opus): over-engineering для 2 типов, рост числа зависимостей без выигрыша; пересмотреть, если появится 4-й holder или внешний SPI-потребитель.
- **`BUILDER_RETRY_IS_PURE`** — **ОТКЛОНЁН, в PR не входит.** Терминологическое уточнение: это НЕ конфигурационный property (разработчики ничего не задают в `application.yml`); «property-based» относится к *стилю тестирования* (property-based testing, напр. jqwik), а само `BUILDER_RETRY_IS_PURE` — предложенное имя фитнес-теста/ArchUnit-контракта, проверяющего, что `builder` в `tryUpdate(...)` side-effect-free. Причина отклонения: контракт side-effect-free уже (а) зафиксирован в Javadoc `DomainConfigHolder`/`VersionedStateHolder`, (б) практически покрыт существующим `DomainConfigHolderTest.concurrentUpdatesAreSerializedWithoutLoss` (8 потоков × 2000, монотонность версий + отсутствие потерь). Отдельный тест избыточен для pre-production. Возможный кандидат на будущее, но вне scope.
- **`AtomicRuntimeStateHolder`, `RuntimePolicyState*`, `api.control.*`** — отклонены именно по причинам Opus (leak impl detail / доменная связанность / ложная ассоциация с control-plane).

Мелкая неточность внешнего Opus: в §8 он написал «5-arg constructor becomes 4-arg». Фактически текущий конструктор `SamplingPolicySnapshot` — **6-арг**, после удаления `version` становится **5-арг** (и record `SamplingPolicyProperties` — 6→5 компонентов). План использует корректную арифметику.

---

## 7. Финальная рекомендация

- **Пакет:** `space.br1440.platform.tracing.api.runtime.state`
- **Типы:** `VersionedState` (маркер `long version()`), `VersionedStateHolder<T extends VersionedState>`
- **`SamplingPolicySnapshot implements ...`:** удалить (снять маркер + зависимость model→api)
- **`SamplingPolicySnapshot.version`:** удалить (мёртвое поле, identity-equality не затрагивается)
- **`SamplingPolicyProperties.version`:** удалить (единственный читатель исчезает)
- **Guardrails:** 3 базовых правила (allowlist по FQN + исключение тестов, model!→runtime.state, app!→runtime.state) + `HOLDER_TYPES_REQUIRE_MARKER_BOUND` + `SNAPSHOT_FIELDS_ARE_FINAL` + постоянный banned-import на `api.config.*`
- **Модуль:** новый НЕ создавать
- **Порядок:** P0 → **P1.5 asset-аудит** → P1 (version chain) → **P2.5 wire-аудит** → P2 (decouple) → ADR → P3 (move/rename) → P4 (imports/holders/tests/bench) → P5 (delete api.config + ArchUnit + build/javadoc) → P6 (docs/ADR + acceptance-grep как CI-gate)

## 8. Top-5 рисков

1. **Agent-embedded api/core jar**: не-`.java` ссылки (JMX ObjectName, config-ключи, дашборды) на старый FQN — невидимы source-grep'у. Митигируется P1.5.
2. **Wire-schema**: если `api.control.wire` где-то сериализует `SamplingPolicySnapshot.version` — удаление сломает контракт. Митигируется P2.5.
3. **Атомарность cutover**: без shim'ов rename обязан быть атомарным по всем модулям в одном релизе; нельзя разбивать между релизами.
4. **Allowlist-дрейф**: слабый scoping правила разрешит `VersionedState` расползтись как «маркер на всякий случай». Митигируется FQN-allowlist + banned-import.
5. **Observability-ассеты**: Grafana-панели/алерты, завязанные на удаляемую версию snapshot — обновить в том же релизе.

## 9. Go / No-Go

**GO** для реализации — при условии выполнения P1.5 (asset-аудит = 0 hits), P2.5 (wire-аудит), написания ADR до P3 и включения доработанных guardrail'ов. Все условия — read-only проверки и уточнения плана; блокеров архитектурного уровня нет.

**Остающееся решение для архитекторов:** утвердить набор из 5 ArchUnit-правил + banned-import как обязательный CI-gate. (`BUILDER_RETRY_IS_PURE` — фитнес-тест, а не config-property — в этот PR НЕ входит; решение зафиксировано.)
