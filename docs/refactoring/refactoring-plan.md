# План рефакторинга `E:\Platform_Traces`

> Статус: предложение к ревью архитекторов.
> Принцип: **минимум изменений, максимум устранённых неоднозначностей**. Без переписываний, без новых абстракций «на будущее», без добавления слоёв там, где есть один пользователь.

---

## 0. Карта решения «как есть»

8 модулей, ~60 production-классов, ~25 тестов:

| Модуль | Назначение по факту | Оценка |
| --- | --- | --- |
| `platform-tracing-bom` | BOM (Spring Boot, OTel, Spring Cloud, web-error-model) | OK |
| `platform-tracing-api` | Интерфейсы, аннотации, константы, SPI | OK |
| `platform-tracing-core` | `DefaultTraceOperations` + `NoopTraceOperations` + 1 исключение | OK |
| `platform-tracing-spring-boot-autoconfigure` | 11 авто-конфигураций, 8 фильтров/AOP/observation, 2 параллельных провайдера контекста ошибок | **рефакторинг** |
| `platform-tracing-spring-boot-starter` | агрегатор | OK |
| `platform-tracing-otel-extension` | агентное расширение: 8 `SpanProcessor`'ов + 1 `Sampler` + `ResourceProvider` | **частичная зачистка** |
| `platform-tracing-collector-config` | 2 YAML-конфига Collector'а | OK |
| `platform-tracing-test` | 1 JUnit 5 extension | OK (доделать после рефакторинга) |
| `platform-tracing-semconv-lint` | линтер JSON span'ов (CLI) | **исключить из сборки** |

---

## 1. Главные проблемы (по приоритету)

### 1.1 Дубль API «контекста запроса для errorhandling» (BLOCKER)

В `autoconfigure` параллельно живут **два класса с одной задачей** — поставить `traceId/spanId/correlationId` во внешний errorhandling-стартер:

- `errorhandling/PlatformTraceContextProvider` (свой DTO `PlatformTraceContext`) — регистрируется в `ErrorhandlingTracingAutoConfiguration`, требует `TraceOperations`.
- `errorhandling/TracingRequestContextSupplier implements Supplier<RequestContext>` — регистрируется в `RequestContextSupplierAutoConfiguration`, не требует `TraceOperations`.

Оба читают одни и те же источники (`Span.current()`, `MDC`), отличаются только типом DTO и наличием `TraceOperations` в зависимостях. У `PlatformTraceContextProvider` нет ни одного потребителя в репозитории — это «теоретический API», добавленный «на всякий случай».

**Решение.** Оставить **только** `TracingRequestContextSupplier` + `RequestContextSupplierAutoConfiguration` (хорошо документированный, всегда-on, прошёл несколько раундов архитектурного ревью). Удалить `PlatformTraceContext`, `PlatformTraceContextProvider`, `PlatformTraceContextProviderTest`, `ErrorhandlingTracingAutoConfiguration`. Снять её из `AutoConfiguration.imports`.

**Эффект:** −4 класса, −1 тест, −1 авто-конфигурация. Контракт с errorhandling-стартером становится единственным.

---

### 1.2 MDC синхронизируется дважды (HIGH)

Две независимые реализации одной задачи активны одновременно:

1. `mdc/TracingMdcContextWrapper` (через `ContextStorage.addWrapper`) — работает на любом attach контекста, покрывает HTTP/реактив/Kafka/scheduler без участия фильтров.
2. `filter/TracingMdcServletFilter` + `filter/TracingMdcWebFilter` — явно вызывают `TracingMdc.populate()/clear()`.

Обе включены по умолчанию (флаг `mdc.context-wrapper-enabled=true`). На каждый HTTP-запрос MDC заполняется **дважды**, и порядок снятия в `finally`-блоках разный для wrapper'а и фильтров. Это рабочее, но избыточное и сбивающее с толку при дебаге.

**Решение.** Wrapper — основной механизм (он покрывает все треды, не только HTTP). Фильтры `TracingMdcServletFilter`/`TracingMdcWebFilter` и утилитный фасад `mdc/TracingMdc` — удалить вместе с регистрациями в `ServletTracingAutoConfiguration` и `ReactiveTracingAutoConfiguration`. Свойство `platform.tracing.mdc.context-wrapper-enabled` остаётся как «выключить wrapper целиком», но без второго механизма-fallback'а — лучше один работающий путь, чем два.

**Эффект:** −3 класса, −2 регистрации фильтра. Поведение не меняется (wrapper покрывает все сценарии фильтров).

---

### 1.3 Actuator `WriteOperation` обманывает оператора (HIGH)

`TracingActuatorEndpoint.updateTracing("samplingRatio", "0.5")` выставляет значение в локальный bean `TracingProperties` autoconfigure-модуля. Реальный sampler живёт в **отдельном classloader'е** (Java Agent extension, `CompositeSampler`), который читает свойства один раз через `ConfigProperties` на старте JVM. UI меняет, но трассировка продолжает сэмплироваться с прежним ratio.

**Решение.** Простое и без overengineering:

- Свойство `enabled` в `WriteOperation` — оставить, оно реально влияет на autoconfigure-уровень (фильтры, observation).
- Свойство `samplingRatio` — **убрать из `WriteOperation`** и оставить только в `ReadOperation` (показывать «настроенное на старте»). Любая попытка `POST /actuator/tracing/samplingRatio/...` → `InvalidRequestException("samplingRatio is read-only at runtime; restart with new -Dplatform.tracing.sampling.ratio=... or change in OTel Collector tail-sampling")`.

Альтернатива «передавать ratio через JMX/SPI в agent extension» — overengineering без явного use case. SRE меняют sampling в Collector'е tail-sampling'ом — это и есть штатный механизм.

**Эффект:** −1 ложное обещание API, +честный контракт.

---

### 1.4 Метрика `EXCEPTIONS_RECORDED` бесполезна (MEDIUM)

`MeteredPlatformTracing.recordException()` всегда инкрементирует с тэгом `result=failure`. Тэг с одним значением — бесполезен.

**Решение.** Убрать тэг (`incrementExceptionsRecorded()` без аргумента). Метод `incrementExceptionsRecorded(SpanResult)` удалить из `PlatformTracingMetrics`. Это правка одного метода + одного callsite.

---

### 1.5 Мёртвый код в `otel-extension` (MEDIUM)

`DropOldestBatchSpanProcessor` и `PrioritizingSpanProcessor` написаны и протестированы, но **нигде не зарегистрированы** в `PlatformAutoConfigurationCustomizer`. Активация требует подмены штатного экспорт-pipeline'а Java Agent'а — непростой шаг, и в текущем агенте их нет.

Архитектурный смысл оставлять заготовку «на потом» сомнительный: это код в production-артефакте, который никогда не выполняется, но сопровождается тестами и Javadoc'ом.

**Решение.** Удалить оба класса вместе с тестами. Когда (и если) понадобится — будут восстановлены из git history с ясным use case. Сейчас они только увеличивают площадь модуля и время сборки.

`SpanWatchdogProcessor` — оставить: он **зарегистрирован** в `PlatformAutoConfigurationCustomizer`, реально работает.

**Эффект:** −2 production-класса, −2 теста, −1 enum (`Tier`).

---

### 1.6 `EnrichingSpanProcessor` дублирует ресурсные атрибуты (LOW)

`EnrichingSpanProcessor.onStart` ставит `platform.host` на каждый span. `PlatformResourceProvider` уже кладёт `platform.host` в `Resource` — а ресурс OTel автоматически приклеивается к каждому экспортируемому span'у. Дублирование на уровне span-атрибутов даёт +N байт на span × миллионы span'ов в день и не несёт информации.

**Решение.** Удалить логику `platform.host` из `EnrichingSpanProcessor` (оставить только `platform.type` и `platform.result`). Параметр `hostName` конструктора и свойство `platform.tracing.service.host` для процессора — удалить, регистрация упрощается до `new EnrichingSpanProcessor()`. Резолв hostname — только в `PlatformResourceProvider`.

**Эффект:** один источник истины для `platform.host`, минус boilerplate в `PlatformAutoConfigurationCustomizer`.

---

### 1.7 `platform-tracing-semconv-lint` отложен, но включён в сборку (LOW)

Архитекторы решили отложить разработку (см. Epic-описание). Модуль сейчас:
- собирается каждый раз вместе с остальными,
- имеет 6 классов и 2 теста,
- пользователей не имеет.

**Решение.** Закомментировать `include 'platform-tracing-semconv-lint'` в `settings.gradle` с пояснением. Каталог оставить в репо (Git history), но из активной сборки исключить. Возврат — раскомментирование одной строки.

---

### 1.8 Расхождение `description` и реального содержимого `platform-tracing-core` (TRIVIAL)

`platform-tracing-core/build.gradle`:

> «Содержит DefaultTraceOperations, NoOp-реализацию … **типизированные ObservationConvention и доменные исключения**.»

ObservationConvention'ы лежат в `autoconfigure/observation`. «Доменные исключения» в description не соответствовали коду: `PlatformTracingException` был dead code и удалён; фактически используется `ExceptionRecorder`.

**Решение.** Переписать description: «Реализация публичного API платформенной трассировки поверх OpenTelemetry API. Содержит `DefaultTraceOperations`, `NoopTraceOperations` и `ExceptionRecorder` для безопасной записи ошибок в span.»

---

### 1.9 Реактивные фильтры не имеют `@Order` (LOW)

Servlet-цепочка задаёт порядок (`HIGHEST_PRECEDENCE + 40` для MDC, `+50` для response-header). Реактивные `WebFilter`'ы регистрируются как обычные `@Bean` без `Ordered`/`@Order`. Spring WebFlux выстраивает их в порядке имени бина — ненадёжно для будущих сервисов с несколькими фильтрами.

**Решение.** В `ReactiveTracingAutoConfiguration` для оставшегося `TraceResponseHeaderWebFilter` (после удаления MDC-фильтра по §1.2) добавить `@Order(Ordered.HIGHEST_PRECEDENCE + 50)`. Один аннотейшн.

---

### 1.10 `MeteredPlatformTracing` декорирует только два метода (LOW)

В фасаде `TraceOperations` шесть методов; декоратор покрывает `startSpan` и `recordException`, но `currentTraceId/currentSpanId/start*` (типизированные) идут через `default` в интерфейсе и **не считаются**. Цифры метрики занижены.

Типизированные методы (`startHttpServer`, `startInternal` и т.д.) делегируют в `startSpan(name, category)` через `default`, поэтому метрика «через декоратор» считает их корректно. Но если потребитель вызовет `delegate.startInternal(...)` — попадёт мимо декоратора. На практике все потребители получают `MeteredPlatformTracing` как `@Primary` бин, всё ок.

**Решение.** Не трогать. Поведение корректное при штатной DI. Зафиксировать в Javadoc `MeteredPlatformTracing`: «декорирует контракт `TraceOperations`; типизированные методы покрываются через `default startSpan`».

---

## 2. Кросс-модульные мелочи

| # | Где | Что | Действие |
| --- | --- | --- | --- |
| 2.1 | `PlatformReactiveServerRequestObservationConvention.resolveResult` | три ветки `is4xx`/`is5xx`/error дублируют логику | Свести к одной: `if (statusCode != null && statusCode.value() >= 400) return FAILURE` (как в Servlet-варианте) |
| 2.2 | `TracingProperties.Sampling.forceRecordHeaderValues` | `new ArrayList<>(Arrays.asList(...))` | Заменить на `new ArrayList<>(List.of(...))` (текстовая чистка) |
| 2.3 | `TracingHealthIndicator` | `try { ... } catch (Exception e) {}` ловит `Exception`, а должен ловить `RuntimeException` (как в `TracingCoreAutoConfiguration`) | Сузить до `RuntimeException` |
| 2.4 | `DefaultTraceOperations` имеет два конструктора (`OpenTelemetry`, `Tracer`) | Конструктор `Tracer` нигде не используется в репо | Удалить, оставить один |
| 2.5 | `PlatformAttributes` | константы OTel semconv продублированы из SDK | Оставить как есть (это сознательное решение, чтобы api-модуль не зависел от SDK) — но добавить комментарий об источнике версии semconv в Javadoc |
| 2.6 | `TracingProperties.Scrubbing.builtInRules` (autoconfigure) и `PROP_SCRUBBING_RULES` (otel-extension) | два независимых списка, изменения в одном не синхронизируются с другим | Документировать в Javadoc обоих: autoconfigure-список — только для самонаблюдательных целей (показать на actuator-эндпоинте), реально работают правила в otel-extension. Альтернатива — объединить — overengineering, classloader разный. |

---

## 3. План работ — порядок и оценка

Приоритет — устранить дубли и мёртвый код. Никаких новых абстракций.

| Шаг | Задача | Файлы | Риск | Оценка |
| --- | --- | --- | --- | --- |
| 1 | §1.1 Удалить `PlatformTraceContextProvider`, `PlatformTraceContext`, `ErrorhandlingTracingAutoConfiguration` + их тесты, обновить `AutoConfiguration.imports` | autoconfigure (4 main + 1 test), imports | low (нет внешних потребителей) | 0.5 d |
| 2 | §1.2 Удалить MDC-фильтры и `TracingMdc`, оставить wrapper | autoconfigure (3 main + 2 регистрации) | low (поведение покрыто wrapper'ом, тесты wrapper'а уже зелёные) | 0.5 d |
| 3 | §1.3 Сделать `samplingRatio` read-only в actuator | `TracingActuatorEndpoint.java` + тест | low | 0.25 d |
| 4 | §1.4 Метрика без бесполезного тэга | `PlatformTracingMetrics`, `MeteredPlatformTracing` + тесты | low | 0.25 d |
| 5 | §1.5 Удалить `DropOldestBatchSpanProcessor`, `PrioritizingSpanProcessor` + тесты | otel-extension (2 main + 2 test) | low | 0.25 d |
| 6 | §1.6 Убрать `platform.host` из `EnrichingSpanProcessor` | otel-extension (1 main + 1 customizer + 1 test) | low | 0.25 d |
| 7 | §1.7 Закомментировать `semconv-lint` в `settings.gradle` | settings | none | 5 min |
| 8 | §1.8 / 1.9 / 2.* — косметика | разное | none | 0.5 d |
| | **Итого** | ~14 удалённых классов, ~6 правок | | **≈ 2.5 человеко-дня** |

---

## 4. Что **не** делаем

Чтобы зафиксировать границы рефакторинга:

- **Не** переписываем фасад `TraceOperations`. Контракт стабилен, потребители не появились, ломать нечего и незачем.
- **Не** вводим новые абстракции (`SamplingService`, `MdcSynchronizer`, `ContextProvider`). Один класс — один владелец.
- **Не** добавляем JMX/JFR/`@RefreshScope`-распространение sampling в Java Agent. Эта задача требует дизайна на отдельный спринт; решается при появлении явного запроса от SRE.
- **Не** трогаем OTel Collector конфигурации. Они валидируются отдельным процессом SRE.
- **Не** меняем имена метрик (`platform_tracing_spans_started_total`, `platform_tracing_exceptions_recorded_total`). Это контракт для дашбордов SRE.
- **Не** удаляем `PlatformHeaders.X_TRACE_ID` (дублирует `X_REQUEST_ID`): требование интеграций, явно описано в Javadoc.

---

## 5. Контрольный чек-лист после рефакторинга

- [ ] `gradlew clean build` зелёный, тестов меньше на ~5.
- [ ] `AutoConfiguration.imports` содержит **9** строк (было 11).
- [ ] `grep -R "PlatformTraceContext" platform-tracing-spring-boot-autoconfigure/src` пусто.
- [ ] `grep -R "TracingMdcServletFilter\|TracingMdcWebFilter\|TracingMdc\b" autoconfigure/src` — только в Javadoc (если остался reference) или пусто.
- [ ] `POST /actuator/tracing/samplingRatio/0.5` возвращает 400 + `reason=INVALID_REQUEST`.
- [ ] Метрика `platform_tracing_exceptions_recorded_total` в Prometheus без тэга `result`.
- [ ] `PlatformAutoConfigurationCustomizer.registerSpanProcessors` без `EnrichingSpanProcessor(hostName)`-параметра.
- [ ] `settings.gradle` содержит закомментированный `// include 'platform-tracing-semconv-lint'` с reason-комментарием.

---

## 6. Сводная таблица «до / после»

| Параметр | До | После | Δ |
| --- | --- | --- | --- |
| Production-классы (без BOM/starter) | ≈ 60 | ≈ 46 | −14 |
| Авто-конфигурации | 11 | 9 | −2 |
| Активных модулей в сборке | 8 | 7 | −1 |
| Дублирующих API контекста ошибок | 2 | 1 | −1 |
| Механизмов синхронизации MDC | 2 | 1 | −1 |
| Мёртвых `SpanProcessor`'ов | 2 | 0 | −2 |
| Ложных Actuator-операций | 1 | 0 | −1 |

Изменений в публичном API (`platform-tracing-api`, `platform-tracing-core` интерфейсы): **0**. Это рефакторинг без breaking changes для прикладных сервисов.

