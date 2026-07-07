# JMX Spike Production Purge With Test Fixtures Plan

> **Статус реализации:** `IMPLEMENTED` (NEW_HYBRID / Production Purge with Test-Only Relocation).
>
> JMX wire spike transport has been removed from production code.
> Any retained JMX wire harness exists only as test-only OTel extension infrastructure.
> Production no longer supports platform.tracing.spike.jmx.wire.
> TracingControlWireValidator / wire schema remains as stable validation contract.
>
> Фактический механизм релокации — отдельный source set `platform-tracing-e2e-tests/src/jmxWireExtension`
> (test-only OTel extension JAR `testJmxWireExtensionJar`, провайдер `WireRoundTripTestExtensionProvider`,
> MBean `WireRoundTripTestMBean` / `type=WireRoundTripTest`, маркер `WIRE_ROUND_TRIP_TEST:`), а не Gradle `testFixtures`.

> **Тип документа:** план реализации. Раздел статуса выше отражает выполненное состояние.
>
> **Naming cleanup (follow-up):** test-only классы переименованы в нейтральные имена без `Spike`:
> `MapWireRoundTripSpikeE2ETest` → `MapWireRoundTripE2ETest`,
> `MapWireRoundTripSpikeMain` → `MapWireRoundTripMain` (пакет `space.br1440.platform.tracing.e2e.wire`),
> `SamplingControlClientWireSpikeTest` → `SamplingControlClientWireContractTest`.
> Старые имена ниже сохранены как историческая запись плана.
> **Целевая архитектура:** `NEW_HYBRID` — Production Purge with Test-Only Relocation.
> **Механизм релокации:** `platform-tracing-e2e-tests/src/testExtension` (test-only OTel extension JAR), **НЕ** Gradle `java-test-fixtures`.
> **Имя файла** сохранено как `...test-fixtures-plan.md` по запросу, но фактический механизм — `testExtension` (см. §5).

---

## 1. Executive Summary

- Финальный таргет — `NEW_HYBRID`: production-пакет `space.br1440.platform.tracing.otel.extension.jmx.spike` должен быть **полностью удалён из `src/main`**.
- JMX spike / MBean / `ObjectName type=WireSpike` **не остаются** в production-артефакте (`agentExtensionJar`).
- Production runtime gate `platform.tracing.spike.jmx.wire` и stderr-маркер `SPIKE_JMX_WIRE:` удаляются из production-логики.
- Wire-schema **остаётся** стабильным production API: `TracingControlWireValidator.V1` в `platform-tracing-api`.
- Полезное E2E-покрытие **сохраняется** через перенос JMX-харнесса в test-only OTel extension JAR (`platform-tracing-e2e-tests/src/testExtension`), который грузится в Agent `ExtensionClassLoader` через `-Dotel.javaagent.extensions`.
- Spike-специфичные ArchUnit-исключения заменяются на production-запрет (`..spike..` / `*Spike*` в `src/main`).
- **Это не rename.** Меняются reachability, packaging, JMX-поверхность, runtime gate, тестовая топология, ArchUnit и документация одновременно.

---

## 2. Current Evidence

Факты, подтверждённые исходным кодом репозитория.

### 2.1 Production-классы (3 шт.) в `src/main`

Пакет `platform-tracing-otel-extension/src/main/java/.../jmx/spike/`:

- `TracingControlWireSpikeMBean` — JMX-контракт; константа
  `OBJECT_NAME = "space.br1440.platform.tracing:type=WireSpike,name=TracingControlWireSpike"`,
  операция `evaluateWirePayload(Map<String,Object>)`, набор result-ключей OpenMBean.
- `TracingControlWireSpike implements TracingControlWireSpikeMBean` — реализация; `evaluateWirePayload` делегирует в `TracingControlWireValidator.V1`; `registerSafely()` регистрирует MBean в `ManagementFactory.getPlatformMBeanServer()`. **Validation-only**: не мутирует sampler / export / scrubbing runtime.
- `TracingControlWireSpikeProbe` — property-gate + stderr-маркеры:
  - `ENABLE_PROPERTY = "platform.tracing.spike.jmx.wire"`;
  - `LINE_PREFIX = "SPIKE_JMX_WIRE:"`;
  - `registerIfEnabled()` — регистрирует MBean только при `-Dplatform.tracing.spike.jmx.wire=true`;
  - `emit(...)` / `emitScenarioResult(...)` — печать маркеров в `System.err`.

### 2.2 Production bootstrap call-site

`platform-tracing-otel-extension/src/main/java/.../PlatformAutoConfigurationCustomizer.java`, строки **93-97**:

```java
// PR-3 spike-only JMX wire MBean (property-gated; no production control-plane impact).
customizer.addTracerProviderCustomizer((builder, config) -> {
    space.br1440.platform.tracing.otel.extension.jmx.spike.TracingControlWireSpikeProbe.registerIfEnabled();
    return builder;
});
```

Вызов выполняется **всегда** при bootstrap agent extension → код production-reachable (даже если gate по умолчанию no-op).

### 2.3 Runtime gate, маркер, ObjectName, операция, валидатор

- Gate: `platform.tracing.spike.jmx.wire` (default отсутствует → no-op).
- Маркер: `SPIKE_JMX_WIRE:` (stderr).
- ObjectName: `space.br1440.platform.tracing:type=WireSpike,name=TracingControlWireSpike`.
- Операция: `evaluateWirePayload(Map)`.
- Wire-валидация: `TracingControlWireValidator.V1` (модуль `platform-tracing-api`, пакет `...api.control.wire`) — отделена от транспорта; транспорт лишь её использует.

### 2.4 Тесты, зависящие от spike

Unit (`platform-tracing-otel-extension/src/test/.../jmx/spike/`):

- `PlatformTracingControlWireSpikeTest` — in-process JMX round-trip через `registerSafely()` + `MBeanServer.invoke`.
- `MapWireRoundTripSpikeTest` — focused round-trip proof через `registerSafely()`.
- `SpikeMBeanPropertyGateArchTest` — FF-07: gate disabled by default, `registerIfEnabled()` no-op без property.
- `SpikeMBeanValidationOnlyArchTest` — FF-08: делегирует в `ArchitectureFitnessArchRules.SPIKE_MBEAN_VALIDATION_ONLY`.

E2E (`platform-tracing-e2e-tests/src/test/.../e2e/spike/`):

- `MapWireRoundTripSpikeE2ETest` — поднимает дочернюю JVM с `-javaagent` + `-Dotel.javaagent.extensions=<extension.jar>` + `-Dplatform.tracing.spike.jmx.wire=true`, парсит `SPIKE_JMX_WIRE:` маркеры, проверяет `registered=true` и сценарии. **Ключевой cross-classloader тест: App CL → JMX → Agent ExtensionClassLoader.**
- `MapWireRoundTripSpikeMain` — App-CL процесс: строит Map-payload'ы и вызывает spike MBean через `MBeanServer.invoke`.

### 2.5 ArchUnit / fitness rules

`platform-tracing-test/src/main/java/.../arch/ArchitectureFitnessArchRules.java`:

- `SPIKE_MBEAN_VALIDATION_ONLY` (FF-08) — запрещает зависимости пакета `...jmx.spike..` на sampler/processor/exporter/autoconfigure.
- `PRODUCTION_AUTOCONFIGURE_NO_SPIKE_MBEAN` (FF-09b) — запрещает production autoconfigure зависеть на `...jmx.spike..`.

`platform-tracing-otel-extension/src/test/.../arch/SafeBoundaryArchTest.java`:

- `no_factory_spike_package_in_production` — уже фиксирует **прецедент** покончившего purge: «`factory.spike` пакет удалён; classloader visibility probe перенесён в test-only extension JAR (`platform-tracing-e2e-tests/src/testExtension`)».

### 2.6 Документация, зависящая от spike

- `docs/architecture/ADR-jmx-wire-map-contract.md`.
- `docs/architecture/platform-tracing-fitness-functions-implementation.md` (FF-07/FF-08, строки 62-63).
- `docs/architecture/classloader-visibility-spike-usage-inventory.md`.
- `docs/architecture/jmx-spike-package-inventory.md` (когда будет создан).

---

## 3. Target Architecture

### Остаётся в production (`src/main`)

- `TracingControlWireValidator.V1` и его API-пакет `...api.control.wire` в `platform-tracing-api`.
- Соседний production JMX `PlatformTracingControl` / `PlatformTracingControlMBean` (НЕ spike, не трогаем).

### Переносится в test-only (`platform-tracing-e2e-tests/src/testExtension`)

- `TracingControlWireSpikeMBean`, `TracingControlWireSpike`, `TracingControlWireSpikeProbe` (или их минимально необходимый эквивалент-харнесс).
- Новый `AutoConfigurationCustomizerProvider` (например `WireSpikeTestExtensionProvider`), который регистрирует spike MBean **безусловно при загрузке extension JAR** (без property gate), по образцу `ClassLoaderVisibilityTestProbe`.
- Регистрация SPI: `META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider`.

### Удаляется из production

- Весь пакет `...jmx.spike` из `src/main`.
- Bootstrap call-site `PlatformAutoConfigurationCustomizer.java:93-97`.
- Runtime gate `platform.tracing.spike.jmx.wire` из production-логики.
- Production spike MBean / ObjectName `type=WireSpike`.
- Production stderr-маркер `SPIKE_JMX_WIRE:` (логика маркеров переезжает в тест-харнесс).

### Тесты

- E2E `MapWireRoundTripSpikeE2ETest` / `MapWireRoundTripSpikeMain` — **сохраняются**, перевязываются на test-only extension JAR (MBean регистрируется provider'ом в Agent CL, без production gate).
- Unit `MapWireRoundTripSpikeTest` / `PlatformTracingControlWireSpikeTest` — переносятся к relocated harness или переписываются как validator-direct тесты.
- `SpikeMBeanPropertyGateArchTest` / `SpikeMBeanValidationOnlyArchTest` — удаляются (gate и production spike больше не существуют).

### Исчезающие production-гейты

- `platform.tracing.spike.jmx.wire` — больше не production runtime property; в тестах MBean поднимается явно.

---

## 4. Why This Is Not Cosmetic

- **Production reachability снята:** autoconfigure больше не вызывает `registerIfEnabled()`; spike-код не достижим из bootstrap.
- **JMX attack/debug surface снята:** production-артефакт перестаёт нести JMX-транспорт, регистрирующий MBean («disabled by default» не является защитой от JMX-векторов).
- **Spike runtime gate удалён:** property существует только ради активации spike — без backward-compat она не нужна.
- **MBean / ObjectName удалены из production:** debug-ориентированное имя `type=WireSpike` исчезает из `src/main` и `agentExtensionJar`.
- **Тестовый харнесс изолирован:** перемещён в test-only extension JAR; не пакуется в production.
- **ArchUnit-исключение → guardrail:** вместо специальной терпимости к `jmx.spike` добавляется явный запрет spike-кода в production.

---

## 5. Gradle / Source Set Feasibility

### Mandatory Technical Decision

```text
Таргет остаётся NEW_HYBRID / Production Purge with Test-Only Relocation.

Однако специфичная для репозитория реализация ОБЯЗАНА использовать
platform-tracing-e2e-tests/src/testExtension, а НЕ java-test-fixtures, потому что:
- java-test-fixtures не используется в репозитории;
- testFixtures оказались бы на test/application classpath;
- MapWireRoundTripSpikeE2ETest требует семантики Agent ExtensionClassLoader;
- в репозитории уже есть успешный прецедент: classloader visibility probe
  перенесён в test-only extension JAR.
```

### Текущие source sets

- `platform-tracing-otel-extension`: стандартные `main` / `test`. Плагин `java-test-fixtures` **не применяется**.
- `platform-tracing-e2e-tests` (`build.gradle`): кастомные sourceSets `customRule`, `testExtension`, `test`.
- Поиск `java-test-fixtures|testFixtures` по всем `*.gradle*` — **0 совпадений** во всём репозитории.

### Почему `testFixtures` не подходит

- `agentExtensionJar` собирается `from sourceSets.main.output` (+ `platform-tracing-api.jar` + `platform-tracing-core.jar`). `testFixtures` отдельного модуля в этот JAR **не попадут** — значит MBean не окажется в Agent `ExtensionClassLoader`.
- `testFixtures(project(...))` подключаются на test/app classpath, что разрушает cross-classloader контракт E2E (App CL → JMX → Agent CL).
- Добавление плагина `java-test-fixtures` ради сценария, который он архитектурно не закрывает, — лишний Gradle-риск.

### Рекомендуемый план source-set / packaging

По образцу существующего `testClassLoaderProbeJar` (e2e `build.gradle`):

- Разместить spike-харнесс в `platform-tracing-e2e-tests/src/testExtension` (расширить существующий sourceSet либо добавить аналогичный `wireSpikeExtension`).
- Зарегистрировать `AutoConfigurationCustomizerProvider` через `META-INF/services` в этом sourceSet.
- Завести Jar-task (например `wireSpikeProbeJar`, classifier `wire-spike`), embedding `platform-tracing-api` (`zipTree(platform-tracing-api.jar)`), т.к. OTel 2.28.x создаёт отдельный `ExtensionClassLoader` на каждый JAR.
- В `test`-таске добавить `dependsOn` на новый JAR и прокинуть его путь через systemProperty, копировать в `extDir` рядом с `extension.jar`.

### Доказательство чистоты production JAR

- Задача `verifyAgentJarContents` уже инспектирует содержимое `agentExtensionJar`; дополнить/использовать проверку отсутствия `space/br1440/platform/tracing/otel/extension/jmx/spike/` в JAR.
- Grep по `src/main` после cleanup (см. §12).

---

## 6. File-by-File Plan

| File | Action | Reason | Risk | Replacement |
|---|---|---|---|---|
| `.../jmx/spike/TracingControlWireSpikeMBean.java` (src/main) | Move → testExtension | JMX-контракт не должен быть в production | Medium | test-only MBean в `e2e-tests/src/testExtension` |
| `.../jmx/spike/TracingControlWireSpike.java` (src/main) | Move → testExtension | MBean impl validation-only, не production control plane | Medium | test-only impl + `registerSafely()` |
| `.../jmx/spike/TracingControlWireSpikeProbe.java` (src/main) | Move → testExtension (без production gate) | gate/маркеры только для E2E | Medium | test-only provider-driven регистрация |
| `PlatformAutoConfigurationCustomizer.java:93-97` | Edit (удалить call-site) | снять production reachability | Medium | нет (provider в тест-JAR) |
| `platform-tracing-otel-extension/build.gradle` | Возможен edit | возможна проверка `verifyAgentJarContents` на отсутствие spike | Low | guard на чистоту JAR |
| `platform-tracing-e2e-tests/build.gradle` | Edit | sourceSet/Jar-task/systemProperty для wire-spike extension JAR | Medium | `wireSpikeProbeJar` по образцу `testClassLoaderProbeJar` |
| `PlatformTracingControlWireSpikeTest` | Move/Rewrite | сейчас зависит от production spike | Medium | relocated harness или validator-direct |
| `MapWireRoundTripSpikeTest` | Rewrite | сохранить wire round-trip coverage | Medium | relocated harness или validator-direct |
| `MapWireRoundTripSpikeE2ETest` | Rebind | сохранить cross-CL E2E | Medium | грузить MBean из test-only extension JAR |
| `MapWireRoundTripSpikeMain` | Move/Rebind | App-CL invoker E2E | Medium | импорт из testExtension harness |
| `SpikeMBeanPropertyGateArchTest` | Delete | gate удалён из production | Low | новый production-ban guardrail |
| `SpikeMBeanValidationOnlyArchTest` | Delete | production spike MBean удалён | Low | новый production-ban guardrail |
| `ArchitectureFitnessArchRules.java` (FF-08, FF-09b) | Edit | убрать spike-specific rules, добавить production ban | Medium | `NO_SPIKE_CODE_IN_PRODUCTION` |
| `SafeBoundaryArchTest.java` | Edit | расширить запрет на `..spike..`/`*Spike*` | Medium | единый production guardrail |
| `ADR-jmx-wire-map-contract.md` | Rewrite | убрать легитимизацию spike в production | Low | test-only роль |
| `platform-tracing-fitness-functions-implementation.md` | Rewrite | FF-07/FF-08 retired | Low | production-hygiene rule |
| `classloader-visibility-spike-usage-inventory.md` | Rewrite | убрать spike-exemption нарратив | Low | — |
| `jmx-spike-package-inventory.md` | Update (если есть) | зафиксировать «spike не production» | Low | — |

---

## 7. Implementation Order

1. Добавить/подтвердить test-only source set (`platform-tracing-e2e-tests/src/testExtension`) и Jar-task `wireSpikeProbeJar` (по образцу `testClassLoaderProbeJar`).
2. Перенести JMX spike-харнесс (3 класса) в test-only source set + добавить `AutoConfigurationCustomizerProvider` и `META-INF/services`.
3. Перевязать unit/E2E-тесты на test-only харнесс.
4. Убедиться, что тесты проходят.
5. Удалить production bootstrap call-site `PlatformAutoConfigurationCustomizer.java:93-97`.
6. Удалить production-пакет `jmx.spike` из `src/main`.
7. Удалить production runtime gate `platform.tracing.spike.jmx.wire`.
8. Заменить spike-specific ArchUnit-правила/исключения на production-ban.
9. Обновить docs/ADR/inventory.
10. Прогнать grep-проверки и инспекцию production JAR.
11. Прогнать targeted + полную валидацию.

---

## 8. Test Strategy

- **Сохранить** validator-уровневые unit-тесты вокруг `TracingControlWireValidator.V1` в `platform-tracing-api` (стабильный production-контракт); при необходимости добавить новые.
- **Переписать/перенести** `MapWireRoundTripSpikeTest` без production spike — либо на relocated harness, либо как прямой validator round-trip.
- **Сохранить** `MapWireRoundTripSpikeE2ETest` через test-only extension JAR; MBean регистрируется provider'ом в Agent CL.
- **Test-only MBean регистрируется только в тестах** (через `AutoConfigurationCustomizerProvider` при загрузке test-extension JAR), не из production autoconfigure.
- **Production startup больше не вызывает** `TracingControlWireSpikeProbe.registerIfEnabled()`.
- **Production `agentExtensionJar` не содержит** классов `jmx.spike` (проверяется `verifyAgentJarContents`).
- **Property `platform.tracing.spike.jmx.wire`** отсутствует в production-логике; в E2E gate больше не нужен (provider активен при наличии JAR).
- **Удалить** `SpikeMBeanPropertyGateArchTest` и `SpikeMBeanValidationOnlyArchTest` (охраняли несуществующий production spike).

---

## 9. Architecture Guardrail Plan

Стиль — существующие `ArchitectureFitnessArchRules` / `SafeBoundaryArchTest`. Планируемые правила:

```text
- нет ..spike.. пакетов в production src/main;
- нет классов с simple name, содержащим "Spike", в production src/main
  (разрешено только для test/testExtension и исторических docs/тестов);
- нет production-зависимости на test-only JMX spike harness;
- нет production-ссылок на platform.tracing.spike.jmx.wire;
- нет production-ссылок на ObjectName type=WireSpike.
```

Эталон правила (по Adversarial Audit §8):

```java
@ArchTest
static final ArchRule NO_SPIKE_CODE_IN_PRODUCTION =
    noClasses()
        .that().resideInAPackage("space.br1440.platform.tracing..")
        .and().doNotResideInAnyPackage("..test..", "..testExtension..")
        .should().haveSimpleNameContaining("Spike")
        .because("Spike/Debug tools must remain in test/testExtension source sets");
```

`SafeBoundaryArchTest.no_factory_spike_package_in_production` уже задаёт прецедент-стиль (`..factory.spike..`); новое правило обобщает запрет на любой spike-код в production.

---

## 10. Docs / ADR Migration Plan

Обновить:

- `ADR-jmx-wire-map-contract.md` — «бывший JMX spike transport удалён из production-кода; wire-валидация остаётся через `TracingControlWireValidator.V1`; любой оставшийся JMX-харнесс — test-only».
- `platform-tracing-fitness-functions-implementation.md` — FF-07/FF-08 retired (production spike MBean удалён; production теперь enforces отсутствие spike-кода).
- `classloader-visibility-spike-usage-inventory.md` — убрать исключение для `jmx.spike`; production больше не несёт этот spike-пакет.
- `jmx-spike-package-inventory.md` / migration plan — зафиксировать, что JMX spike больше не production-код.

Также удалить упоминания `platform.tracing.spike.jmx.wire`, `SPIKE_JMX_WIRE:` и любой текст, легитимизирующий PR-3 spike как терпимый production-артефакт.

Обязательное финальное сообщение для документации:

```text
JMX wire spike transport has been removed from production code.
Any retained JMX wire harness exists only as test infrastructure.
TracingControlWireValidator / wire schema remains as stable API.
platform.tracing.spike.jmx.wire is not a production runtime property anymore.
```

---

## 11. Stop Conditions

Остановить реализацию и пересмотреть план, если:

- перенос харнесса попадает в production agent JAR (`agentExtensionJar` содержит spike-классы);
- E2E не может получить доступ к test-only extension JAR без широкой перестройки Gradle;
- `MapWireRoundTripSpikeE2ETest` пришлось бы удалить без замены;
- production `PlatformAutoConfigurationCustomizer` всё ещё обязан вызывать spike-код;
- wire-schema классы оказались случайно завязаны на `jmx.spike`;
- production JMX spike остаётся reachable после cleanup;
- ArchUnit-guardrail становится flaky или слишком широким;
- документация остаётся противоречивой;
- обнаружены дополнительные production call-sites помимо `PlatformAutoConfigurationCustomizer.java:93-97`;
- внешние scripts/CI завязаны на маркер `SPIKE_JMX_WIRE:` или ObjectName `type=WireSpike`.

---

## 12. Validation Commands

Targeted + полная валидация:

```bash
./gradlew :platform-tracing-otel-extension:test --continue
./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*MapWireRoundTripE2ETest*" --continue
./gradlew pr4ArchitectureFitnessVerify --continue
```

Проверки чистоты production:

```bash
rg "space\.br1440\.platform\.tracing\.otel\.extension\.jmx\.spike" platform-tracing-otel-extension/src/main
rg "TracingControlWireSpike|platform\.tracing\.spike\.jmx\.wire|SPIKE_JMX_WIRE|WireSpike" platform-tracing-otel-extension/src/main
rg "registerIfEnabled" platform-tracing-otel-extension/src/main
```

Инспекция production JAR (отсутствие spike-классов):

```bash
./gradlew :platform-tracing-otel-extension:verifyAgentJarContents
# дополнительно: убедиться, что в agentExtensionJar нет
# entry space/br1440/platform/tracing/otel/extension/jmx/spike/*.class
```

Ожидаемые результаты: все три grep по `src/main` — 0 совпадений; `verifyAgentJarContents` — без spike-классов; targeted/E2E/ArchUnit — зелёные.

---

## 13. Final Status

```text
Planning status: COMPLETED
Code changes performed: NO
Ready for implementation prompt: YES
```
