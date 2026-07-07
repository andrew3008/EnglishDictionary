# Тестовые аннотации для platform-tracing-test: исследование и решение

> **Статус:** принято. `@OtelSdkTest` — вводим сейчас. `@ExpectSpanCount` — отложено.

## 1. TL;DR

**Принято: `@OtelSdkTest` сейчас, `@ExpectSpanCount` отложить.**

`@OtelSdkTest` — composite-аннотация (~75 строк с Javadoc, ноль runtime-логики), объединяющая
`@ExtendWith(OtelSdkExtension.class)` и `@Tag("tracing")`. Два независимых прецедента:

1. Spring Boot `@DataJpaTest` / `@WebMvcTest` — composite поверх `@ExtendWith`.
2. Spring Boot 4.0 `@AutoConfigureTracing` (ноябрь 2025) — первая официальная
   tracing-аннотация в крупной экосистеме.

`@ExpectSpanCount` намеренно не вводим: нулевой прецедент за 5+ лет в OTel SDK Testing
и Micrometer Tracing, создаёт debug-pain, конфликтует с явным `exporter.reset()`.
Решение откладывается до сбора реальных метрик после миграции Серий 5-6.

---

## 2. Индустриальный ландшафт 2025-2026

### OpenTelemetry Java SDK Testing

- `OpenTelemetryExtension.create()` регистрируется через `@RegisterExtension`.
  Аннотаций нет — намеренная позиция, не упущение.
- **Активный баг #7919** (декабрь 2025): `AfterAllCallback` закрывает SDK преждевременно при `@Nested`.
  `OtelSdkExtension` использует `CloseableResource` — проблема отсутствует по построению.

### Micrometer Tracing Test

- `SimpleTracer` + `TracerAssert` + `SampleTestRunner`. Аннотаций нет за 5+ лет.

### Spring Boot 4.0 (ноябрь 2025)

- Введён `@AutoConfigureTracing` — прямой прецедент для `@OtelSdkTest`.
- Удалён `@AutoConfigureObservability` как "too broad". Индустрия движется к минимализму.

### JUnit 6 GA (сентябрь 2025)

- Улучшена поддержка `@Nested`-иерархий и ancestor-lookup в `ExtensionContext`.
  Та же проблема, которую `OtelSdkExtension` решил превентивно.

---

## 3. Текущее состояние platform-tracing-test

| Задача | Текущее решение | Нужна аннотация? |
|--------|----------------|-----------------|
| SDK + exporter в тесте | `ParameterResolver` | Нет — автоматически |
| Кастомный Sampler/Processor | `builder().sampler(...)` | Нет — builder чище |
| Scope (METHOD/CLASS/SHARED) | `methodScope()` / `classScope()` | Нет — дублирование |
| CI-категоризация | ручной `@Tag("tracing")` + `@ExtendWith` | **Да — `@OtelSdkTest`** |
| Проверка количества span'ов | `assertThat(exporter...).hasSize(N)` | Спорно — отложено |

---

## 4. Решения по кандидатам

### ДА: `@OtelSdkTest`

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@ExtendWith(OtelSdkExtension.class)
@Tag("tracing")
public @interface OtelSdkTest {}
```

`@Inherited` полезен для `extends BaseTest` (где `BaseTest` помечен `@OtelSdkTest`).
Для `@Nested`-классов наследование extension происходит через ancestor-lookup JUnit
`ExtensionContext` независимо от `@Inherited` (JSR-175 ограничение).

### ОТЛОЖЕНО: `@ExpectSpanCount` / `@ExpectNoSpans`

1. Нулевой прецедент за 5+ лет.
2. Debug-pain: при падении `expected 1 span, got 2` assertion скрыт вне тела теста.
3. Конфликт с `exporter.reset()` в многоэтапных тестах.
4. Стоимость: ~80 строк через `AfterTestExecutionCallback` + edge-cases.

**Условие пересмотра:** после миграции Серий 5-6 собрать метрики (раздел 6).

### Не вводим никогда

| Кандидат | Причина отказа |
|----------|---------------|
| `@SamplerUnderTest(MySampler.class)` | Дублирует `builder().sampler()`, добавляет рефлексию |
| `@ProcessorUnderTest` | Аналогично |
| `@ScopeMode(CLASS)` | Два места задания scope — риск рассинхронизации |
| `@CapturedSpans` / `@WithExporter` | `InMemorySpanExporter` уже инжектируется |
| `@WithSpan` на тестовых методах | Production-аннотация, не тестовая |

---

## 5. Anti-overengineering чек-лист

- [x] `@OtelSdkTest` не дублирует builder или fluent harness API.
- [x] Не прячет конфигурационное состояние теста (sampler, processor, scope).
- [x] Нет side-effect'ов вне жизненного цикла JUnit Store.
- [x] Два независимых прецедента (`@DataJpaTest`, `@AutoConfigureTracing`).
- [x] `@ExpectSpanCount` нарушает правило прецедента — отложена, не отвергнута.
- [x] Anti-pattern двойной регистрации задокументирован в Javadoc.
- [x] `@Inherited` полезен для `extends BaseTest`; поведение для `@Nested` задокументировано корректно.
- [x] No-arg ctor сохраняет `sampler=null` — нет скрытых поведенческих изменений.

---

## 6. Метрики (собрать после миграции Серий 5-6)

```bash
# Потенциальная pain-area для @ExpectSpanCount
rg "getFinishedSpanItems\(\)?\s*\.hasSize\("   --type java E:/Platform_Traces -c
rg "getFinishedSpanItems\(\)?\s*\.isEmpty\(\)" --type java E:/Platform_Traces -c

# Потенциальные клиенты @OtelSdkTest
rg "@ExtendWith\(OtelSdkExtension\.class\)" --type java E:/Platform_Traces -c
rg "OtelSdkExtension\.(methodScope|builder)"  --type java E:/Platform_Traces -c

# Baseline: где уже стоит @Tag("tracing")
rg '@Tag\("tracing"\)' --type java E:/Platform_Traces -c
```

### Фактические числа на момент введения `@OtelSdkTest`

Замерено по всему `E:/Platform_Traces` (исключая `build/`), всего 132 java-файла:

| Метрика | Сырой счётчик | Реальные совпадения вне комментариев/Javadoc | Что это значит |
|---|---|---|---|
| `getFinishedSpanItems()...hasSize(N)` | 19 | 19 | Потенциальный pain под `@ExpectSpanCount(N)` |
| `getFinishedSpanItems()...isEmpty(...)` | 9 | 9 | Потенциальный pain под `@ExpectNoSpans` |
| `@ExtendWith(OtelSdkExtension.class)` | 7 | **3** (2 в Javadoc-примере + 1 в `OtelSdkTestAnnotationTest`) | Реальных production-клиентов сейчас нет — только сам smoke-тест аннотации |
| `OtelSdkExtension.builder()` / `classScope()` / `sharedAcrossNested()` | 8 | 6 | Все программные регистрации (все с builder-конфигурацией или нестандартным scope) |
| `@Tag("tracing")` | 4 | 4 | Baseline до введения `@OtelSdkTest` |
| Клиенты `OtelSdkExtension` через `@RegisterExtension` с builder-конфигурацией | — | **1** ([EnrichingSpanProcessorTest](../platform-tracing-otel-extension/src/test/java/space/br1440/platform/tracing/otel/extension/processor/EnrichingSpanProcessorTest.java)) | НЕ подходит под `@OtelSdkTest` (кастомный `SpanProcessor`) |

#### Выводы

- **`@OtelSdkTest` имеет ценность как превентивная мера, а не как retrofit-инструмент.** На момент введения в платформе **нет** ни одного теста, который бы использовал `@ExtendWith(OtelSdkExtension.class)` без конфигурации и без `@Tag("tracing")` — единственный кандидат с прямой регистрацией ([EnrichingSpanProcessorTest](../platform-tracing-otel-extension/src/test/java/space/br1440/platform/tracing/otel/extension/processor/EnrichingSpanProcessorTest.java)) использует кастомный `SpanProcessor` и поэтому остаётся на `@RegisterExtension static OtelSdkExtension`. Ценность аннотации проявится при появлении новых тестов сервисов, которые иначе пришлось бы заводить как `@ExtendWith(OtelSdkExtension.class) + @Tag("tracing")` — теперь это один декоратор, и `@Tag` нельзя забыть навесить.
- **`@ExpectSpanCount` — pain заметный, но не доминирующий:** 19 + 9 = 28 повторений на 132 java-файла. Решение «отложить и обсудить после Серий 5–6 с реальными данными» остаётся корректным — числа сами по себе не обосновывают введение аннотации против нулевого индустриального прецедента.
- **Миграция существующих тестов на `@OtelSdkTest` не выполнялась**, потому что не нашлось ни одного теста, подходящего под её условие применимости. Это здоровый сигнал, а не недостаток: аннотация не должна навязываться там, где она не упрощает код.

---

## 7. План итоговый

| Шаг | Содержание | Когда |
|-----|-----------|-------|
| 1 | Защита no-arg ctor: `sampler=null`, inline-комментарий + Javadoc | До PR — применить патч |
| 2 | `OtelSdkTest.java` (composite, `@Documented`, `@Inherited`, корректный Javadoc) | Мини-PR |
| 3 | `OtelSdkTestAnnotationTest.java` — 7 smoke-тестов, изоляция в `@Nested` | Тот же PR |
| 4 | Этот research-документ | Тот же PR |
| 5 | Собрать метрики (5 команд выше) | После Серий 5-6 |
| 6 | По данным метрик принять решение по `@ExpectSpanCount` | Отдельный PR |
