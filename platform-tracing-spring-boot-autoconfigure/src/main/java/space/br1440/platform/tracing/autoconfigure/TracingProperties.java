package space.br1440.platform.tracing.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Платформенная конфигурация модуля трассировки.
 * <p>
 * Все значения описаны декларативно, чтобы их можно было переопределять через
 * {@code application.yml}, переменные окружения или Spring Cloud Config без модификаций кода.
 * <p>
 * Конфигурация разбита на логические подразделы: настройки сервиса, сэмплирование, лимиты данных,
 * очередь экспорта, скраббинг, экспортер и заголовки ответа.
 */
@Getter
@Setter
@Accessors(chain = true)
@ConfigurationProperties(prefix = TracingProperties.PREFIX)
public class TracingProperties {

    public static final String PREFIX = "platform.tracing";

    /** Глобальный выключатель платформенной трассировки. */
    private boolean enabled = true;

    private final Sdk sdk = new Sdk();
    private final Service service = new Service();
    private final Resource resource = new Resource();
    private final Facade facade = new Facade();
    private final Sampling sampling = new Sampling();
    private final Limits limits = new Limits();
    private final Queue queue = new Queue();
    private final Scrubbing scrubbing = new Scrubbing();
    private final Exporter exporter = new Exporter();
    private final Response response = new Response();
    private final ServiceNames serviceNames = new ServiceNames();
    private final Aop aop = new Aop();
    private final Suppression suppression = new Suppression();
    private final Enriching enriching = new Enriching();
    private final Validation validation = new Validation();
    private final Semantic semantic = new Semantic();
    private final Watchdog watchdog = new Watchdog();
    private final Propagation propagation = new Propagation();
    private final Kafka kafka = new Kafka();
    private final ContextPropagation contextPropagation = new ContextPropagation();
    private final Diagnostics diagnostics = new Diagnostics();
    private final Actuator actuator = new Actuator();

    /**
     * Режим работы относительно OpenTelemetry SDK (Фаза 15).
     * <p>
     * Платформа agent-first: starter не создаёт собственный SDK, а потребляет
     * {@code OpenTelemetry}/{@code GlobalOpenTelemetry}. Свойство — диагностика и явность режима,
     * а не переключатель создания SDK. См. {@code ADR-sdk-mode-detection}.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Sdk {
        /**
         * Режим: {@code AUTO} (детект), {@code AGENT}, {@code STARTER}, {@code EXTERNAL},
         * {@code DISABLED}. По умолчанию {@code AUTO}. {@code DISABLED} — единственный режим,
         * в котором фасад становится {@code NoopTraceOperations}.
         * <p>
         * Дублируется в agent-канал ({@code platform.tracing.sdk.mode} в {@code ConfigProperties})
         * только для диагностики на стороне расширения (см. {@code ADR-dual-channel-properties}).
         */
        private space.br1440.platform.tracing.autoconfigure.support.SdkMode mode =
                space.br1440.platform.tracing.autoconfigure.support.SdkMode.AUTO;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Service {
        /** Логическое имя сервиса (попадает в ресурсный атрибут {@code service.name}). */
        private String name;

        /** Версия сервиса (попадает в ресурсный атрибут {@code service.version}). */
        private String version;

        /** Среда исполнения: dev, stage, prod, иное согласованное значение. */
        private String environment;

        /** Идентификатор организационной группы (C-Group). */
        private String cGroup;

        /**
         * Runtime ID контейнера (OpenTelemetry semconv {@code container.id}).
         * Пробрасывается в {@code platform.tracing.service.container-id} для OTel Agent.
         */
        private String containerId;

        // k8s.pod.uid удалён (Фаза 9): обогащение Kubernetes-метаданными — зона Collector
        // k8sattributes / Downward API, а не JVM-провайдера. См. ADR-resource-merge-precedence.
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Resource {
        /** Версия платформенной resource-policy ({@code platform.tracing.policy.version}). */
        private String policyVersion;

        /** Нормализовать ли {@code environment} к well-known OTel-значениям. */
        private boolean normalizeEnvironment = true;

        /** Режим валидации resource-идентичности на старте: {@code LENIENT} | {@code STRICT}. */
        private String validationMode = "LENIENT";

        /** Opt-in procfs-детекта {@code container.id} (по умолчанию выключен). */
        private boolean detectContainerId = false;
    }

    /**
     * Гранулярный toggle платформенного фасада ({@code TraceOperations}/{@code DefaultTraceOperations}).
     * <p>
     * Runtime-mutable (Фаза 14): при {@code enabled=false} фасад отдаёт no-op span handle, не затрагивая
     * auto-instrumentation OTel Agent. Wiring — PR-4.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Facade {
        /** Включает платформенный фасад создания span'ов. */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Sampling {
        /**
         * Sampling runtime config schema v1 (PR-6E) — runtime-mutable поля, публикуемые одним
         * атомарным {@code updateSamplingPolicy} через {@link RuntimeConfigApplier}:
         * {@code enabled}, {@code ratio}, {@code dropPaths}, {@code forceRecordHeaderValues},
         * {@code routeRatios}. Agent-side {@code SamplerStateHolder} — source of truth; Spring —
         * input/reconciliation layer only.
         * <p>
         * Гранулярный toggle сэмплирования (kill-switch). Runtime-mutable (Фаза 14).
         * При {@code false} платформенный sampler не форсирует/не режет — поведение KillSwitchRule.
         */
        private boolean enabled = true;

        /** Базовая вероятность сэмплирования trace'ов в диапазоне [0.0, 1.0]. */
        private double ratio = 0.1;

        /**
         * Per-route вероятности сэмплирования: ключ — route/path-префикс, значение — ratio в [0.0, 1.0].
         * Runtime-mutable (Фаза 14). Пустая карта означает «использовать только {@link #ratio}».
         */
        private Map<String, Double> routeRatios = new LinkedHashMap<>();

        /** Имя HTTP-заголовка принудительной записи trace'а. */
        private String forceRecordHeader = PlatformHeaders.X_TRACE_ON;

        /** Значения, при которых заголовок {@link #forceRecordHeader} активирует принудительную запись. */
        private List<String> forceRecordHeaderValues = new ArrayList<>(List.of("on"));

        /** Имя QA-маркерного заголовка, гарантирующего 100% запись. */
        private String qaForceHeader = PlatformHeaders.X_QA_TRACE;

        /**
         * Список path-префиксов, для которых head-sampling возвращает {@code DROP} и span не
         * создаётся вовсе. Сравнение идёт по атрибуту {@code url.path} (raw URL), а не по
         * {@code http.route} — последний на head-sampling ещё не проставлен Spring MVC.
         * Force/QA-заголовки имеют приоритет и отменяют drop.
         * <p>
         * Дефолтный набор выровнен с дефолтом агентского расширения и закрывает
         * типичные actuator-эндпоинты, которые забивают tail-sampling Collector'а в
         * Kubernetes-окружениях.
         */
        private List<String> dropPaths = new ArrayList<>(List.of(
                "/actuator/health", "/actuator/prometheus", "/actuator/info"
        ));
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Limits {
        /**
         * Максимальное количество пользовательских атрибутов на span.
         * <p>
         * Aligned with platform tracing defaults. Span-limit drift is monitored by
         * {@code DualChannelDriftDiagnostics}.
         */
        private int maxAttributes = 50;

        /**
         * Максимальная длина строкового значения атрибута (в символах).
         * <p>
         * Aligned with platform tracing defaults. Span-limit drift is monitored by
         * {@code DualChannelDriftDiagnostics}.
         */
        private int maxAttributeValueLength = 1000;

        /**
         * Максимальное количество событий на span.
         * <p>
         * Aligned with platform tracing defaults. Span-limit drift is monitored by
         * {@code DualChannelDriftDiagnostics}.
         */
        private int maxEvents = 10;

        /** Максимальное время жизни span'а до принудительного завершения watchdog'ом. */
        private Duration spanTimeout = Duration.ofSeconds(30);

        /** Максимальная длительность trace'а. */
        private Duration traceTimeout = Duration.ofSeconds(60);
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Queue {
        /**
         * Размер очереди ожидающих экспорта span'ов.
         * <p>
         * Kept aligned with the agent extension default supplier by tests where applicable
         * ({@code SharedDefaultsAlignmentTest}).
         */
        private int maxSize = 2048;

        /**
         * Желаемая (UX-facing) политика поведения при переполнении очереди.
         * <p>
         * <b>В v0.1.0 и при default-конфигурации v1.x</b> это <i>aspirational</i> значение —
         * фактическое runtime-поведение определяется стандартным {@code BatchSpanProcessor}
         * OTel SDK 1.62.0, у которого политика drop-new (подтверждено
         * {@code BatchSpanProcessorOverflowPolicyProbeTest}, см.
         * {@code docs/decisions/ADR-bsp-overflow-policy-finding.md}).
         * <p>
         * <b>Гарантированный {@code DROP_OLDEST} в v1.x активируется явно</b> через свойство
         * {@code platform.tracing.queue.overflow-policy=DROP_OLDEST} (env-канал —
         * {@code PLATFORM_TRACING_QUEUE_OVERFLOW_POLICY=DROP_OLDEST}) на стороне OTel Java
         * Agent extension. Это behavior-changing opt-in: меняет overflow-семантику под
         * нагрузкой и не активируется по умолчанию. Полный дизайн —
         * {@code docs/decisions/ADR-drop-oldest-export-processor-v1.md}.
         * <p>
         * <b>DROP_OLDEST ≠ error-priority:</b> под перегрузкой старый error span может быть
         * вытеснен в пользу более нового успешного. Сохранение error'ов — отдельная задача
         * (Collector tail-sampling / priority queue, backlog).
         * <p>
         * {@code DROP_NEWEST} остаётся как enum-значение для совместимости с YAML
         * (legacy/desired UX), но <b>не имеет</b> отдельной runtime-реализации на стороне
         * Agent extension в v1.x: при default-конфигурации совпадает с фактическим
         * поведением stock BSP.
         */
        private OverflowPolicy policy = OverflowPolicy.DROP_OLDEST;

        /**
         * Размер пакета, отправляемого экспортером за один цикл.
         * <p>
         * Kept aligned with the agent extension default supplier by tests where applicable
         * ({@code SharedDefaultsAlignmentTest}).
         */
        private int exportBatchSize = 512;

        /**
         * Таймаут операции экспорта пакета span'ов.
         * <p>
         * Kept aligned with the agent extension default supplier by tests where applicable
         * ({@code SharedDefaultsAlignmentTest}).
         * Значение выровнено на 5000 ms в v0.1.0 для совпадения с дефолтом OTel agent extension
         * и устранения dual-channel расхождения; см. CHANGELOG.md и docs/MIGRATION.md.
         */
        private Duration exportTimeout = Duration.ofMillis(5_000);

        public enum OverflowPolicy {
            /** Вытеснять самые старые span'ы. */
            DROP_OLDEST,
            /** Отбрасывать новые span'ы. */
            DROP_NEWEST
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Scrubbing {
        /**
         * Scrubbing runtime config schema v1 (PR-7C) — runtime-mutable поля, публикуемые одним
         * атомарным {@code updateScrubbingPolicy} через {@link RuntimeConfigApplier}:
         * {@code enabled}, {@code builtInRules}. Agent-side {@code ScrubbingPolicyHolder} — source of truth;
         * Spring — input/reconciliation layer only.
         * <p>
         * Включает обработчик маскирования чувствительных данных.
         */
        private boolean enabled = true;

        /** 
         * Список встроенных правил scrubbing (runtime-mutable schema v1, PR-7C).
         * Property: {@code platform.tracing.scrubbing.built-in-rules}.
         * <p>
         * Публикуется в agent одним {@code updateScrubbingPolicy(enabled, ruleNames[], source)}.
         * Unknown names передаются as-is; agent пропускает неизвестные (PR-7B parity).
         * SPI-правила здесь не отображаются.
         */
        private List<String> builtInRules = new ArrayList<>(List.of(
                "password", "jwt", "email", "oauth-header", "x-auth-header",
                "infra-credential", "webhook-token", "ssh-credential",
                "user-identity", "hardware-identity", "location", "ip-address"
        ));

        /**
         * Путь к Properties-файлу с дополнительными встроенными правилами маскирования.
         * Поддерживаемые префиксы: {@code classpath:...}, {@code file:...} либо абсолютный/
         * относительный путь без префикса. Содержимое файла читает {@code ScrubbingRulesLoader}
         * на стороне OTel SDK-расширения; формат — Java Properties с ключом
         * {@code additional-built-in-rules=name1,name2,...}.
         * <p>
         * Пустое значение или отсутствие свойства означает «не загружать внешний файл» —
         * используется только {@link #builtInRules} и {@code ServiceLoader}-расширения.
         * YAML в v1 не поддерживается из-за classloader isolation OTel Java Agent.
         */
        private String rulesConfig;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Exporter {
        /**
         * Гранулярный toggle экспорта (kill-switch). Runtime-mutable (Фаза 14): при {@code false}
         * span'ы создаются и пропагируются, но не экспортируются (export-gate, PR-4) — propagation
         * не ломается. Не меняет SpanRelationship (endpoint/queue остаются startup-only).
         */
        private boolean enabled = true;

        private final Otlp otlp = new Otlp();

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Otlp {
            /** Адрес OpenTelemetry Collector'а. */
            private String endpoint = "http://otel-collector:4317";

            private final Retry retry = new Retry();

            @Getter
            @Setter
            @Accessors(chain = true)
            public static class Retry {
                private boolean enabled = true;
                private Duration initialBackoff = Duration.ofSeconds(1);
                private Duration maxBackoff = Duration.ofSeconds(30);
            }
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Response {
        /** Включает добавление заголовка {@code X-Request-Id} (и/или {@code X-Trace-Id}) в HTTP-ответы. */
        private boolean exposeRequestIdHeader = true;

        /** Имя заголовка, используемого для возврата идентификатора trace'а клиенту. */
        private String headerName = PlatformHeaders.X_REQUEST_ID;
    }

    /**
     * Конфигурация платформенных провайдеров имени сервиса.
     * <p>
     * Бины провайдеров ({@code PlatformLocalServiceNameProvider},
     * {@code PlatformRemoteServiceNameProvider}) поднимаются всегда — независимо от
     * {@code platform.tracing.enabled}, чтобы потребитель (например, errorhandling-стартер)
     * получал корректное значение и при отключённой трассировке. Параметры ниже управляют
     * только поведением провайдеров и записи на span'ы клиентских вызовов.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class ServiceNames {
        /**
         * Включает запись имени upstream-сервиса {@code platform.remote.service} на ERROR'ные
         * клиентские span'ы и в MDC. Отключение оставляет провайдеры рабочими, но
         * {@code remoteServiceNameProvider} будет всегда возвращать {@link java.util.Optional#empty()}.
         */
        private boolean recordRemoteOnClientError = true;

        /**
         * Приоритет атрибутов CLIENT-span'а, по которым извлекается имя upstream-сервиса.
         * Применяется в указанном порядке: первый непустой атрибут после нормализации становится
         * значением {@code platform.remote.service}.
         * <p>
         * По умолчанию используется OpenTelemetry-конвенция:
         * {@code peer.service} → {@code rpc.service} → {@code server.address}.
         */
        private List<String> remoteAttributePriority = new ArrayList<>(List.of(
                "peer.service", "rpc.service", "server.address"
        ));

        /**
         * Если {@code true}, значение {@code server.address} в виде голого IP-адреса
         * (IPv4 или IPv6) не считается корректным именем сервиса и игнорируется.
         * Это защищает поле {@code domain} в DTO ошибок от мусорных значений.
         */
        private boolean ignoreServerAddressIfIp = true;
    }

    /**
     * Конфигурация поведения аспекта {@code @Traced}.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Aop {
        /**
         * Режим обработки {@code @Traced}-методов:
         * <ul>
         *   <li>{@link Mode#ENRICH_CURRENT} — обогащает текущий span (если активен) атрибутами
         *       без создания дочернего span'а; если активного контекста нет — создаёт дочерний span;</li>
         *   <li>{@link Mode#CHILD_SPAN} — всегда создаёт дочерний span (поведение, совместимое
         *       с ранними прототипами стартера).</li>
         * </ul>
         * По умолчанию {@code ENRICH_CURRENT} — это дешевле по числу span'ов и не порождает
         * дублирующие HTTP-спаны при работе вместе с OpenTelemetry Java Agent.
         */
        private Mode mode = Mode.ENRICH_CURRENT;

        public enum Mode {
            /** Обогащает уже активный span (или создаёт дочерний, если активного нет). */
            ENRICH_CURRENT,
            /** Всегда создаёт отдельный дочерний span. */
            CHILD_SPAN
        }
    }

    /**
     * Подавление дублирующих компонентов трассировки при работе вместе с OpenTelemetry Java Agent.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Suppression {
        /**
         * Подавляет HTTP-обсервации {@code Micrometer Observation} (server и client) для текущего
         * веб-стека через {@code ObservationPredicate} с {@code instanceof}-проверкой класса
         * контекста. Включается явно при работе с OpenTelemetry Java Agent, который самостоятельно
         * создаёт HTTP-span'ы на уровне Tomcat / Netty / HTTP-клиентов; без подавления возникает
         * дублирование span'ов в backend'е.
         * <p>
         * <b>Trade-off (важно):</b> {@code ObservationPredicate} → {@code false} превращает
         * обсервацию в {@code Observation.NOOP}, поэтому не вызываются ни tracing-, ни
         * metrics-{@code ObservationHandler}'ы. Метрики {@code http.server.requests} /
         * {@code http.client.requests} тоже исчезают — HTTP-метрики при включённом подавлении
         * должны поступать из OpenTelemetry Java Agent (OTel metrics pipeline).
         * Если требуются метрики без span'ов и без Agent'а — реализуйте собственный
         * {@code ObservationConvention} + selective handler, а не глобальный predicate.
         * <p>
         * Реализация подавления выполнена в стек-специфичных авто-конфигурациях
         * {@code WebMvcSuppressMicrometerTracingAutoConfiguration} и
         * {@code WebFluxSuppressMicrometerTracingAutoConfiguration} (модули
         * {@code platform-tracing-autoconfigure-webmvc} и
         * {@code platform-tracing-autoconfigure-webflux} соответственно).
         */
        private boolean suppressMicrometerTracing = false;
    }

    /**
     * Конфигурация {@code EnrichingSpanProcessor} — обогащение span'ов платформенными атрибутами
     * ({@code platform.trace.type}, {@code platform.trace.result}, {@code platform.remote.service}).
     * <p>
     * Дублирует свойства расширения OTel Java Agent ({@code platform.tracing.enriching.*}) на
     * уровне Spring Boot для удобства диагностики через {@code /actuator/configprops}. Реальное
     * применение остаётся за SDK-extension; здесь хранится «зеркало» значений для actuator-снимка.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Enriching {
        /** Включает обогащение атрибутов на стороне SDK-extension. */
        private boolean enabled = true;

        /**
         * Приоритет атрибутов, по которым определяется имя upstream-сервиса при ошибочных
         * клиентских span'ах. Совпадает с {@code TracingProperties.ServiceNames#remoteAttributePriority}
         * — последний выигрывает на уровне Java-фасада, этот список — для конфигурации SDK-extension.
         */
        private List<String> remoteServicePriority = new ArrayList<>(List.of(
                "peer.service", "rpc.service", "server.address"
        ));
    }

    /**
     * Конфигурация {@code ValidatingSpanProcessor} — проверка корректности платформенной разметки
     * span'ов перед экспортом в Collector.
     * <p>
     * Runtime-mutable schema v1 (PR-8C): {@code enabled}, {@code strict} публикуются одним
     * атомарным {@code updateValidationPolicy} через {@code RuntimeConfigApplier}.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Validation {
        /** Включает валидацию платформенной разметки на стороне SDK-extension. Runtime-mutable (Фаза 14). */
        private boolean enabled = true;

        /**
         * Строгий режим валидации ({@code ValidatingSpanProcessor}). Runtime-mutable (Фаза 14):
         * {@code true} — нарушения трактуются строго; {@code false} — warn-режим. Wiring — PR-3.
         * <p>
         * Runtime enablement of {@code strict=true} is rejected by default unless
         * {@link #strictRuntimeAllowed} is {@code true} (agent-side guard, PR-9F).
         */
        private boolean strict = false;

        /**
         * Startup-only guard (PR-9F): when {@code false} (default), agent rejects runtime updates
         * that set {@code strict=true}. Intended for CI/test/pre-prod diagnostics only.
         * Not runtime-mutable via {@link RuntimeConfigApplier}.
         */
        private boolean strictRuntimeAllowed = false;
    }

    /**
     * Конфигурация платформенного semantic-слоя (Фаза 13): типизированные builder'ы и
     * runtime-валидация semconv-контракта ({@code AttributePolicy}).
     * <p>
     * Режимы (см. {@code docs/decisions/ADR-semconv-validation-modes.md}):
     * <ul>
     *   <li>{@code WARN} — production default: span создаётся, safe-defaults + лог + метрика;</li>
     *   <li>{@code STRICT} — fail-fast, предназначен только для CI/test; при включении в
     *       production runtime на старте логируется WARN (unsupported runtime mode);</li>
     *   <li>{@code DISABLED} — явный аварийный opt-out (не migration mode): атрибуты as-is,
     *       обязательны {@link #disabledReason} и one-time startup WARN + gauge. PII-scrubbing
     *       при этом НЕ отключается.</li>
     * </ul>
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Semantic {

        /** Режим runtime-валидации semconv-контракта. Production default — {@code WARN}. */
        private space.br1440.platform.tracing.api.semconv.SemconvValidationMode validationMode =
                space.br1440.platform.tracing.api.semconv.SemconvValidationMode.WARN;

        /**
         * Причина перевода в {@code DISABLED} (owner/причина/expiry). Для режима
         * {@code DISABLED} крайне желателен непустой текст — он попадает в one-time startup WARN
         * и облегчает аудит «кто и зачем отключил валидацию».
         */
        private String disabledReason;

        /**
         * Разрешает escape-hatch {@code unsafeAttribute(key, value)} (атрибут вне allowlist,
         * прогоняемый через policy с пометкой). По умолчанию запрещён.
         */
        private boolean allowUnsafeAttributes = false;

        private final Exception exception = new Exception();

        /**
         * Политика публикации текста исключения через {@code ExceptionRecorder}.
         * <p>
         * Секьюр-дефолт: ни {@code exception.message}, ни {@code exception.stacktrace} не
         * публикуются (events не скрабятся {@code ScrubbingSpanProcessor}, поэтому raw message /
         * stacktrace могли бы утечь PII/SQL/токены мимо скрабинга). Включать осознанно.
         */
        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Exception {
            /** Публиковать ли (усечённый) {@code exception.message} и status description. */
            private boolean includeMessage = false;

            /** Публиковать ли {@code exception.stacktrace} в span (verbose — обычно через logs). */
            private boolean includeStacktrace = false;
        }
    }

    /**
     * Конфигурация {@code SpanWatchdogProcessor} — фонового сторожевого процесса, который
     * принудительно завершает «зависшие» span'ы и trace'ы по таймаутам {@code limits.spanTimeout}
     * и {@code limits.traceTimeout}.
     * <p>
     * Здесь параметрируется только сам процесс сканирования; пороговые значения остаются в
     * {@link Limits} для обратной совместимости с существующими конфигурациями сервисов.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Watchdog {
        /** Включает фоновый сторожевой процессор span'ов. */
        private boolean enabled = true;

        /**
         * Период фонового сканирования открытых span'ов на предмет превышения таймаутов.
         * Меньшие значения ускоряют реакцию на «зависшие» span'ы, но повышают фоновую CPU-нагрузку.
         */
        private Duration scanInterval = Duration.ofSeconds(5);
    }

    /**
     * Конфигурация распространения платформенных заголовков.
     * <p>
     * Свойства управляют поведением приложения (входящие MDC, валидация) и служат
     * "actuator hints" для OTel Agent Extension. Фактическая propagator chain
     * определяется агентом через `otel.propagators`.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Propagation {
        /**
         * Гранулярный toggle платформенной пропагации управляющих заголовков. Runtime-mutable (Фаза 14).
         * Не отключает W3C/baggage Агента — только платформенный слой. Wiring — PR-4.
         */
        private boolean enabled = true;

        private final PlatformHeadersConfig platformHeaders = new PlatformHeadersConfig();
        private final Outbound outbound = new Outbound();
        private final Mdc mdc = new Mdc();
        private final Baggage baggage = new Baggage();

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class PlatformHeadersConfig {
            private String forceTraceHeader = PlatformHeaders.X_TRACE_ON;
            private String qaTraceHeader = PlatformHeaders.X_QA_TRACE;
            private String requestIdHeader = PlatformHeaders.X_REQUEST_ID;
        }

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Outbound {
            /**
             * Главный переключатель исходящей передачи платформенных заголовков (secure-by-default off).
             * Имя {@code enabled} однозначно: при {@code false} платформенные заголовки не уходят никуда,
             * даже на доверенные хосты.
             */
            private boolean enabled = false;
            /** Glob-паттерны доверенных хостов (label-aware: {@code *} — один label, {@code **} — много). */
            private List<String> trustedHostPatterns = new ArrayList<>();
            /** Разрешить IP-литералы в trusted-list (по умолчанию запрещены — защита от SSRF-обхода). */
            private boolean allowIpLiterals = false;
            /**
             * Пробрасывать ли {@code X-Trace-On} наружу. По умолчанию {@code false}: решение о записи
             * уже переносит sampled-flag в {@code traceparent}. Включать только если downstream-sampler
             * не parent-based (escape hatch).
             */
            private boolean propagateForceTrace = false;
            /** Пробрасывать ли {@code X-QA-Trace} наружу (по умолчанию {@code false}). */
            private boolean propagateQaTrace = false;
            /** Пробрасывать ли {@code X-Request-Id} (correlation id) наружу (по умолчанию {@code true}). */
            private boolean propagateRequestId = true;
        }

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Mdc {
            /** Сохранять ли входящий X-Request-Id в MDC (logs). */
            private boolean putRequestId = true;
            private String requestIdKey = "correlation_id";
        }

        /**
         * Конфигурация Baggage (фильтрация исходящих PII).
         * Дублирует свойства OTel Agent Extension для actuator hints.
         */
        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Baggage {
            private boolean enabled = true;
            private List<String> allowedKeys = new ArrayList<>(List.of("traffic_source", "tenant_class", "correlation-id"));
            private List<String> denyPatterns = new ArrayList<>(List.of("password", "secret", "token"));
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Kafka {
        /**
         * Включает создание отдельного INTERNAL span'а с Links на каждое
         * сообщение в батче для @KafkaListener(batch="true").
         */
        private boolean batchLinksEnabled = false;

        /**
         * Режим интеграции с Kafka. Open-enum (String):
         * {@code agent-compatible} (default) — платформа добавляет только policy-driven inject
         * платформенных заголовков, span'ы создаёт OTel Java Agent; {@code disabled} — платформенные
         * Kafka-интерсепторы не регистрируются. Неизвестные значения трактуются как {@code agent-compatible}.
         */
        private String mode = "agent-compatible";

        /**
         * Включает исходящую инжекцию платформенных заголовков в producer-записи
         * (только в режиме {@code agent-compatible} и на доверенные топики).
         */
        private boolean propagatePlatformHeaders = false;

        /** Glob-паттерны доверенных топиков для исходящей инжекции платформенных заголовков. */
        private List<String> trustedTopicPatterns = new ArrayList<>();
    }

    /**
     * Конфигурация переноса контекста трассировки между потоками.
     * <p>
     * Платформенный starter не модифицирует executors прикладного приложения по умолчанию.
     * Включение возможно только явно, чтобы избежать конфликтов с кастомными
     * {@code ThreadPoolTaskExecutor} / {@code TaskDecorator}, security/MDC/tenant propagation
     * и OpenTelemetry Java Agent. См. ADR «@Async context propagation opt-in».
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class ContextPropagation {
        private final Async async = new Async();
    }

    /**
     * Подраздел для {@code @Async} / {@code ThreadPoolTaskExecutor}.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Async {
        /**
         * Включает регистрацию платформенного {@code TaskDecorator} через
         * {@code BeanPostProcessor} для всех {@code ThreadPoolTaskExecutor}-бинов контекста.
         * <p>
         * <b>Default {@code false}.</b> В этом режиме платформа не вмешивается в работу
         * прикладных executors. При значении {@code true} BPP композирует существующий
         * {@code TaskDecorator} (включая Spring Boot 3.5
         * {@code ContextPropagatingTaskDecorator}) с платформенным, оставляя платформенный
         * самым внешним слоем — capture снапшота происходит в caller-thread до запуска
         * existing decorator.
         * <p>
         * Property bootstrap-only: изменение требует перезапуска JVM, так как executor beans
         * создаются один раз при инициализации контекста.
         */
        private boolean enabled = false;

        /**
         * Режим переноса контекста.
         * <p>
         * Open enum: единственное валидное значение в v0.1.0 — {@code propagate-current-context}
         * (переносится только OTel Context + MDC через Micrometer ContextSnapshot, span
         * автоматически НЕ создаётся). Неизвестные значения логируются как WARN и
         * fallback'ятся на {@code propagate-current-context} — это обеспечивает
         * forward-compatibility для будущих режимов.
         */
        private String mode = "propagate-current-context";
    }

    /**
     * Конфигурация диагностических сигналов платформенной трассировки на этапе старта.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Diagnostics {
        /**
         * Включает startup-WARN при расхождении Spring {@code TracingProperties} и effective
         * OTel agent значений по whitelist shared properties (BSP queue, span limits).
         * <p>
         * См. {@code DualChannelDriftDiagnostics} и
         * {@code docs/decisions/ADR-dual-channel-properties-v0.1.md}.
         * Default {@code true}. WARN — diagnostic-only сигнал, НЕ misconfiguration.
         */
        private boolean dualChannelWarn = true;

        /**
         * Включает startup-WARN, когда Spring {@code platform.tracing.queue.policy=DROP_OLDEST}
         * <b>задан явно оператором</b> (а не унаследован как default), но платформенный
         * Agent extension не активирован в режиме {@code DROP_OLDEST} (то есть фактическое
         * runtime-поведение — stock {@code BatchSpanProcessor} = drop-new).
         * <p>
         * <b>WARN не срабатывает на default-конфигурации:</b> платформа поставляет
         * {@code DROP_OLDEST} как default Spring property, и если оператор его не переопределял,
         * это не противоречие — выводится только информационный actuator-фрагмент (см.
         * {@link #dropOldestAspirationInfo}).
         * <p>
         * См. {@code DropOldestAspirationDiagnostics} и
         * {@code docs/decisions/ADR-drop-oldest-export-processor-v1.md}. Default {@code true}.
         */
        private boolean dropOldestAspirationWarn = true;

        /**
         * Включает одноразовый INFO-лог на старте с описанием статуса
         * {@code DROP_OLDEST}-aspiration: совпадают ли Spring policy и Agent overflow-policy.
         * Имеет смысл для default-конфигурации (когда WARN сознательно подавлен), чтобы
         * операторам было понятно из чего складывается фактическое runtime-поведение.
         * <p>
         * Default {@code true}.
         */
        private boolean dropOldestAspirationInfo = true;

        /**
         * Желаемый уровень логирования платформенных логгеров ({@code space.br1440.platform.tracing.*}).
         * Runtime-mutable (Фаза 14). Пусто/{@code null} — не менять (использовать конфигурацию backend'а).
         * <p>
         * Для app-CL логгеров штатный канал — Spring Boot {@code /actuator/loggers}; это свойство —
         * UX-зеркало и источник для agent/bootstrap-CL логгеров (PR-5).
         */
        private String logLevel;
    }

    /**
     * Actuator endpoint settings for {@code /actuator/tracing} (PR-9J).
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Actuator {
        /**
         * Enables {@code POST /actuator/tracing/{property}/{value}} mutation operations.
         * Default {@code false} — production should leave mutation disabled; enable only for
         * local/dev/debug/test/pre-prod diagnostics.
         * <p>
         * Does not affect {@code GET /actuator/tracing} read model. Does not protect direct JMX
         * access to domain MBeans — see runtime policy architecture docs.
         */
        private boolean mutationEnabled = false;
    }
}
