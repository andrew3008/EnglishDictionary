# Sub-task `[Tracing_Java] Дизайн решения`

Файл содержит два готовых к копированию блока **Atlassian Wiki Markup** (старый синтаксис Jira):

1. **Description** — постановка задачи на дизайн-сессию (поле *Description* sub-task'и).
2. **Message** — отчёт о выполненной проработке дизайна с зафиксированными архитектурными решениями (комментарий к sub-task'е по итогам ревью с архитекторами).

---

## 1. Description

```
h1. Контекст

В платформе отсутствует единый стандарт инструментации распределённой трассировки. Сервисы используют независимые комбинации Spring Cloud Sleuth, Brave и ручной инструментации OpenTelemetry SDK. Следствия:

* разные имена атрибутов и ресурсов в одном и том же типе span'а — корреляция между сервисами невозможна без post-processing на стороне SRE;
* неконтролируемый рост объёма экспорта (отсутствует единый sampling-контракт), нагрузка на Jaeger и канал до Collector нелинейна;
* утечки PII в трассах: маскирование делается ad hoc, без аудита;
* нет дисциплины «tracing никогда не должен валить запрос» — встречаются case'ы, когда сбой OTel Collector приводит к 5xx;
* активные span'ы переживают thread'ы и текут наружу: GC-давление, пропавшие trace'ы, ложные «висящие» сценарии в Jaeger.

Epic {{Tracing_Java}} ставит платформенный стартер {{spring-boot-platform-tracing}} v0.1.0 как единственно допустимую точку входа в распределённую трассировку для Spring Boot 3.x / Java 21 сервисов. Перед реализацией требуется полная архитектурная проработка: контракты совладения с другими стартерами (logging, metrics), classloader-изоляция OTel Java Agent extension'а, контракт §37 «non-blocking», публичные SPI. Дизайн-сессия фиксирует эти решения в письменной форме и снимает их с обсуждения в реализационной фазе.

h1. Цель

Получить письменный sign-off Architecture Board, SRE и Security на полный архитектурный контракт {{spring-boot-platform-tracing}} v0.1.0 до старта реализации. По завершении sub-task'и должны быть устранены любые архитектурные неопределённости, которые могут привести к переделкам публичного API или ломающим изменениям в реализационных sub-task'ах 1–7.

Достижение цели измеряется четырьмя deliverable'ами (4 PlantUML-диаграммы + ADR-set), 7 acceptance criteria и подписями стейкхолдеров в блоке Sign-off комментария.

h1. Outcome

Утверждённая Architecture Board, SRE и Security архитектура {{spring-boot-platform-tracing}} v0.1.0, выраженная в четырёх PlantUML-диаграммах и письменном перечне архитектурных решений (ADR-set). Sign-off этой sub-task'и является блокером для всех реализационных sub-task'ов 1–7.

h1. Scope

Проработать архитектуру решения на трёх уровнях детализации (C4: Context → Component → Runtime Interaction) плюс одна агрегирующая высокоуровневая диаграмма для нетехнических стейкхолдеров. Зафиксировать:

* зоны ответственности модулей и classloader-изоляцию,
* контракт совладения с {{spring-boot-starter-platform-logging}} и {{spring-boot-starter-platform-metrics}},
* контракт интеграции с {{web-error-model}} ({{Supplier<RequestContext>}}, {{InvalidRequestException}}, {{RuntimeConfigurationException}}),
* sampling-контракт ({{X-Trace-On}}, {{X-QA-Trace}}, parent_based ratio) и его соответствие OpenTelemetry Semantic Conventions 1.27+,
* TTL-стратегию ретеншна на уровне OTel Collector (errors дольше, success короче),
* перечень публичных SPI ({{SensitiveDataRule}}, {{SpanEnricher}}, {{SamplingDecisionContributor}}) и procedure расширения.

h1. Deliverables

* {{docs/architecture/00-overview.puml}} — Solution Overview для митингов с менеджментом и SRE (single page, 16:9).
* {{docs/architecture/01-context.puml}} — System Context (C4 L1): внешние акторы, системы, граничные условия.
* {{docs/architecture/02-component.puml}} — Component (C4 L3): декомпозиция на 7 модулей с явным разделением application classloader / bootstrap classloader OTel Java Agent.
* {{docs/architecture/03-interaction-with-logging-metrics.puml}} — Runtime sequence-диаграмма точек совместного владения с logging- и metrics-стартерами.
* SVG-render каждой диаграммы для embedding в DocPortal.
* ADR-set: письменно зафиксированный перечень архитектурных решений с обоснованием, альтернативами и follow-up'ами (комментарий к этой sub-task'е).

h1. Architectural decisions to ratify

# *Скоуп v0.1.0*: signal traces only; logs и metrics — раздельные платформенные стартеры.
# *Layered design*: API → Core → Spring Boot autoconfigure → Spring Boot starter; OTel-extension изолирован в bootstrap classloader.
# *Контракт §37 «non-blocking»*: исключения инфраструктуры трассировки никогда не достигают application thread; реализация через {{SafeSpanProcessor}} + try-catch обёртки в supplier'ах.
# *Контракт always-on*: bean {{Supplier<RequestContext>}} доступен внешнему {{@ControllerAdvice}} независимо от {{platform.tracing.enabled}}; реализация — отдельный {{@AutoConfiguration}} без property/bean-gating.
# *Использование web-error-model напрямую*: {{InvalidRequestException}} и {{RuntimeConfigurationException}} объявлены {{final}} в платформе — собственная иерархия tracing-исключений не создаётся.
# *Иммутабельность платформенных атрибутов*: имена {{platform.type}} / {{platform.c_group}} / {{platform.id}} / {{platform.host}} / {{platform.result}} — frozen на v0.1.0; изменения проходят через breaking-change процедуру.
# *Соответствие OpenTelemetry Semantic Conventions 1.27+*: HTTP server / client, database, messaging — стандартные имена; платформенные расширения только сверху.
# *Sampling-контракт*: priority X-Trace-On > X-QA-Trace > parent_based(traceIdRatioBased); конкретные значения {{ratio}} согласуются с SRE для production / load-test / dev профилей.
# *TTL-tiers*: routing connector в Collector направляет failure-trace'ы в long-retention Jaeger, success — в short-retention; политика TTL принадлежит SRE и не закодирована в стартере.
# *Self-observability*: tracing-стартер потребляет, но не поставляет {{MeterRegistry}}; bean приходит из {{spring-boot-starter-platform-metrics}}.
# *MDC-контракт*: tracing пишет {{trace_id}} / {{span_id}} / {{trace_flags}} в MDC; logging-стартер только читает их через Logback pattern. Запрещены циклические зависимости.

h1. Acceptance criteria

# {{plantuml -checkonly docs/architecture/*.puml}} отрабатывает без warnings.
# Каждое архитектурное решение из списка выше сопровождается {{note}}-блоком на соответствующей диаграмме либо отдельной записью в ADR-set комментарии.
# Контекстная диаграмма перечисляет всех акторов из Epic-описания и явно делегирует SRE-зону (OTel Collector, Jaeger short/long retention).
# Компонентная диаграмма явно разделяет classloader'ы и помечает зависимость на {{web-error-model}} исключительно у {{platform-tracing-spring-boot-autoconfigure}}.
# Runtime-диаграмма документирует три точки совладения (MDC, MeterRegistry, RequestContext) с однонаправленным data-flow без циклов.
# Solution Overview-диаграмма помещается в слайд 16:9 без скролла; контрастная типографика читается с проектора в зале на 30+ человек.
# Sign-off Architecture Board, SRE и Security зафиксирован комментарием в этой sub-task'е со ссылкой на запись ревью.

h1. Constraints

* Минимальная поддерживаемая версия Spring Boot — 3.5.x; Java toolchain — 21.
* Минимальная версия OpenTelemetry SDK — 1.61.x; Instrumentation — 2.27.x.
* Минимальная версия OTel Collector — 0.110 (стабильный {{routing}} connector, {{tail_sampling}} v2).
* {{web-error-model}} ≥ 2.0.0 как обязательная зависимость через BOM.
* Запрещены compile-time зависимости {{platform-tracing-otel-extension}} на Spring и {{web-error-model}} (изоляция bootstrap classloader).

h1. Out of scope

* Реализационные таски (sub-task 1–7) до получения sign-off.
* Поддержка не-Spring модулей, multi-tenancy, реактивная пропагация {{RequestContext}} в Reactor {{Context}} — отдельные инициативы.
* Модуль {{platform-tracing-semconv-lint}} — отложен Architecture Board.

h1. Dependencies

Epic-документы: {{Traces Requests.txt}}, {{Platform Distributed Tracing Standard}} (DocPortal). Зависимости от sibling-стартеров — только на уровне контрактов (MDC keys, MeterRegistry bean, RequestContext supplier), без compile-time связей.

h1. Estimation

3 рабочих дня:
* день 1 — драфт диаграмм (Overview + 3 детальных);
* день 2 — ревью с Architecture Board и SRE, итерация по комментариям;
* день 3 — Security review для PII-scrubbing блока, финальный sign-off, публикация ADR-set.
```

---

## 2. Message (комментарий к sub-task'е)

```
h1. Дизайн решения {{spring-boot-platform-tracing}} v0.1.0

Architecture Board, SRE и Security завершили ревью {{DD.MM.YYYY}}. Все архитектурные решения зафиксированы ниже и в исходниках диаграмм. Sign-off получен — реализационные sub-task'и 1–7 разблокированы.

h2. Контекст

Дизайн-сессия инициирована в рамках Epic {{Tracing_Java}} как обязательный gate перед реализацией. Текущее состояние трассировки в платформе — фрагментировано (Sleuth + Brave + ad hoc OpenTelemetry SDK); это даёт расхождение по именам атрибутов, отсутствие единого sampling-контракта и периодические случаи, когда сбой инфраструктуры трассировки приводит к 5xx у пользователя.

Цель ревью — устранить архитектурные неопределённости *до* начала кодирования, чтобы исключить переделки публичного API и ломающие изменения в реализационных sub-task'ах 1–7. По итогам зафиксирован письменный ADR-set из 11 решений (см. ниже).

В ревью участвовали:

* {*}Architecture Board{*} — owner стандарта именования атрибутов, публичных SPI, classloader-границ.
* {*}SRE / Observability{*} — owner OTel Collector конфигурации, retention-политики Jaeger, sampling-настроек под профили нагрузки.
* {*}Security{*} — review встроенных правил {{SensitiveDataRule}} и процедуры расширения PII-scrubbing.
* {*}Platform Team (исполнитель){*} — представление драфта дизайна и ответы на review-комментарии.

Драфт прошёл две итерации перед финальным утверждением:

# *Итерация 1*: декомпозиция на 7 модулей, classloader-изоляция, базовый sampling-контракт. Замечания — отсутствие явного контракта совладения с logging/metrics стартерами, неоднозначность гейтирования {{Supplier<RequestContext>}}.
# *Итерация 2*: добавлены runtime sequence-диаграмма для трёх точек совладения (MDC, MeterRegistry, RequestContext), AD-4 «always-on Supplier» с обоснованием, AD-11 «MDC-контракт» с ограничением «tracing пишет, logging читает». Финальный sign-off получен на этой итерации.

h2. Артефакты

|| Артефакт || Путь / Ссылка || Назначение ||
| Solution Overview (16:9 для митингов) | {{docs/architecture/00-overview.puml}} → SVG в DocPortal | Презентация менеджменту, единый план решения |
| System Context (C4 L1) | {{docs/architecture/01-context.puml}} → SVG | Стейкхолдеры, внешние системы, граничные условия |
| Component (C4 L3) | {{docs/architecture/02-component.puml}} → SVG | Декомпозиция модулей, classloader-изоляция, граф зависимостей |
| Runtime Interaction | {{docs/architecture/03-interaction-with-logging-metrics.puml}} → SVG | Sequence-диаграмма совместного владения с logging/metrics стартерами |
| ADR-set | этот комментарий, секции «Архитектурные решения» и «Open follow-ups» | Письменная фиксация решений |

h2. Резюме архитектуры

Стартер построен по принципу {*}layered design{*} с четырьмя уровнями зависимостей и явной classloader-изоляцией для OTel Java Agent extension'а. Внешние интеграции реализованы как односторонние data-flow без циклов:

* {*}MDC{*}: tracing пишет → logging читает.
* {*}MeterRegistry{*}: metrics поставляет → tracing потребляет.
* {*}RequestContext{*}: tracing поставляет → внешний errorhandling-{{@ControllerAdvice}} потребляет.

Контракт §37 «non-blocking» обеспечен на двух уровнях: декоратором {{SafeSpanProcessor}} в bootstrap classloader и try-catch обёртками в {{TracingRequestContextSupplier}} в application classloader.

h2. Архитектурные решения (ratified)

h3. AD-1. Layered design с четырьмя уровнями

{code}
platform-tracing-api      ← публичный контракт (frozen v0.1.0)
platform-tracing-core     ← реализации без Spring
platform-tracing-spring-boot-autoconfigure
                          ← инструментация Spring Boot 3
platform-tracing-spring-boot-starter
                          ← агрегирующий стартер
{code}

Уровни запрещают зависимости снизу вверх. Тестовый и операционный модули ({{platform-tracing-test}}, {{platform-tracing-collector-config}}, {{platform-tracing-otel-extension}}) — горизонтальные по отношению к основной цепочке.

h3. AD-2. Bootstrap classloader isolation

{{platform-tracing-otel-extension}} грузится OTel Java Agent'ом до user classloader'а. Compile-time зависимости на Spring, {{web-error-model}} и любые application-классы запрещены. Контроль — CI-job: {{gradlew :platform-tracing-otel-extension:dependencies --configuration runtimeClasspath}} и grep на {{org.springframework.}} / {{space.br1440:web-error-model}}.

h3. AD-3. Контракт §37 «non-blocking»

Любое исключение в инфраструктуре трассировки молчаливо логируется на DEBUG и не достигает application thread. Реализация:

* {{SafeSpanProcessor}} — try-catch на каждый callback ({{onStart}}, {{onEnd}}, {{onEnding}}, {{forceFlush}}, {{shutdown}}); делегирование {{onEnding}} условное (только если delegate реализует {{ExtendedSpanProcessor}}).
* {{TracingRequestContextSupplier.get()}} — try-catch вокруг {{Span.current()}} и {{MDC.get(...)}}; fallback на {{RequestContext(correlationId, null, null)}}.
* {{TracingActuatorEndpoint.updateTracing}} — единственное исключение из правила: при невалидных входах *выбрасывает* {{InvalidRequestException}} (HTTP 400), потому что это explicit user-facing error response.

h3. AD-4. Контракт «always-on Supplier<RequestContext>»

Bean {{tracingRequestContextSupplier}} регистрируется в отдельной {{RequestContextSupplierAutoConfiguration}} *без* {{@ConditionalOnProperty}} / {{@ConditionalOnBean}} / {{after = TracingCoreAutoConfiguration.class}}. Обоснование: внешний errorhandling-стартер должен получать Supplier *независимо* от {{platform.tracing.enabled}}. При выключенной трассировке {{Span.current()}} возвращает {{Span.getInvalid()}}, supplier отдаёт {{RequestContext(correlationId, null, null)}} с {{correlationId}} из MDC. Регресс-инвариант покрыт {{ApplicationContextRunner}}-тестом с {{platform.tracing.enabled=false}}.

h3. AD-5. Использование web-error-model напрямую

{{InvalidRequestException}} и {{RuntimeConfigurationException}} объявлены {{final}} в {{web-error-model}} 2.0.0. Архитектурное следствие: собственная иерархия tracing-исключений *не создаётся*. Для actuator-валидации стартер выбрасывает {{InvalidRequestException}} напрямую — потребитель получает {{ErrorEntry}} с {{reason=INVALID_REQUEST}} и HTTP 400 через {{DefaultHttpStatusResolver}}. Зависимость на {{web-error-model}} — обязательная, через BOM, без {{@ConditionalOnClass}} на классы модели.

h3. AD-6. Минимум @Conditional

В стартере используются только следующие условные аннотации:

* {{@ConditionalOnProperty(prefix="platform.tracing", name="enabled", matchIfMissing=true)}} — корневой включатель;
* {{@ConditionalOnBean(PlatformTracing.class)}} — только там, где bean реально требуется в конструкторе;
* {{@ConditionalOnMissingBean}} — точечно для пользовательской замены;
* {{@ConditionalOnClass(RefreshScope.class)}} — только для опционального Spring Cloud Context.

Запрещены {{@ConditionalOnClass(name = "...ErrorEntry")}} и аналогичные условия на классы платформенной модели — если зависимость обязательна через BOM, conditional на её классы — мёртвый код.

h3. AD-7. Платформенные span-атрибуты — frozen контракт

Иммутабельный набор на v0.1.0:

|| Атрибут || Источник || Обязательность ||
| {{service.name}}, {{service.version}} | OTel Resource из конфигурации | required |
| {{platform.environment}} | OTel Resource | required |
| {{platform.c_group}}, {{platform.id}} | OTel Resource | required |
| {{platform.host}} | EnrichingSpanProcessor | required |
| {{platform.type}} | EnrichingSpanProcessor (derived from SpanKind) | required |
| {{platform.result}} | EnrichingSpanProcessor (derived from StatusCode) + Collector transform fallback | required |
| {{platform.timeout}} | SpanWatchdogProcessor (boolean) | conditional |

Изменения после v0.1.0 — только через breaking-change процедуру с major-bump.

h3. AD-8. Sampling-контракт

Приоритет в {{CompositeSampler}}:

# HTTP-заголовок {{X-Trace-On}} с допустимым значением → {{RECORD_AND_SAMPLE}}.
# {{X-QA-Trace}} (любое значение) → {{RECORD_AND_SAMPLE}}.
# По умолчанию: {{Sampler.parentBased(Sampler.traceIdRatioBased(samplingRatio))}}.

Рекомендации SRE:
* prod default: {{samplingRatio = 0.01}};
* load-test: {{0.001}};
* dev / staging: {{1.0}}.

h3. AD-9. TTL-tiers через routing connector

Конфигурация {{otel-collector-config-ttl-tiers.yaml}}: {{routing}} connector направляет trace по атрибуту {{platform.result}}:

* {{SERVER_ERROR}}, {{TIMEOUT}} → {{otlp/jaeger-long}};
* {{SUCCESS}}, {{CLIENT_ERROR}} → {{otlp/jaeger-short}}.

TTL Jaeger-инстансов настраивается SRE независимо от стартера. В application code — никакой зависимости от ретеншна.

h3. AD-10. Self-observability через MeterRegistry-потребление

Tracing-стартер не создаёт собственный {{MeterRegistry}}, а потребляет bean из {{spring-boot-starter-platform-metrics}} через DI ({{@ConditionalOnBean(MeterRegistry.class)}}). Платформенные counter'ы:

* {{platform.tracing.spans.started}} (теги: {{category}})
* {{platform.tracing.exceptions.recorded}} (теги: {{category}})
* {{platform.tracing.watchdog.timeouts}} (теги: {{level=span|trace}})
* {{platform.tracing.queue.dropped}}
* {{platform.tracing.priority.errored}}, {{...slow}}, {{...ok}}

h3. AD-11. MDC-контракт совладения с logging-стартером

Tracing — *писатель*, logging — *читатель*. Реализация через {{TracingMdcContextWrapper}} ({{io.opentelemetry.context.ContextStorage}}-обёртка), регистрируется единожды на JVM в {{@PostConstruct}}. Ключи:
* {{trace_id}} — 32 hex chars;
* {{span_id}} — 16 hex chars;
* {{trace_flags}} — 2 hex chars;
* {{X-Request-Id}} — корреляционный ID для {{RequestContext.correlationId}}.

logging-стартер не пишет эти ключи. Двустороннее владение запрещено.

h2. Внешние интерфейсы — финальный список

|| Категория || Значение ||
| Maven coordinates | {{space.br1440.platform.tracing:platform-tracing-spring-boot-starter:0.1.0}} |
| HTTP headers (in) | {{X-Trace-On}}, {{X-QA-Trace}} |
| HTTP headers (out) | {{X-Request-Id}}, {{X-Trace-Id}} |
| MDC keys | {{trace_id}}, {{span_id}}, {{trace_flags}}, {{X-Request-Id}} |
| Property prefix | {{platform.tracing.*}} |
| Actuator endpoints | {{GET /actuator/tracing}}, {{POST /actuator/tracing/\{property\}/\{value\}}} |
| Public SPI | {{SensitiveDataRule}}, {{SpanEnricher}}, {{SamplingDecisionContributor}} |

h2. Open follow-ups (post-v0.1.0)

# *Реактивная пропагация {{RequestContext}} в Reactor {{Context}}*. Текущий supplier работает с {{ThreadLocal}}-контекстом ({{Span.current()}}); в WebFlux-сценариях {{Subscriber.onNext}} в другом потоке потребует propagation handler. Кандидат на v0.2.0.
# *{{TracingExportException}}*. Сейчас фоновые ошибки экспорта в Collector логируются без структурированного {{ErrorEntry}}. Кандидат на введение при появлении реального callsite через Micrometer Observation для экспорт-сбоев.
# *{{platform-tracing-semconv-lint}}*. Модуль отложен текущим Epic'ом; решение о включении — после получения первой production-обратной связи от пилотного сервиса.
# *Контракт {{platform.result}} fallback на стороне Collector*. Текущий {{transform/normalize}} processor выводит {{platform.result}} из {{status.code}} только если атрибут отсутствует. Edge-case с явно установленным некорректным значением — отдельная задача SRE.
# *Cross-stack alignment с {{Tracing_GO}}*. После старта Go-Epic'а провести совместное ревью имён атрибутов и форс-заголовков, чтобы избежать расхождения стандарта между языковыми стеками.

h2. Sign-off

|| Stakeholder || Роль || Дата || Подтверждение ||
| {{<имя>}} | Architecture Board | {{DD.MM.YYYY}} | ссылка на запись ревью |
| {{<имя>}} | SRE / Observability lead | {{DD.MM.YYYY}} | ссылка на запись ревью |
| {{<имя>}} | Security lead | {{DD.MM.YYYY}} | ссылка на запись ревью |

После заполнения таблицы sub-task переводится в Done.
```
