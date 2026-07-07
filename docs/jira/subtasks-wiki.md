# Sub-tasks for Story `Tracing_JAVA / Поставка spring-boot-platform-tracing v0.1.0`

Контент каждого раздела ниже — готовый к вставке в поле **Description** соответствующей Jira sub-task'и. Формат: **Atlassian Wiki Markup** (старый синтаксис). Заголовок `# Sub-task N` — служебный, в Jira не вставляется; копируется только тело раздела.

---

# Sub-task 0 — Architecture design session

h2. Outcome

Утверждённая архитектура стартера, выраженная в трёх PlantUML-диаграммах (System Context / Component / Runtime Interaction). Sign-off Architecture Board, SRE и Security является блокером для sub-task'ов 1–7.

h2. Deliverables

* {{docs/architecture/01-context.puml}} — System Context (C4 L1)
* {{docs/architecture/02-component.puml}} — Component (C4 L3) с явным разделением application classloader / bootstrap classloader OTel Java Agent
* {{docs/architecture/03-interaction-with-logging-metrics.puml}} — runtime sequence-диаграмма точек совместного владения с {{spring-boot-starter-platform-logging}} и {{spring-boot-starter-platform-metrics}}
* SVG-render каждой диаграммы для embedding в DocPortal

h2. Architectural decisions to ratify

* Полный перечень платформенных span-атрибутов и зон владения (autoconfigure vs otel-extension)
* Изоляция bootstrap classloader для {{platform-tracing-otel-extension}}: запрет compile-time зависимостей на Spring и {{web-error-model}}
* Контракт §37 «non-blocking»: исключения инфраструктуры трассировки никогда не достигают application thread
* Контракт «always-on Supplier<RequestContext>»: bean доступен внешнему {{@ControllerAdvice}} независимо от {{platform.tracing.enabled}}
* Соответствие OpenTelemetry Semantic Conventions 1.27+ для HTTP server / client / database / messaging кейсов

h2. Acceptance criteria

# {{plantuml -checkonly docs/architecture/*.puml}} отрабатывает без warnings.
# Каждая нетривиальная архитектурная решённость сопровождается {{note}}-блоком с ссылкой на ADR или решение архитекторов.
# Контекстная диаграмма перечисляет всех актёров из Epic-описания и явно делегирует SRE-зону (OTel Collector, Jaeger short/long retention).
# Компонентная диаграмма явно разделяет classloader'ы и помечает зависимость на {{web-error-model}} исключительно у {{platform-tracing-spring-boot-autoconfigure}}.
# Runtime-диаграмма документирует три точки совладения (MDC, MeterRegistry, RequestContext) с однонаправленным data-flow.
# Sign-off Architecture Board, SRE, Security зафиксирован комментарием в sub-task'е со ссылкой на ревью.

h2. Out of scope

* Реализационные таски (sub-task 1–7) до получения sign-off
* Diagram-as-code сторонних модулей платформы

---

# Sub-task 1 — Repository scaffolding & BOM

h2. Outcome

Multi-module Gradle-проект с зафиксированными версиями зависимостей через BOM и pipeline публикации в корпоративный Nexus. Все последующие sub-task'и работают в готовом каркасе без изменения корневых build-скриптов.

h2. Deliverables

* {{settings.gradle}} (Gradle 8.14, Short DSL): {{rootProject.name = 'spring-boot-platform-tracing'}}, включены 7 модулей; {{platform-tracing-semconv-lint}} *НЕ* включается (отложено Architecture Board).
* {{build.gradle}} (root): Java toolchain 21, repositories ({{mavenLocal}} → {{mavenCentral}} → корпоративные Nexus-прокси), {{maven-publish}}, агрегирующие задачи {{publishAllToNexus}} и {{publishAllToMavenLocal}}, общие настройки {{withSourcesJar}} / {{withJavadocJar}} и {{Xdoclint:none}}.
* {{gradle.properties}}: фиксированные версии — Spring Boot 3.5.x, OpenTelemetry SDK 1.61.x, OTel Instrumentation 2.27.x (с alpha-каналом для Java Agent extension API), Spring Cloud Context 4.1.x, web-error-model 2.0.0.
* Модуль {{platform-tracing-bom}} ({{java-platform}}): импорт {{spring-boot-dependencies}}, {{opentelemetry-bom}}, {{opentelemetry-instrumentation-bom}}; constraints на {{opentelemetry-javaagent-extension-api}} (alpha), {{spring-cloud-context}}, {{web-error-model}}.
* Скелеты модулей: {{platform-tracing-api}}, {{platform-tracing-core}}, {{platform-tracing-spring-boot-autoconfigure}}, {{platform-tracing-spring-boot-starter}}, {{platform-tracing-otel-extension}}, {{platform-tracing-collector-config}}, {{platform-tracing-test}}.
* CI: pipeline-job на {{gradlew clean build}} с публикацией в {{platform-maven-dev}} snapshot repository.

h2. Acceptance criteria

# {{gradlew clean build}} зелёный на чистом окружении (Gradle 8.14, Java 21, без локального cache).
# {{gradlew :platform-tracing-bom:publishToMavenLocal}} публикует валидный POM с корректным {{<dependencyManagement>}}-блоком.
# {{gradlew dependencyInsight}} для любой управляемой зависимости из BOM возвращает корректную версию без conflict-resolution warnings.
# В {{repositories}}-блоке корневого {{build.gradle}} {{mavenLocal()}} стоит первым — локальные перепубликации платформенных зависимостей перекрывают Nexus при разработке.
# Артефакт {{space.br1440.platform.tracing:platform-tracing-bom:0.1.0-SNAPSHOT}} опубликован в {{platform-maven-dev}}.

h2. Constraints

* Версии зависимостей в {{gradle.properties}} — single source of truth; изменения требуют ревью архитекторов.
* {{platform-tracing-semconv-lint}} *НЕ* включается в {{settings.gradle}}.

h2. Dependencies

Sub-task 0 (sign-off дизайна).

---

# Sub-task 2 — Public API & core implementations

h2. Outcome

Стабильный публичный контракт стартера и базовые реализации, на которые опираются autoconfigure и otel-extension. После закрытия sub-task'а API считается *frozen* на v0.1.0 — последующие изменения проходят через breaking-change процедуру.

h2. Module: `platform-tracing-api`

* Контракт {{space.br1440.platform.tracing.api.PlatformTracing}}:
** {{Span startSpan(String name, SpanCategory category, Map<String, ?> attributes)}}
** {{<T> T withinSpan(String name, SpanCategory category, ThrowingSupplier<T> body)}}
** {{void recordException(Throwable t, Map<String, ?> attributes)}}
** {{Optional<String> currentTraceId()}}, {{Optional<String> currentSpanId()}}
* Перечисления {{SpanCategory}} ({{HTTP_SERVER}}, {{HTTP_CLIENT}}, {{DB}}, {{MESSAGING}}, {{INTERNAL}}), {{SpanResult}} ({{SUCCESS}}, {{CLIENT_ERROR}}, {{SERVER_ERROR}}, {{TIMEOUT}}).
* Helper {{SpanScope}} ({{Closeable}}-обёртка с idempotent {{close()}}).
* Аннотации {{@Traced}} (поля {{name}}, {{category}}, {{recordException}}, SpEL-выражения для атрибутов) и {{@TracedAttribute}} (для параметров метода).
* Константы {{PlatformAttributes}} — соответствие OpenTelemetry Semantic Conventions 1.27+ + платформенные расширения ({{platform.type}}, {{platform.c_group}}, {{platform.id}}, {{platform.host}}, {{platform.result}}, {{platform.timeout}}).
* Константы {{PlatformHeaders}} — {{X-Trace-On}}, {{X-QA-Trace}}, {{X-Request-Id}}, {{X-Trace-Id}} (стандартный W3C {{traceparent}} обрабатывается OTel propagator'ами и в платформенных константах не дублируется).
* SPI: {{SensitiveDataRule}}, {{SpanEnricher}}, {{SamplingDecisionContributor}} — каждая помечена Javadoc-аннотацией {{@apiNote SPI extension point}}.

h2. Module: `platform-tracing-core`

* {{DefaultPlatformTracing}} — адаптер над {{io.opentelemetry.api.trace.Tracer}}; функциональный no-op detection через создание probe-span'а и проверку {{SpanContext.isValid()}}, чтобы корректно ловить состояние {{GlobalOpenTelemetry.noop()}} без reference-equality.
* {{NoOpPlatformTracing}} — singleton ({{INSTANCE}}); все методы — pure no-op без обращения к OTel API.
* {{ExceptionRecorder}} — безопасная запись ошибок в span ({{error.type}}, sanitized exception-event); используется span builder'ами и autoconfigure.

h2. Acceptance criteria

# Все публичные классы документированы Javadoc'ом; SPI-точки расширения помечены {{@apiNote}}.
# Unit-тесты покрывают {{DefaultPlatformTracing}} и {{NoOpPlatformTracing}} с использованием {{opentelemetry-sdk-testing}} ({{InMemorySpanExporter}}).
# {{NoOpPlatformTracing.startSpan(...)}} отрабатывает без NPE и без обращения к {{GlobalOpenTelemetry}} (verified через Mockito {{verifyNoInteractions}}).
# Аннотации имеют корректные {{@Retention(RUNTIME)}}, {{@Target}} и {{@Documented}}.
# Code coverage публичных контрактов и SPI ≥ 80% по line; 100% по branch для критичных no-op путей.

h2. Constraints

* OpenTelemetry Semantic Conventions версии 1.27+; отклонения от стандарта документируются в Javadoc с обоснованием.
* Контракт SPI ({{SensitiveDataRule}}, {{SpanEnricher}}, {{SamplingDecisionContributor}}) считается публичным API — изменения сигнатур после freeze требуют major-bump.

h2. Dependencies

Sub-task 1.

---

# Sub-task 3 — Spring Boot autoconfigure: AOP / MDC / Observation / Self-metrics / Actuator (read)

h2. Outcome

Слой автоматической инструментации Spring Boot 3.x приложения: AOP-обработка {{@Traced}}, синхронизация trace-контекста с MDC через Servlet и WebFlux фильтры, расширение Micrometer Observation платформенными атрибутами для server/client span'ов, публикация self-observability метрик и read-операции Actuator endpoint. Write-операции Actuator — sub-task 7.

h2. Components

* {{TracingProperties}} — {{@ConfigurationProperties("platform.tracing")}} с JSR-303 валидацией: {{@DecimalMin/@DecimalMax}} для {{samplingRatio}}, {{@Positive}} для timeout'ов, {{@NotBlank}} для {{service.name}}.
* AOP: {{TracedAspect}} ({{@Aspect}}, {{@Order(Ordered.HIGHEST_PRECEDENCE + 100)}}); resolve аннотаций через {{AopUtils.getMostSpecificMethod}} для корректной работы с interface-методами через Spring AOP proxy.
* MDC: {{TracingMdcKeys}} (ключи {{trace_id}}, {{span_id}}, {{trace_flags}}); {{TracingMdcServletFilter extends OncePerRequestFilter}}, {{Ordered.HIGHEST_PRECEDENCE}}; {{TracingMdcWebFilter implements WebFilter}} для WebFlux.
* Micrometer Observation: {{PlatformServerRequestObservationConvention}}, {{PlatformReactiveServerRequestObservationConvention}}, {{PlatformClientRequestObservationConvention}} — добавляют {{platform.type}}, {{platform.result}} к стандартным HTTP-атрибутам Spring Web.
* Self-observability: {{PlatformTracingMetrics}} ({{Counter}} {{platform.tracing.spans.started}}, {{platform.tracing.exceptions.recorded}} с тегом {{category}}); декоратор {{MeteredPlatformTracing implements PlatformTracing}} ({{@Primary}} при наличии {{MeterRegistry}}).
* Actuator: {{TracingHealthIndicator}} ({{HealthIndicator}}; статус {{OUT_OF_SERVICE}} при {{platform.tracing.enabled=false}}); {{TracingActuatorEndpoint}} ({{@Endpoint(id = "tracing")}}, read-операция возвращает детерминированный JSON).
* AutoConfiguration-классы (все с {{@AutoConfiguration}} и общим gate {{@ConditionalOnProperty(prefix = "platform.tracing", name = "enabled", matchIfMissing = true)}}):
** {{TracingCoreAutoConfiguration}} — bean {{PlatformTracing}}
** {{TracingAopAutoConfiguration}} — bean {{TracedAspect}} под {{@ConditionalOnClass(EnableAspectJAutoProxy.class)}}
** {{TracingMetricsAutoConfiguration}} — bean {{MeteredPlatformTracing}} под {{@ConditionalOnBean(MeterRegistry.class)}}
** {{TracingObservationAutoConfiguration}} — server/client conventions
** {{TracingActuatorAutoConfiguration}} — endpoint и health indicator
** {{ServletTracingAutoConfiguration}}, {{ReactiveTracingAutoConfiguration}} — фильтры под {{@ConditionalOnWebApplication(type = SERVLET|REACTIVE)}}
* Регистрация всех AutoConfiguration-классов в {{META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}}.

h2. Acceptance criteria

# Интеграционные тесты через {{ApplicationContextRunner}} покрывают:
#* подъём всех bean'ов под {{platform.tracing.enabled=true}}
#* graceful fallback на {{NoOpPlatformTracing}} при отсутствии настроенного {{GlobalOpenTelemetry}} (functional check через probe-span)
#* подмену каждого bean'а через user-defined {{@Bean}} (контракт {{@ConditionalOnMissingBean}})
# {{TracedAspect}} корректно обрабатывает Spring AOP proxy для interface-метода vs implementation-метода (regression test на резолв аннотации через {{getMostSpecificMethod}}).
# Каждый {{ObservationConvention}} покрыт тестом, проверяющим наличие {{platform.type}} и {{platform.result}} в финальном span'е через {{InMemorySpanExporter}}.
# {{MeteredPlatformTracing}} инкрементирует counter'ы при {{startSpan}} и {{recordException}} (verified через {{MeterRegistry.find(...).counter()}}).
# {{GET /actuator/tracing}} возвращает JSON, контракт схемы покрыт snapshot-тестом.
# Coverage модуля по line ≥ 80%; обязательное покрытие фильтров и SPI-точек.

h2. Constraints

* Совместимость со Spring Boot 3.5.x; используется {{@AutoConfiguration}} (не {{@Configuration}}).
* Запрещены ссылки на internal API Spring Boot ({{org.springframework.boot.autoconfigure.internal.*}}).

h2. Dependencies

Sub-task 1, sub-task 2.

---

# Sub-task 4 — OTel Java Agent extension: enrichment, scrubbing, sampling, resource

h2. Outcome

Bootstrap-classloader extension JAR, регистрируемый OpenTelemetry Java Agent через {{otel.javaagent.extensions}}. Обогащает span'ы платформенными атрибутами, маскирует PII по встроенному набору правил, выполняет sampling по корпоративным заголовкам и публикует платформенные ресурсные атрибуты в OTel {{Resource}}.

h2. Components

* {{SafeSpanProcessor implements ExtendedSpanProcessor}} — декоратор-обёртка с try-catch на каждый callback ({{onStart}}, {{onEnd}}, {{onEnding}}, {{forceFlush}}, {{shutdown}}); гасит {{RuntimeException}} от вложенного процессора с логированием на DEBUG (контракт §37). Делегирование {{onEnding}} условное: только если delegate реализует {{ExtendedSpanProcessor}}.
* {{CompositeSampler implements Sampler}}:
** Приоритет 1: HTTP-заголовок {{X-Trace-On}} со значением из {{platform.tracing.sampling.force-record-header-values}} → {{RECORD_AND_SAMPLE}}
** Приоритет 2: {{X-QA-Trace}} (любое значение) → {{RECORD_AND_SAMPLE}}
** Приоритет 3 (default): {{Sampler.parentBased(Sampler.traceIdRatioBased(samplingRatio))}}
* {{EnrichingSpanProcessor implements ExtendedSpanProcessor}} — на {{onEnding}}:
** {{platform.type}} — derived из {{SpanKind}} (mapping в {{PlatformAttributes}})
** {{platform.host}} — из {{Resource}} либо {{InetAddress.getLocalHost()}}
** {{platform.result}} — derived из {{StatusCode}} ({{UNSET}}/{{OK}} → {{SUCCESS}}, {{ERROR}} → {{SERVER_ERROR}})
* {{ScrubbingSpanProcessor implements ExtendedSpanProcessor}} + {{BuiltInSensitiveDataRules}}:
** Email (RFC 5322 simplified pattern)
** JWT (3-part base64-pattern)
** Password keys (attribute-name match: {{password}}, {{passwd}}, {{pwd}}, {{secret}}, {{token}})
** PAN (Luhn-validated 13–19 digits)
** Phone numbers (E.164)
* {{ValidatingSpanProcessor implements SpanProcessor}} — на {{onEnd}} логирует WARN при отсутствии обязательных атрибутов: {{service.name}}, {{platform.c_group}}, {{platform.id}}.
* {{PlatformResourceProvider implements ResourceProvider}} — заполняет {{Resource}} из OTel {{ConfigProperties}}: {{service.name}}, {{service.version}}, {{platform.environment}}, {{platform.c_group}}, {{platform.id}}, {{platform.host}}.
* SPI-регистрация:
** {{META-INF/services/io.opentelemetry.sdk.autoconfigure.spi.ResourceProvider}}
** {{PlatformAutoConfigurationCustomizer implements AutoConfigurationCustomizerProvider}} — регистрация sampler'а и span-процессоров (каждый завёрнут в {{SafeSpanProcessor}})

h2. Acceptance criteria

# Unit-тесты для каждого процессора и sampler'а с использованием {{opentelemetry-sdk-testing}} и {{InMemorySpanExporter}}.
# {{SafeSpanProcessor}}: тесты на корректное делегирование {{onEnding}} если delegate реализует {{ExtendedSpanProcessor}}, и no-op при стандартном {{SpanProcessor}}.
# {{CompositeSampler}}: 6 кейсов — force-record true/false, QA, child span с наследованием родительского решения, ratio = 0.0, ratio = 1.0.
# {{BuiltInSensitiveDataRules}}: каждое правило покрыто positive- и negative-тестами; PAN-Luhn покрыт edge-кейсом числа корректной длины, не проходящего Luhn.
# Constraint check (CI): {{gradlew :platform-tracing-otel-extension:dependencies --configuration runtimeClasspath}} *НЕ* содержит {{org.springframework.*}} и {{space.br1440:web-error-model}} (grep-проверка).
# Smoke: extension JAR подключается к OTel Java Agent через {{-Dotel.javaagent.extensions=...}}, span'ы пилотного сервиса экспортируются в локальный Collector с платформенными атрибутами.

h2. Constraints

* Bootstrap classloader: запрещены любые compile-time зависимости на Spring и {{web-error-model}}.
* {{ExtendedSpanProcessor}} — internal API OTel SDK 1.61.x; версия фиксируется в BOM, при upgrade'е требуется regression-тест.

h2. Dependencies

Sub-task 1, sub-task 2.

---

# Sub-task 5 — OTel Java Agent extension: reliability (watchdog / drop-oldest / prioritizing)

h2. Outcome

Защита экспортного пайплайна от утечек ресурсов и деградации Collector'а: принудительное завершение span'ов по timeout'у, drop-oldest политика заполненной очереди и приоритезация ERROR/SLOW span'ов под back-pressure.

h2. Components

* {{SpanWatchdogProcessor implements SpanProcessor}}:
** {{ScheduledExecutorService}} (named threads, daemon = true)
** Per-span tracking через {{ConcurrentHashMap<SpanId, WatchedEntry>}}
** Принудительное завершение span'а при превышении {{platform.tracing.limits.span-timeout}}: {{setStatus(StatusCode.ERROR)}}, attribute {{platform.timeout=true}}, {{end()}}
** Per-trace tracking: при превышении {{platform.tracing.limits.trace-timeout}} закрываются все активные span'ы trace'а
** Self-metric: {{platform.tracing.watchdog.timeouts}} ({{Counter}} с тегом {{level=span|trace}})
* {{DropOldestBatchSpanProcessor implements SpanProcessor}}:
** {{ArrayBlockingQueue<ReadableSpan>}} capacity = {{platform.tracing.queue.max-size}}
** При {{offer() == false}} — {{pollFirst()}} + повторный {{offer()}} (drop-oldest semantics)
** Worker thread: batch-export каждые {{platform.tracing.queue.export-batch-size}} либо по {{platform.tracing.queue.export-timeout}}
** Self-metric: {{platform.tracing.queue.dropped}} ({{Counter}})
* {{PrioritizingSpanProcessor implements SpanProcessor}}:
** Три независимые очереди: {{errorQueue}}, {{slowQueue}}, {{okQueue}}
** Классификация: {{StatusCode.ERROR}} → error; {{duration > slow-threshold}} → slow; иначе → ok
** Worker thread: drain-and-export в порядке error → slow → ok с per-tier batch-size
** Self-metric: {{platform.tracing.priority.{tier}}} ({{Counter}})
* Регистрация в {{PlatformAutoConfigurationCustomizer}} под флагами:
** {{platform.tracing.queue.policy = drop-oldest|prioritizing}} (default {{drop-oldest}})
** {{platform.tracing.limits.span-timeout}} (default {{PT5M}})
** {{platform.tracing.limits.trace-timeout}} (default {{PT10M}})

h2. Acceptance criteria

# Concurrency-тесты с {{CompletableFuture}} parallel-load (≥ 1000 span/sec): отсутствие утечек памяти (verified через JFR-снимок heap), корректная статистика drop'ов.
# {{SpanWatchdogProcessor}}: тест с искусственно «зависшим» span'ом подтверждает закрытие по timeout'у и наличие {{platform.timeout=true}}.
# {{DropOldestBatchSpanProcessor}}: тест с переполнением очереди подтверждает drop головы (FIFO order) и неблокирующее поведение producer'а.
# {{PrioritizingSpanProcessor}}: stress-тест с насыщенной очередью подтверждает экспорт ERROR-span'ов первыми (verified через timestamp sequence в exporter'е).
# Smoke: пилотный сервис под нагрузкой 100 RPS с искусственно деградированным Collector'ом (iptables drop) — деградация RPS не более 1%, потери span'ов учитываются в self-metrics.

h2. Constraints

* Reliability-процессоры регистрируются в pipeline *после* {{EnrichingSpanProcessor}} и {{ScrubbingSpanProcessor}} (sub-task 4) — иначе утрачиваются обогащение и маскирование.

h2. Dependencies

Sub-task 4.

---

# Sub-task 6 — OTel Collector configuration

h2. Outcome

Две согласованные с SRE production-ready конфигурации OpenTelemetry Collector: стандартная (единый Jaeger backend) и TTL-tiers (раздельные backends по результату trace'а через {{routing}} connector).

h2. Standard configuration ({{otel-collector-config.yaml}})

receivers:
* {{otlp}} (gRPC port 4317, HTTP port 4318)

processors (в порядке pipeline):
* {{tail_sampling}}, policies:
** {{errors}} — {{status_code: ERROR}}
** {{latency-slow}} — {{latency.threshold_ms: 1000}}
** {{forced-record}} — attribute {{platform.force_record: true}}
** {{qa-traces}} — attribute {{platform.qa: true}}
** {{drop-health-checks}} — {{http.target}} matches {{^/actuator/(health|info|prometheus)$}} → {{DROP}}
** {{default-probabilistic}} — {{sampling_percentage: 1.0}}
* {{transform/normalize}} — fallback для {{platform.result}} если отсутствует (set из {{status.code}})
* {{attributes/limits}} — {{count_limit: 64}}, {{value_length_limit: 4096}}
* {{attributes/scrubbing}} — secondary line of defence: regex-based маскирование email/JWT/PAN на стороне Collector'а

exporters:
* {{otlp/jaeger}} — TLS, retry-on-failure ({{max_elapsed_time: 5m}}), {{sending_queue}} ({{num_consumers: 10}}, {{queue_size: 5000}})

h2. TTL-tiers configuration ({{otel-collector-config-ttl-tiers.yaml}})

Дополнительно к стандартной:
* connector {{routing}} по {{platform.result}}:
** {{failure}} ({{SERVER_ERROR}}, {{TIMEOUT}}) → {{otlp/jaeger-long}}
** {{success}} ({{SUCCESS}}, {{CLIENT_ERROR}}) → {{otlp/jaeger-short}}
* Два {{otlp}} exporter'а с разными endpoints

h2. Acceptance criteria

# {{otelcol-contrib validate --config <file>}} проходит без warnings для обеих конфигураций (CI step).
# Smoke-тест с docker-compose (Collector + Jaeger short + Jaeger long): success-trace попадает только в short, failure-trace — только в long (verified через Jaeger HTTP API search).
# README модуля содержит:
#* описание каждой tail_sampling политики с примером входного span'а
#* рекомендации SRE по {{decision_wait}}, {{num_traces}}, {{expected_new_traces_per_second}} под профили нагрузки 1k/10k/100k RPS
#* checklist для on-call на случай перегрузки Collector'а
# Sign-off SRE на обе конфигурации (комментарий в Jira со ссылкой на ревью).

h2. Constraints

* Минимальная версия OTel Collector — 0.110 (стабильный {{routing}} connector, {{tail_sampling}} v2).

h2. Dependencies

Sub-task 0 (зафиксированный список платформенных атрибутов).

---

# Sub-task 7 — Integrations: MDC ContextStorage, Actuator write-operations, web-error-model, @RefreshScope

h2. Outcome

Замыкание граничных интеграций стартера: автоматическая синхронизация OTel-контекста с MDC, dynamic-управление через Actuator write-операции, поставка {{Supplier<RequestContext>}} для внешнего errorhandling-{{@ControllerAdvice}} и опциональная {{@RefreshScope}}-перенастройка через Spring Cloud Context.

h2. Components

* {{TracingMdcContextWrapper implements io.opentelemetry.context.ContextStorage}}:
** Регистрация через {{ContextStorage.addWrapper()}} в {{@PostConstruct}} {{TracingMdcContextAutoConfiguration}}
** На {{attach(Context)}}: синхронизация {{Span.fromContext(...).getSpanContext()}} с MDC ключами {{trace_id}}, {{span_id}}, {{trace_flags}}
** На {{Scope.close()}}: восстановление предыдущих значений (поддержка вложенных scope'ов)
** Защита от повторной регистрации: {{ConcurrentHashMap<ClassLoader, AtomicBoolean>}}, гарантия однократности на JVM
* {{TracingActuatorEndpoint.updateTracing(@Selector property, @Selector value)}} ({{@WriteOperation}}):
** Валидация: {{property ∈ {enabled, samplingRatio}}}; {{samplingRatio}} парсится как {{double}} и проверяется на диапазон {{[0.0, 1.0]}}
** При невалидных входах *выбрасывается* {{space.br1440.platform.errorhandling.exception.validation.InvalidRequestException}} (HTTP 400 через {{DefaultHttpStatusResolver}} по маркеру {{httpstatus.BadRequestException}})
** *Собственные классы исключений не создаются*: {{InvalidRequestException}} объявлен {{final}} в {{web-error-model}}
** Аудит-логирование изменений на INFO с {{previous → applied}}
* {{PlatformTraceContext}} (record): {{traceId}}, {{spanId}}, {{traceFlags}}, {{requestId}} — extended diagnostic context.
* {{PlatformTraceContextProvider}} — bean расширенного контекста; зависит от {{PlatformTracing}}.
* {{TracingRequestContextSupplier implements Supplier<RequestContext>}}:
** {{Span.current().getSpanContext()}} обёрнут в try-catch (контракт §37)
** {{correlationId}} читается из MDC по ключу {{PlatformHeaders.X_REQUEST_ID}} через safe-MDC reader
** Возвращает {{RequestContext(correlationId, traceId, spanId)}} либо fallback {{RequestContext(correlationId, null, null)}}
* {{RequestContextSupplierAutoConfiguration}} ({{@AutoConfiguration}}, *БЕЗ* {{after = ...}}, *БЕЗ* {{@ConditionalOnProperty}}, *БЕЗ* {{@ConditionalOnBean}}):
** Bean {{tracingRequestContextSupplier}} регистрируется *всегда* при наличии модуля на classpath
** Только {{@ConditionalOnMissingBean(name = "tracingRequestContextSupplier")}} для пользовательской замены
** Javadoc с явным предупреждением: «не добавлять {{after = TracingCoreAutoConfiguration.class}} по аналогии — сломает always-on контракт»
* {{ErrorhandlingTracingAutoConfiguration}}:
** *Удалить* {{@ConditionalOnClass(name = "...ErrorEntry")}} — {{web-error-model}} теперь обязательная BOM-зависимость
** Сохранить {{@ConditionalOnBean(PlatformTracing.class)}} — {{PlatformTraceContextProvider}} требует {{PlatformTracing}} в конструкторе
* {{TracingRefreshScopeAutoConfiguration}}:
** {{@ConditionalOnClass(org.springframework.cloud.context.scope.refresh.RefreshScope.class)}}
** Регистрирует {{TracingProperties}} как {{@RefreshScope @Primary}} bean
* Регистрация {{RequestContextSupplierAutoConfiguration}} в {{AutoConfiguration.imports}}.

h2. Acceptance criteria

# {{TracingActuatorEndpointTest}}: 6 тестов; отрицательные кейсы (out-of-range / NaN / unknown property) бросают {{InvalidRequestException}} (verified через {{assertThatThrownBy(...).isInstanceOf(InvalidRequestException.class)}}).
# {{TracingRequestContextSupplierTest}}: 5 тестов, включая «не бросает исключение при сбое OpenTelemetry-контекста» через {{try-with-resources MockedStatic<Span>}} (изоляция в параллельном Gradle Test Worker).
# {{RequestContextSupplierAutoConfigurationTest}} ({{ApplicationContextRunner}}): bean {{Supplier<RequestContext>}} зарегистрирован при {{platform.tracing.enabled=false}} (главный регресс-инвариант).
# {{TracingMdcContextWrapperTest}}: тест на однократную регистрацию {{ContextStorage.addWrapper()}}, корректная синхронизация MDC при вложенных scope'ах.
# {{gradlew :platform-tracing-spring-boot-autoconfigure:dependencyInsight --dependency web-error-model --configuration compileClasspath}} подтверждает разрешение через BOM.

h2. Constraints

* {{web-error-model}} подключается как {{api}}-зависимость в {{platform-tracing-spring-boot-autoconfigure}} через BOM; явная {{version}} *НЕ* указывается.
* В {{platform-tracing-otel-extension}} зависимость на {{web-error-model}} запрещена (изоляция bootstrap classloader, sub-task 4).

h2. Dependencies

Sub-task 1 (BOM с {{web-error-model:2.0.0}}), sub-task 3 (autoconfigure-каркас с {{TracingActuatorEndpoint}} read-операцией), sub-task 4 (отсутствие циклических зависимостей с extension-JAR).

---

# Sub-task 8 — DocPortal documentation

h2. Outcome

Опубликованная страница {{Platform Tracing Starter}} на корпоративном DocPortal — единый референс для команд-потребителей (Java) и точка входа в архитектурные решения. Без публикации страницы Story не закрывается.

h2. Page structure

# *Quickstart* — Maven coordinates ({{space.br1440.platform.tracing:platform-tracing-spring-boot-starter:0.1.0}}), минимальный {{application.yml}}, ожидаемое поведение в Jaeger.
# *Configuration reference* — таблица всех {{platform.tracing.*}} свойств: ключ, тип, дефолт, допустимый диапазон, ссылка на ADR при необычных дефолтах.
# *Span attributes* — обязательные / опциональные платформенные атрибуты, маппинг в OpenTelemetry Semantic Conventions 1.27+.
# *Annotations* — {{@Traced}}, {{@TracedAttribute}} с примерами и SpEL-конструкциями.
# *Sampling* — {{X-Trace-On}}, {{X-QA-Trace}}, динамическое изменение через {{POST /actuator/tracing/samplingRatio/<value>}}, рекомендации по {{samplingRatio}} для production / load-test / dev.
# *PII / scrubbing* — встроенные правила, расширение через {{SensitiveDataRule}} SPI, процедура согласования новых правил с Security.
# *Reliability* — watchdog / drop-oldest / prioritizing; контракт §37.
# *Errorhandling integration* — {{Supplier<RequestContext>}} для {{@ControllerAdvice}}, матрица «место выброса → класс из web-error-model → reason → HTTP», явное упоминание always-on контракта.
# *Actuator endpoint* — read и write операции, схема JSON, аудит-логирование изменений.
# *Operational* — конфигурации Collector (стандарт + TTL-tiers), tail_sampling политики, рекомендации SRE по {{decision_wait}}, {{num_traces}}.
# *SPI / extensibility* — {{SensitiveDataRule}}, {{SpanEnricher}}, {{SamplingDecisionContributor}} с примерами реализации.
# *Migration / Troubleshooting* — частые проблемы (потерянный MDC в WebFlux, утечка span'а, отказ Collector, конфликт версий OTel SDK).
# *Architecture* — три PlantUML-диаграммы из sub-task 0 в виде SVG.

h2. Acceptance criteria

# Страница опубликована в production DocPortal под разделом {{Platform / Observability / Tracing}}.
# Все таблицы свойств / атрибутов сверены с актуальным кодом (regression-проверка на основе сгенерированного {{spring-boot-configuration-metadata.json}}).
# Ссылка на страницу добавлена в {{README.md}} корня репозитория и в release notes артефакта {{0.1.0}}.
# Sign-off Architecture Board, SRE и tech writer'а зафиксирован комментарием в Jira.

h2. Dependencies

Sub-tasks 0–7 (страница агрегирует deliverables всех предшественников).
