# Тестовая инфраструктура `platform-tracing-test`

Документ для разработчиков сервисов-потребителей платформы трассировки и для тех, кто
сопровождает `Platform_Traces`.

## Состав

- **`OtelSdkExtension`** — JUnit 5 extension над минимальным `OpenTelemetrySdk` с
  `InMemorySpanExporter`. Три режима жизненного цикла: `METHOD`, `CLASS`, `SHARED_NESTED`.
  Параметр-резолвер для `OpenTelemetrySdk`, `InMemorySpanExporter`, `Sampler`.
- **`TraceOperationsTestExtension`** — тонкий фасад поверх `OtelSdkExtension` для прикладных
  тестов; добавляет резолвер `TraceOperations` и `OpenTelemetry`.
- **`SamplerHarness`** — fluent harness для прямого тестирования `Sampler` без поднятия SDK.
- **`SpanProcessorHarness`** — `AutoCloseable` harness для прямого тестирования `SpanProcessor`.
- **`SpanAssertions`**, **`SamplerDecisionAssert`** — статические/AssertJ-style помощники.
- **`OtelSdkArchRules`** — ArchUnit-правила для тестов сервисов.

## Примеры

### 1. Тест прикладного сервиса через `TraceOperationsTestExtension`

```java
@ExtendWith(TraceOperationsTestExtension.class)
class OrderServiceTest {
    @Test
    void createOrder_создаётSpan(TraceOperations tracing, InMemorySpanExporter exporter) {
        OrderService service = new OrderService(tracing);

        service.create(new OrderRequest("u-1", 100));

        SpanAssertions.assertOnlyFinishedSpan(exporter)
                .hasName("order.create")
                .hasAttribute(stringKey("platform.type"), "internal")
                .hasStatus(StatusData.ok());
    }
}
```

### 2. Тест `SpanProcessor` через `SpanProcessorHarness`

```java
class EnrichingSpanProcessorAdvancedTest {
    @Test
    void кастомный_приоритет() {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(
                new EnrichingSpanProcessor(List.of("server.address", "peer.service")))) {

            Span span = h.tracer("t").spanBuilder("client").setSpanKind(CLIENT).startSpan();
            span.setAttribute("peer.service", "billing");
            span.setAttribute("server.address", "billing.example.com");
            span.setStatus(StatusCode.ERROR, "boom");
            span.end();

            assertThat(h.exporter().getFinishedSpanItems().get(0)
                    .getAttributes().get(stringKey("platform.remote.service")))
                    .isEqualTo("billing.example.com");
        }
    }
}
```

Для случаев с **общей конфигурацией процессора** для всех тестов класса удобнее
`OtelSdkExtension`:

```java
class EnrichingSpanProcessorTest {
    @RegisterExtension
    static OtelSdkExtension otel = OtelSdkExtension.builder()
            .scope(ScopeMode.METHOD)
            .addSpanProcessor(new EnrichingSpanProcessor())
            .build();

    @Test
    void тест(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) { ... }
}
```

### 3. Тест `Sampler` через `SamplerHarness`

```java
class CompositeSamplerTest {
    @Test
    void X_Trace_On_включает_запись() {
        CompositeSampler sampler = new CompositeSampler(Sampler.alwaysOff(),
                "X-Trace-On", List.of("on"), "X-QA-Trace");

        SamplerDecisionAssert.assertThat(SamplerHarness.of(sampler)
                        .spanKind(SpanKind.SERVER)
                        .putStringArrayAttribute("http.request.header.x-trace-on", "on")
                        .sample())
                .isRecordAndSample();
    }
}
```

## Режимы жизненного цикла `OtelSdkExtension`

| Режим                | Фабрика                                          | SDK         | exporter.reset() |
|----------------------|--------------------------------------------------|-------------|------------------|
| `METHOD` (дефолт)    | `OtelSdkExtension.create()`                      | на метод    | не нужен         |
| `CLASS`              | `OtelSdkExtension.classScope()`                  | на класс    | перед каждым тестом |
| `SHARED_NESTED`      | `OtelSdkExtension.sharedAcrossNested()`          | на класс    | нет (накапливаем) |

`SHARED_NESTED` использовать только в паре с `@TestMethodOrder` для саг/многошаговых сценариев.

## ArchUnit-правила в тестах сервиса

В тестовом модуле сервиса:

```java
@AnalyzeClasses(packages = "com.acme.service",
        importOptions = ImportOption.DoNotIncludeTests.class)
class TracingArchRulesTest {
    @ArchTest
    static final ArchRule no_buildAndRegisterGlobal =
            OtelSdkArchRules.NO_BUILD_AND_REGISTER_GLOBAL;

    @ArchTest
    static final ArchRule no_globalOpenTelemetry =
            OtelSdkArchRules.NO_GLOBAL_OPEN_TELEMETRY;
}
```

Подключение в `build.gradle` сервиса:

```groovy
dependencies {
    testImplementation 'space.br1440.platform.tracing:platform-tracing-test'
}
```

ArchUnit публикуется как `api`-зависимость `platform-tracing-test`; явно указывать его
в сервисе не нужно.

## Антипаттерны

- **Несколько `OtelSdkExtension` в одном тест-классе.** Namespace общий, второй extension
  перетрёт ресурс первого. Разнесите по классам.
- **`OpenTelemetrySdk.builder().buildAndRegisterGlobal()` в тестах сервиса.** Регистрация в
  global допустима только из `platform-tracing-spring-boot-autoconfigure`. ArchUnit-правило
  `NO_BUILD_AND_REGISTER_GLOBAL` ловит такие случаи.
- **`GlobalOpenTelemetry.get()` в прикладном коде.** Инжектируйте `OpenTelemetry` через
  Spring DI; ArchUnit-правило `NO_GLOBAL_OPEN_TELEMETRY` ловит такие случаи.

## Когда использовать что

| Сценарий                                          | Инструмент                              |
|---------------------------------------------------|-----------------------------------------|
| Юнит-тест Sampler'а                               | `SamplerHarness` + `SamplerDecisionAssert` |
| Юнит-тест SpanProcessor'а с разной конфигурацией  | `SpanProcessorHarness` (try-with-resources) |
| Юнит-тест SpanProcessor'а с общей конфигурацией   | `OtelSdkExtension.builder().addSpanProcessor()` |
| Интеграционный тест прикладного сервиса           | `TraceOperationsTestExtension` (через `@ExtendWith`) |
| Тест с `@Nested`-блоками и общим SDK              | `OtelSdkExtension.classScope()`         |
| Сага/многошаговый сценарий                        | `OtelSdkExtension.sharedAcrossNested()` + `@TestMethodOrder` |
