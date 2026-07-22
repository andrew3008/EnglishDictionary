# Baggage Propagation Customization Boundary Plan

## 1. Executive Summary

Цель рефакторинга — `FULL_PROPAGATION_BOUNDARY`:

- Финальная цель — выровненная граница ответственности в пакете `propagation`.
- Это не косметическое переименование: убирается ложная абстракция фабрики, разделяются детекция и оборачивание, изолируется знание о конкретном типе propagator.
- Старый класс `PlatformPropagatorFactory` удалён без замены с тем же именем.
- Логика кастомизации перемещена в пакет `propagation`, где уже живут `FilteringBaggagePropagator`, `SafeTextMapPropagator`, `PropagationDefaults`.
- Знание о `W3CBaggagePropagator` изолировано в package-private классе `BaggagePropagatorTypeDetector`; ни один другой production-класс не импортирует его напрямую.
- Тест на composite propagator кодирует safety: composite не является `W3CBaggagePropagator`, детектор возвращает `false`, оборачивание не происходит.

## 2. Current Evidence

**Расположение:**
`platform-tracing-otel-javaagent-extension/src/main/java/space/br1440/platform/tracing/otel/extension/factory/PlatformPropagatorFactory.java`

**Метод:**
```java
public TextMapPropagator customizePropagator(TextMapPropagator propagator, ConfigProperties config)
```

**Строковое определение типа:**
```java
propagator.getClass().getName().contains("W3CBaggagePropagator")
```

**Поведение при обнаружении:**
Оборачивает propagator в `SafeTextMapPropagator(FilteringBaggagePropagator(...))` при `isBaggageEnabled(config) == true`.

**Точки использования:**
1. `PlatformAutoConfigurationCustomizer` — поле `propagatorFactory` (строка 30), метод-ссылка `propagatorFactory::customizePropagator` (строка 72).

**Тесты:** тестов для `PlatformPropagatorFactory` не было.

## 3. Target Architecture

**`BaggagePropagatorTypeDetector`**
- Ответственность: единственная точка знания о конкретном типе `W3CBaggagePropagator`.
- Пакет: `space.br1440.platform.tracing.otel.extension.propagation`
- Видимость: package-private final (используется только из `BaggagePropagationCustomizer` в том же пакете).
- Метод: `static boolean isW3cBaggagePropagator(TextMapPropagator propagator)` — `instanceof W3CBaggagePropagator`.

**`BaggagePropagationCustomizer`**
- Ответственность: решение «оборачивать или пропускать» на основе типа и конфигурации.
- Пакет: `space.br1440.platform.tracing.otel.extension.propagation`
- Видимость: public final.
- Метод: `public TextMapPropagator apply(TextMapPropagator propagator, ConfigProperties config)`.
- Не импортирует `W3CBaggagePropagator` напрямую — делегирует в `BaggagePropagatorTypeDetector`.

**Неизменные классы:** `FilteringBaggagePropagator`, `SafeTextMapPropagator`, `PropagationDefaults`, порядок оборачивания, логика allowlist/deny-patterns, ключи конфигурации.

**Точка подключения:** `PlatformAutoConfigurationCustomizer.customize(...)` — `customizer.addPropagatorCustomizer(baggageCustomizer::apply)`.

## 4. Why This Is Not Cosmetic

- **Пакетная ответственность исправлена:** логика customization propagator принадлежит пакету `propagation`, а не `factory`.
- **Ложная абстракция фабрики удалена:** `PlatformPropagatorFactory` не создавала объекты предметной области — она была точкой условной логики; `BaggagePropagationCustomizer` описывает роль точно.
- **Детекция и оборачивание разделены:** `BaggagePropagatorTypeDetector` (кто это?) и `BaggagePropagationCustomizer` (что делать?) — разные классы с разными обязанностями.
- **Только один класс знает о `W3CBaggagePropagator`:** ArchUnit guardrail G-PROP-2 делает это ограничение машиночитаемым.
- **Тесты кодируют composite safety:** composite propagator → `false` гарантирует, что будущие изменения в OTel SDK (если `addPropagatorCustomizer` вдруг начнёт получать composite) не вызовут ложное оборачивание.
- **Guardrail предотвращает регресс:** G-PROP-1 запрещает `Class.getName().contains(...)` в production-коде модуля.

## 5. OpenTelemetry API Decision

- **Semantic Conventions** описывают семантику атрибутов spans и events, а не runtime-идентичность propagator-объектов. Использовать их для определения типа propagator — семантическая ошибка.
- **Никаких fake-констант** (например, `private static final String W3C_BAGGAGE = "W3CBaggagePropagator"`) не добавлялось: строковая константа не устраняет хрупкость строковой проверки.
- **`fields()`-based detection небезопасен:** `TextMapPropagator.composite(...)` возвращает объединение полей всех составляющих, что даёт false positive для composite, содержащего W3C baggage propagator.
- **Выбран type-based detection через публичный API OTel:** `W3CBaggagePropagator` — публичный final class, `instanceof` — стабильная проверка, не зависящая от имени класса в строке.
- **Config-based detection слабее object-based:** конфигурация описывает намерение оператора, но не гарантирует, что фактический объект propagator соответствует этому намерению. Object-based detection проверяет реальный объект на горячем пути.

## 6. File-by-File Plan

| Файл | Действие | Причина | Риск |
|---|---|---|---|
| `factory/PlatformPropagatorFactory.java` | Удалён | Ложная абстракция фабрики; brittle string detection | Все ссылки убраны |
| `propagation/BaggagePropagatorTypeDetector.java` | Создан | Изоляция знания о W3CBaggagePropagator | Нет: package-private |
| `propagation/BaggagePropagationCustomizer.java` | Создан | Замена factory с корректным именем и пакетом | Нет: семантика сохранена |
| `PlatformAutoConfigurationCustomizer.java` | Изменён | Замена поля и метод-ссылки | Минимальный: только 2 строки |
| `propagation/BaggagePropagatorTypeDetectorTest.java` | Создан | 4 test cases: W3C→true, composite→false, TraceContext→false, custom→false | Нет |
| `propagation/BaggagePropagationCustomizerTest.java` | Создан | 5 test cases: enable/disable/non-baggage/composite/custom | Нет |
| `arch/PropagationBoundaryArchTest.java` | Создан | G-PROP-1 (no getName), G-PROP-2 (only detector imports W3C) | Нет: scope ограничен |
| `docs/architecture/baggage-propagation-customization-boundary-plan.md` | Создан | Документирование решения | Нет |

## 7. Implementation Order

1. Создать `BaggagePropagatorTypeDetector` и тест.
2. Создать `BaggagePropagationCustomizer` и тест.
3. Обновить wiring в `PlatformAutoConfigurationCustomizer`.
4. Удалить `PlatformPropagatorFactory`.
5. Добавить `PropagationBoundaryArchTest` (ArchUnit guardrail).
6. Запустить тесты и grep-проверки.
7. Обновить/создать документацию.

## 8. Test Plan

Обязательные тесты:

- `W3CBaggagePropagator.getInstance()` → детектор возвращает `true`.
- `TextMapPropagator.composite(W3CBaggagePropagator.getInstance(), W3CTraceContextPropagator.getInstance())` → детектор возвращает `false`.
- `W3CTraceContextPropagator.getInstance()` → детектор возвращает `false`.
- Произвольный `TextMapPropagator` (anonymous class) → детектор возвращает `false`.
- W3C baggage + `baggage.enabled=true` → `apply()` возвращает `SafeTextMapPropagator`.
- W3C baggage + `baggage.enabled=false` → `apply()` возвращает тот же экземпляр (`isSameAs`).
- Не-baggage propagator + `baggage.enabled=true` → `apply()` возвращает тот же экземпляр.
- Composite propagator + `baggage.enabled=true` → `apply()` возвращает тот же экземпляр.
- Произвольный propagator + `baggage.enabled=true` → `apply()` возвращает тот же экземпляр.
- Отсутствие строкового литерала `"W3CBaggagePropagator"` в production-коде (ArchUnit G-PROP-1 через запрет `Class.getName()`).
- Отсутствие ссылок на `PlatformPropagatorFactory` (ArchUnit + grep).

## 9. Architecture Guardrail Plan

ArchUnit уже используется в проекте (7 тест-файлов в `platform-tracing-otel-javaagent-extension`).

Добавлен новый `PropagationBoundaryArchTest` с двумя правилами:

**G-PROP-1 — `no_class_getName_in_production`:**
```java
noClasses()
    .that().resideInAPackage("space.br1440.platform.tracing.otel.extension..")
    .should().callMethodWhere(/* java.lang.Class.getName() */)
    .because("Class.getName().contains(...) — хрупкое строковое определение типа")
```

**G-PROP-2 — `only_detector_depends_on_w3c_baggage_propagator`:**
```java
noClasses()
    .that().resideInAPackage("space.br1440.platform.tracing.otel.extension..")
    .and().doNotHaveSimpleName("BaggagePropagatorTypeDetector")
    .should().dependOnClassesThat()
    .haveFullyQualifiedName("io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator")
```

Правила сфокусированы: не затрагивают инфраструктуру за пределами otel-extension.

## 10. Stop Conditions

Остановиться, если:

- `W3CBaggagePropagator` недоступен как публичный тип в зависимостях модуля.
- Wiring требует точного имени старого класса из framework/ServiceLoader.
- Composite propagator нельзя сконструировать в тестах.
- `fields()`-based detection стала необходимой.
- Реализация потребовала бы изменения семантики `FilteringBaggagePropagator`.
- Реализация потребовала бы fake-констант или использования Semantic Conventions.
- ArchUnit guardrail становится слишком широким или нестабильным.
- Реализация потребовала бы нового top-level модуля.

## 11. Validation Commands

```bash
./gradlew :platform-tracing-otel-javaagent-extension:test --continue
./gradlew pr4ArchitectureFitnessVerify --continue
```

Целевые тест-классы:
```bash
./gradlew :platform-tracing-otel-javaagent-extension:test \
  --tests "*.BaggagePropagatorTypeDetectorTest" \
  --tests "*.BaggagePropagationCustomizerTest" \
  --tests "*.PropagationBoundaryArchTest"
```

## 12. Grep Checks

```bash
rg "PlatformPropagatorFactory" .
rg "\"W3CBaggagePropagator\"" platform-tracing-otel-javaagent-extension/src/main/java
rg "getClass\(\)\.getName\(\).*contains|getName\(\)\.contains" platform-tracing-otel-javaagent-extension/src/main/java
rg "BaggagePropagationCustomizer|BaggagePropagatorTypeDetector" .
```

## 13. Final Status

```text
Planning status: COMPLETED
No code changes performed in plan phase.
Ready for implementation prompt.
```
