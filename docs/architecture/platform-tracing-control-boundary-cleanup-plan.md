# PlatformTracingControl Boundary Cleanup Plan

> **СТАТУС: IMPLEMENTED (2026-06-18, отклонение устранено).** Реализация `AGGRESSIVE_FULL_BOUNDARY_CLEANUP` выполнена.
> Итог:
> - `PlatformTracingControl` — чистая реализация MBean: один **package-private** канонический конструктор, без публичных конструкторов, без `registerSafely`.
> - `PlatformTracingControl` не предоставляет registration API.
> - `PlatformTracingJmxRegistrar` **перенесён в пакет `jmx`**, потому что он — единственный владелец жизненного цикла JMX `PlatformTracingControl`. Будучи в одном пакете, registrar напрямую вызывает package-private канонический конструктор; терминальная логика регистрации (бывш. R8) встроена в `tryRegisterMBean()`.
> - **Публичного хелпера `PlatformTracingControlRegistration` больше нет** — он удалён. Прежнее отклонение (публичный registration helper) устранено переносом registrar.
> - Тесты используют `PlatformTracingControlTestBuilder`; тесты регистрации/идемпотентности используют `PlatformTracingJmxRegistrar` как production-owned путь.
> - Тайминг ранней регистрации MBean намеренно **не менялся** (отдельный follow-up).
>
> **Тип документа:** план реализации. Ниже — исходный план.
>
> **Дата:** 2026-06-18
>
> **Источник истины:** `docs/architecture/platform-tracing-control-constructor-inventory.md` + текущий исходный код.
>
> **Примечание о приоритете источников:** документы `Final Decision Memo Reset`, `Kimi Feasibility Audit Reset`, `Adversarial Audit Reset — PlatformTracingControl Constructor Cleanup` **не найдены** в репозитории (`Not found`). План построен на inventory (приоритет 3) и текущем коде (приоритет 5).

---

## 1. Executive Summary

- **Цель:** `AGGRESSIVE_FULL_BOUNDARY_CLEANUP` — убрать публичную поверхность construction/registration у `PlatformTracingControl`, оставив класс чистой реализацией MBean.
- **Текущее состояние API:** **7 публичных конструкторов** (C1–C7) и **8 статических `registerSafely(...)`** перегрузок (R1–R8).
- **Production-путь:** единственный — `PlatformTracingJmxRegistrar.tryRegisterMBean()` → `registerSafely` R8 (9-arg) → `new PlatformTracingControl(...)` C7 (9-arg). Прямого `new PlatformTracingControl` в `src/main` **нет**.
- **Что делает cleanup:**
  - оставляет только канонический 9-arg конструктор **C7**, делает его **package-private**;
  - удаляет C1–C6;
  - удаляет все `registerSafely` R1–R8;
  - переносит терминальную логику регистрации MBean в `PlatformTracingJmxRegistrar`;
  - переводит тесты на `PlatformTracingControlTestBuilder` (src/test);
  - обновляет stale docs/UML.
- **Это не косметика:** меняется владелец регистрации (ownership), сокращается public API, ложные публичные контракты исчезают.
- **Early-registration timing — отдельно:** текущий тайминг регистрации (MBean регистрируется на первом `setConfigHolder()` до span processors) **сохраняется без изменений** и выносится в отдельный follow-up.

---

## 2. Current Evidence

### 2.1 Конструкторы (C1–C7)

| # | Сигнатура (кратко) | Делегирует | Classification (inventory) |
|---|--------------------|------------|----------------------------|
| C1 | `(SamplerStateHolder)` | C3 | TEST_ONLY |
| C2 | `(SamplerStateHolder, SpanWatchdogProcessor)` | C3 | TEST_ONLY |
| C3 | `(SamplerStateHolder, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | C4 | TEST_ONLY |
| C4 | `(SamplerStateHolder, CompositeSampler, SpanWatchdogProcessor, PlatformCompositeSpanProcessor)` | C6 | TEST_ONLY |
| C5 | `(…, MetricsSpanProcessor, ScrubbingSpanProcessor)` (6-arg) | C7 (+NULL_SUPPLIER×2) | TEST_ONLY |
| C6 | `(…, Supplier×2)` (8-arg, validating=null) | C7 | TEST_ONLY |
| **C7** | **9-arg full (incl. validating + оба supplier)** | **terminal** | **PRODUCTION_REQUIRED** |

### 2.2 registerSafely (R1–R8)

| # | Сигнатура (кратко) | Регистрирует MBean | Classification |
|---|--------------------|--------------------|----------------|
| R1 | `(SamplerStateHolder)` | нет (chain) | TEST_ONLY (3 вызова в `PlatformTracingControlTest`) |
| R2 | `(…, watchdog)` | нет | UNUSED |
| R3 | `(…, watchdog, composite)` | нет | UNUSED |
| R4 | `(…, compositeSampler, watchdog, composite)` | нет | UNUSED |
| R5 | `(…, metrics, scrubbing)` | нет | UNUSED |
| R6/R7 | 8-arg с suppliers, validating=null | нет | UNUSED / OBSOLETE |
| **R8** | **9-arg full** | **да (terminal)** | **PRODUCTION_REQUIRED** |

### 2.3 Production call-sites

| Call-site | API |
|-----------|-----|
| `PlatformTracingJmxRegistrar.java:99-104` | `PlatformTracingControl.registerSafely(...)` R8 |
| `PlatformTracingControl.java:778` | `new PlatformTracingControl(...)` C7 — **внутри** R8 |

Прямого `new PlatformTracingControl` в production вне R8 — **Not found**.

### 2.4 Test call-sites (constructors + registerSafely)

| Файл | Использование |
|------|---------------|
| `PlatformTracingControlTest` | C1 (×12), C2, C3, C6; R1 (×3) |
| `SamplingPolicyRuntimeUpdateJmxTest` | C1 (×3), C4 |
| `ScrubbingPolicyRuntimeUpdateJmxTest` | C5 (×8) |
| `ValidationPolicyRuntimeUpdateJmxTest` | C7 via `controlWith()` helper |
| `ValidationStrictRuntimeGuardTest` | C7 via `controlWith()` helper |
| `PlatformAutoConfigurationCustomizerProcessorsTest` | indirect — bootstrap → MBeanServer (без прямого конструктора) |

### 2.5 Канонический путь

- **Канонический конструктор:** C7 (9-arg, terminal, прямая инициализация полей).
- **Канонический production registration:** R8 (9-arg, единственный, кто вызывает `MBeanServer.registerMBean`).

### 2.6 Stale docs / UML

| Документ | Проблема | Статус |
|----------|----------|--------|
| `docs/architecture/Components_v1.puml:110,260` | `registerSafely(sampler, watchdog?) : boolean` — старая 2-arg сигнатура | **stale** |
| `docs/architecture/platform-tracing-classes.puml:438` | `{static} +registerSafely(...): void` + поля `sampler/watchdog` не соответствуют реальным | **stale** |
| `docs/architecture/platform-tracing-control-constructor-inventory.md` | source-of-truth для cleanup; после реализации потребует пометки «реализовано» | active |

---

## 3. Target Architecture

### 3.1 `PlatformTracingControl` — чистая реализация MBean

- Реализует `PlatformTracingControlMBean`; никаких публичных конструкторов и `registerSafely`.
- Единственный конструктор — **package-private** C7 (9-arg).
- `NULL_SUPPLIER` остаётся (используется тестовым билдером для дефолтов export-поставщиков, либо переносится в билдер — см. §7).
- Все MBean-операции, поля, null-tolerant поведение, `invalidConfigCounter`, обращения к singleton'ам (`PlatformPropagationGate`, `ConfigReloadDiagnostics`, `PlatformLogControl`, `TracingDiagnostics`) — **без изменений**.

### 3.2 `PlatformTracingJmxRegistrar` — единственный владелец регистрации

- Содержит терминальную логику бывшего R8: lookup `MBeanServer`, создание `ObjectName`, `new PlatformTracingControl(...)`, `registerMBean(...)`, обработка `InstanceAlreadyExistsException` / `Throwable`, success-логирование с теми же dependency-флагами.
- **Требует package-доступа** к package-private C7. `PlatformTracingJmxRegistrar` находится в пакете `...otel.extension.factory`, а `PlatformTracingControl` — в `...otel.extension.jmx`. **Это разные пакеты** → package-private конструктор из `factory` **недоступен**.

  **Решение (выбранное):** разместить терминальную регистрацию в **новом package-private статическом factory-методе внутри пакета `jmx`**, например `PlatformTracingControlRegistration.register(...)` (или package-private статический метод на самом `PlatformTracingControl`, видимый только внутри `jmx`), который `PlatformTracingJmxRegistrar` вызывает. Это сохраняет «registrar владеет регистрацией» как оркестратором, но фактический `new` + `registerMBean` остаётся в пакете `jmx` ради package-private конструктора.

  **Альтернатива (отклонена):** оставить конструктор C7 `public` — противоречит цели (package-private C7). Отклонено.

  **Альтернатива (отклонена):** переместить `PlatformTracingJmxRegistrar` в пакет `jmx` — расширяет diff за пределы согласованной цели и ломает существующие импорты фабрик. Отклонено (см. §11 stop-condition по широкому Gradle/перемещению).

  > **Открытый вопрос для implementation-промпта:** допустимо ли ввести package-private helper `PlatformTracingControlRegistration` в пакете `jmx`, или регистрацию обязательно держать в классе `PlatformTracingJmxRegistrar`. Если требуется строго в `PlatformTracingJmxRegistrar` без helper'а — единственный способ при package-private C7 — переместить registrar в пакет `jmx` (расширяет scope; кандидат на stop/уточнение).

### 3.3 Тесты

- Новый `PlatformTracingControlTestBuilder` (public, пакет `jmx`, src/test) вызывает package-private C7.
- Тесты из пакетов `sampler`, `scrubbing`, `processor` используют **public** билдер (cross-package), который внутри пакета `jmx` обращается к package-private C7.

### 3.4 Docs / UML

- `Components_v1.puml`, `platform-tracing-classes.puml` — убрать публичный `registerSafely`; показать registrar как владельца регистрации.
- Inventory — пометить статус «реализовано» в follow-up (в данном плане только указывается).

---

## 4. Why This Is Not Cosmetic

- **Ownership move:** терминальная регистрация (lookup/objectname/new/registerMBean/exception-handling) уходит из MBean-реализации к registrar-границе.
- **Сокращение public API:** 7 публичных конструкторов + 8 static-перегрузок → 1 package-private конструктор; 0 публичных registration-методов.
- **Test convenience покидает production API:** дефолты/sparse-конфигурации мигрируют в тестовый билдер.
- **Удаление ложных контрактов:** R2–R7 (UNUSED) и C2–C4 — публичные обещания, которые никто не использует.
- **Docs/UML отражают реальную архитектуру** вместо устаревшей 2-arg `registerSafely`.

---

## 5. Production Behavior Invariants

Сохраняются без изменений:

- `ObjectName` = `space.br1440.platform.tracing:type=Control,name=PlatformTracingControl`;
- интерфейс `PlatformTracingControlMBean` (сигнатуры всех операций);
- null-tolerant чтения (0 / empty / `-1` / `"unknown"`);
- исключения мутаторов (`IllegalStateException` при null `configHolder`/`scrubbing`/`validating`; `IllegalArgumentException` валидации);
- `invalidConfigCounter` семантика;
- `ConfigReloadDiagnostics` запись;
- late-binding export suppliers (`this::getExportProcessor`, `this::getSafeExporter`);
- **текущий тайминг регистрации** (первый `setConfigHolder()` → register, `mbeanRegistered` guard);
- `InstanceAlreadyExistsException` → `false` + debug; `Throwable` → `false` + warn; success → `true` + info с теми же флагами наличия зависимостей.

---

## 6. File-by-File Plan

| File | Action | Reason | Risk | Validation |
|------|--------|--------|------|------------|
| `platform-tracing-otel-javaagent-extension/.../jmx/PlatformTracingControl.java` | Удалить C1–C6; сделать C7 package-private; удалить R1–R8; (опц.) убрать/переместить `NULL_SUPPLIER` в билдер; добавить package-private registration helper если выбран вариант §3.2 | Класс становится чистой MBean-реализацией | High (компиляция тестов и registrar) | `:platform-tracing-otel-javaagent-extension:test` |
| `platform-tracing-otel-javaagent-extension/.../factory/PlatformTracingJmxRegistrar.java` | Заменить вызов `PlatformTracingControl.registerSafely(...)` на вызов нового package-private registration helper в пакете `jmx`; перенести exception/log поведение туда | Registrar — владелец регистрации | High | targeted registrar тест отсутствует → через `PlatformAutoConfigurationCustomizerProcessorsTest` |
| `platform-tracing-otel-javaagent-extension/.../jmx/PlatformTracingControlRegistration.java` *(новый, опц.)* | Package-private terminal registration (MBeanServer/ObjectName/new C7/registerMBean/exceptions/logs) | Package-доступ к C7 | Medium | как выше |
| `platform-tracing-otel-javaagent-extension/src/test/.../jmx/PlatformTracingControlTestBuilder.java` *(новый)* | Public builder, вызывает package-private C7 | Замена convenience-конструкторов | Medium | компиляция всех JMX-тестов |
| `platform-tracing-otel-javaagent-extension/src/test/.../jmx/PlatformTracingControlTest.java` | Переписать C1/C2/C3/C6 + R1 на билдер; регистрацию — через registrar/ helper | Снять зависимость от удаляемого API | Medium | `--tests "*PlatformTracingControlTest*"` |
| `.../src/test/.../sampler/SamplingPolicyRuntimeUpdateJmxTest.java` | C1/C4 → builder | то же | Medium | `--tests "*SamplingPolicyRuntimeUpdateJmxTest*"` |
| `.../src/test/.../scrubbing/ScrubbingPolicyRuntimeUpdateJmxTest.java` | C5 (×8) → builder | то же | Medium | `--tests "*ScrubbingPolicyRuntimeUpdateJmxTest*"` |
| `.../src/test/.../processor/ValidationPolicyRuntimeUpdateJmxTest.java` | `controlWith()` C7 → builder | то же | Low | `--tests "*ValidationPolicyRuntimeUpdateJmxTest*"` |
| `.../src/test/.../processor/ValidationStrictRuntimeGuardTest.java` | `controlWith()` C7 → builder | то же | Low | `--tests "*ValidationStrictRuntimeGuardTest*"` |
| `.../src/test/.../PlatformAutoConfigurationCustomizerProcessorsTest.java` | Проверить, что MBean регистрация по-прежнему зелёная после переноса; правок логики ожидать не должно | Регрессионный guard | Low | `--tests "*PlatformAutoConfigurationCustomizerProcessorsTest*"` |
| `docs/architecture/Components_v1.puml` | Убрать `registerSafely(sampler, watchdog?)`; registrar как владелец | Stale UML | Low | визуальный review |
| `docs/architecture/platform-tracing-classes.puml` | Убрать `{static} +registerSafely(...)`; актуализировать поля | Stale UML | Low | визуальный review |
| `docs/architecture/platform-tracing-control-constructor-inventory.md` | Пометить как реализованное (banner) | Консистентность | Low | review |

---

## 7. Test Builder Design

- **Имя класса:** `PlatformTracingControlTestBuilder`
- **Видимость:** `public` (нужно для cross-package тестов из `sampler`/`scrubbing`/`processor`).
- **Пакет:** `space.br1440.platform.tracing.otel.javaagent.jmx` — тот же, что у `PlatformTracingControl`, чтобы иметь доступ к package-private C7.
- **Расположение:** `platform-tracing-otel-javaagent-extension/src/test/java/space/br1440/platform/tracing/otel/javaagent/jmx/PlatformTracingControlTestBuilder.java`
- **Поля** (соответствуют параметрам C7):
  - `SamplerStateHolder configHolder = null`
  - `CompositeSampler compositeSampler = null`
  - `SpanWatchdogProcessor watchdog = null`
  - `PlatformCompositeSpanProcessor composite = null`
  - `MetricsSpanProcessor metrics = null`
  - `ScrubbingSpanProcessor scrubbing = null`
  - `ValidatingSpanProcessor validating = null`
  - `Supplier<PlatformDropOldestExportSpanProcessor> exportProcessorSupplier = () -> null`
  - `Supplier<SafeSpanExporter> safeExporterSupplier = () -> null`
- **Дефолты соответствуют старому поведению C1–C6:** все зависимости `null`, export-поставщики — `() -> null` (эквивалент `NULL_SUPPLIER`).
- **Fluent-методы:** `withConfigHolder(...)`, `withCompositeSampler(...)`, `withWatchdog(...)`, `withComposite(...)`, `withMetrics(...)`, `withScrubbing(...)`, `withValidating(...)`, `withExportProcessorSupplier(...)`, `withSafeExporterSupplier(...)`.
- **`build()`** вызывает package-private C7 со всеми 9 полями.
- **Helper-методы для частых sparse-конфигураций** (опционально): `samplingOnly(holder)`, `withScrubbingProcessor(processor)`, `withValidatingProcessor(processor)`, `lateBindingExport(procSupplier, exporterSupplier)` — повторяют то, что раньше давали C1/C5/C6/C7.
- **Запрещено:** использовать production convenience-конструкторы (их не будет) и reflection (package-private доступ обеспечен размещением билдера в пакете `jmx`).

---

## 8. Registration Migration Plan

Перенести терминальную логику бывшего R8 в пакет `jmx` (package-private helper, вызываемый из `PlatformTracingJmxRegistrar`):

```text
MBeanServer server = ManagementFactory.getPlatformMBeanServer();
ObjectName objectName = new ObjectName(PlatformTracingControlMBean.OBJECT_NAME);
try {
    server.registerMBean(new PlatformTracingControl(           // package-private C7
        configHolder, compositeSampler, watchdog, composite,
        metrics, scrubbing, validating,
        exportProcessorSupplier, safeExporterSupplier), objectName);
    log.info("PlatformTracingControl MBean зарегистрирован: {}, configHolder={}, ... validating={}", ...);
    return true;
} catch (InstanceAlreadyExistsException e) {
    log.debug("PlatformTracingControl MBean уже зарегистрирован: {}", objectName);
    return false;
} catch (Throwable t) {
    log.warn("Не удалось зарегистрировать PlatformTracingControl MBean: {}", t.getMessage());
    return false;
}
```

- `PlatformTracingJmxRegistrar.tryRegisterMBean()` сохраняет gate (`configHolder != null`, `mbeanRegistered`) и вызывает helper, передавая те же аргументы (`this::getExportProcessor`, `this::getSafeExporter` как lazy suppliers).
- Поведение логирования и возврат `boolean` сохраняются 1:1.

---

## 9. Test Rewrite Plan

| Файл | Текущее использование | Замена на builder | Сохранить ассерты | Риски |
|------|-----------------------|-------------------|-------------------|-------|
| `PlatformTracingControlTest` | C1 (sampling CRUD, null-tolerant stats, export zeros), C2 (watchdog), C3 (processor errors), C6 (late-binding); R1 (registration + idempotency) | `builder().withConfigHolder(h).build()`, `.withWatchdog(w)`, `.withComposite(c)`, `.lateBindingExport(...)`; регистрацию — через registrar/helper путь | все sampling/stat/export ассерты; idempotency (`registered=false` при повторе) | регистрация теперь не на `PlatformTracingControl.registerSafely` — тест регистрации переписать на новый registration helper/registrar |
| `SamplingPolicyRuntimeUpdateJmxTest` | C1, C4 `(holder, sampler, null, null)` | `builder().withConfigHolder(h).withCompositeSampler(s).build()` | атомарность policy update, версия, LKG, integration с CompositeSampler | нет |
| `ScrubbingPolicyRuntimeUpdateJmxTest` | C5 `(null×5, processor)` ×8 | `builder().withScrubbing(processor).build()` | scrubbing policy version/source/rules, LKG, concurrency | нет |
| `ValidationPolicyRuntimeUpdateJmxTest` | C7 helper `(null×6, processor, ()->null, ()->null)` | `builder().withValidating(processor).build()` | validation policy version/source, concurrency | нет |
| `ValidationStrictRuntimeGuardTest` | C7 helper | `builder().withValidating(processor).build()` | strict runtime guard поведение | нет |
| `PlatformAutoConfigurationCustomizerProcessorsTest` | indirect MBean через bootstrap | без изменений логики; только убедиться, что регистрация зелёная | MBean зарегистрирован, get/set SamplingRatio | если registration helper меняет тайминг — **stop** (см. §11) |

---

## 10. Docs / UML Plan

Обновить (в implementation PR, не сейчас) так, чтобы документы утверждали:

- у `PlatformTracingControl` **нет** публичного `registerSafely` API;
- регистрацию MBean владеет `PlatformTracingJmxRegistrar` (терминальная логика в пакете `jmx` helper'е);
- конструктор `PlatformTracingControl` — package-private;
- тесты используют `PlatformTracingControlTestBuilder`.

Конкретно:
- `Components_v1.puml:110,260` — заменить `registerSafely(sampler, watchdog?)` на связь «registrar → jmx registration».
- `platform-tracing-classes.puml:437-441` — убрать `{static} +registerSafely(...)`, привести поля к реальным (9 зависимостей или обобщённо).
- `platform-tracing-control-constructor-inventory.md` — добавить banner «реализовано (boundary cleanup)».

---

## 11. Stop Conditions

Остановиться и доложить, если реализация потребует:

- изменить `ObjectName`;
- изменить поведение `InstanceAlreadyExistsException`;
- изменить поведение `Throwable`-ветки;
- изменить early-registration timing (или тайминг нужен для зелёных тестов);
- изменить null-tolerant чтения;
- изменить исключения мутаторов;
- чтобы тесты проходили только через production convenience-конструкторы;
- билдер потребовал reflection из-за package/source-set проблем;
- production-код всё ещё вызывает `PlatformTracingControl.registerSafely`;
- единственный способ «registration в самом `PlatformTracingJmxRegistrar`» — переместить registrar в пакет `jmx` (широкое изменение) **и** helper-вариант §3.2 отвергнут ревьюером.

---

## 12. Validation Commands

Полная валидация:

```bash
./gradlew :platform-tracing-otel-javaagent-extension:test --continue
./gradlew pr4ArchitectureFitnessVerify --continue
```

Targeted:

```bash
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*PlatformTracingControlTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*SamplingPolicyRuntimeUpdateJmxTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*ScrubbingPolicyRuntimeUpdateJmxTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*ValidationPolicyRuntimeUpdateJmxTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*ValidationStrictRuntimeGuardTest*" --continue
./gradlew :platform-tracing-otel-javaagent-extension:test --tests "*PlatformAutoConfigurationCustomizerProcessorsTest*" --continue
```

Grep-проверки:

```bash
rg "PlatformTracingControl\.registerSafely" .
rg "registerSafely\(" platform-tracing-otel-javaagent-extension/src/main
rg "new PlatformTracingControl" platform-tracing-otel-javaagent-extension/src/main
rg "public PlatformTracingControl\(" platform-tracing-otel-javaagent-extension/src/main
```

Ожидаемо после реализации:

- нет ссылок `PlatformTracingControl.registerSafely` (ни в src, ни в docs кроме исторических);
- нет `registerSafely` в `PlatformTracingControl`;
- `new PlatformTracingControl` в `src/main` — только в пакете `jmx` (registration helper), вызываемый registrar'ом;
- нет `public PlatformTracingControl(` конструкторов.

---

## 13. Separate Follow-Up: Early Registration Timing

- **Суть проблемы:** `PlatformTracingJmxRegistrar.tryRegisterMBean()` срабатывает на первом `setConfigHolder()` (фаза `addSamplerCustomizer`) — **до** того как span processors (watchdog/composite/metrics/scrubbing/validating) зарегистрированы в фазе `addTracerProviderCustomizer`. В результате live MBean-инстанс может иметь null-ссылки на эти процессоры (export — через lazy suppliers, не затронут).
- **Почему вне scope:** это поведенческая правка тайминга/последовательности bootstrap, ортогональная cleanup'у API-границы; смешивание увеличивает риск регрессий и нарушает инвариант §5.
- **Предлагаемый follow-up:** отдельный dossier/PR `PlatformTracingJmxRegistrar Registration Timing` — рассмотреть (a) отложенную регистрацию до полного набора зависимостей, либо (b) безопасную перерегистрацию/обновление ссылок, с E2E-проверкой JMX-метрик процессоров в agent runtime.

---

## 14. Final Status

```text
Planning status: COMPLETED
Code changes performed: NO
Ready for implementation prompt: YES
```
