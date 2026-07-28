package space.br1440.platform.tracing.autoconfigure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.autoconfigure.configuration.TracingConfigurationAuthority;
import space.br1440.platform.tracing.autoconfigure.diagnostics.OtelAgentLogLevel;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Платформенная конфигурация модуля трассировки.
 */
@Getter
@Setter
@Accessors(chain = true)
@ConfigurationProperties(prefix = TracingProperties.PREFIX)
public class TracingProperties {

    public static final String PREFIX = "platform.tracing";

    /**
     * Режим работы платформенной трассировки. {@link SdkMode#AGENT} требует готовый
     * Controlled Platform Agent, {@link SdkMode#DISABLED} включает намеренный NoOp-режим.
     */
    private SdkMode mode = SdkMode.AGENT;

    /**
     * Уровень логирования платформенного расширения OpenTelemetry Java Agent.
     */
    private OtelAgentLogLevel logLevel = OtelAgentLogLevel.INFO;

    private final Sampling sampling = new Sampling();
    private final Scrubbing scrubbing = new Scrubbing();
    private final Exporter exporter = new Exporter();
    private final Response response = new Response();
    private final Suppression suppression = new Suppression();
    private final Validation validation = new Validation();
    private final Semantic semantic = new Semantic();
    private final Propagation propagation = new Propagation();
    private final Kafka kafka = new Kafka();
    private final ThreadPoolTaskExecutorPropagation threadPoolTaskExecutor = new ThreadPoolTaskExecutorPropagation();
    private final Actuator actuator = new Actuator();
    private final Configuration configuration = new Configuration();

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Sampling {
        /** Runtime-переключатель (kill-switch) для сэмплирования, изменяемый на лету (JMX reconcile). */
        private boolean enabled = true;

        /** Базовая вероятность сэмплирования trace'ов в диапазоне [0.0, 1.0]. */
        private double ratio = 0.1;

        /** Вероятности сэмплирования по отдельным маршрутам (route). */
        private Map<String, Double> routeRatios = new LinkedHashMap<>();

        /** Значения force-record заголовка ({@code propagation.platformHeaders.forceTraceHeader}). */
        private List<String> forceRecordHeaderValues = new ArrayList<>(List.of("on"));

        /**
         * Path-префиксы для head-sampling DROP. Force/QA-заголовки имеют приоритет.
         */
        private List<String> dropPaths = new ArrayList<>(List.of(
                "/actuator/health", "/actuator/prometheus", "/actuator/info"
        ));
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Scrubbing {
        /** Runtime-переключатель (kill-switch) для скраббинга, изменяемый на лету (JMX reconcile). */
        private boolean enabled = true;

        /** Встроенные правила scrubbing (runtime-mutable, JMX reconcile). */
        private List<String> builtInRules = new ArrayList<>(List.of(
                "password", "jwt", "email", "oauth-header", "x-auth-header",
                "infra-credential", "webhook-token", "ssh-credential",
                "user-identity", "hardware-identity", "location", "ip-address"
        ));
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Exporter {
        /** Переключатель (kill-switch) экспорта, изменяемый на лету (JMX reconcile). */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Response {
        /** runtime opt-out только для X-Request-Id; фильтр всегда активен. */
        private boolean exposeRequestIdHeader = true;

        private String headerName = PlatformHeaders.X_REQUEST_ID;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Suppression {

        /**
         * При {@code true} подавляет Micrometer Observation для HTTP server/client (Servlet и
         * WebFlux): {@code ObservationPredicate} переводит соответствующие observation в
         * {@code Observation.NOOP} — ни span, ни метрика ({@code http.server.requests} /
         * {@code http.client.requests}) не создаются.
         * <p>
         * <b>Значение по умолчанию — {@code null} (не задано), а не {@code false}.</b> Если
         * оператор не задал свойство явно, эффективное значение автоматически выводится из
         * присутствия OpenTelemetry Java Agent в JVM ({@code OtelAgentDetector}) через
         * {@code MicrometerTracingSuppressionResolver}: Agent есть → suppression включён (иначе
         * дублирование HTTP-span'ов); Agent'а нет → suppression выключен (иначе дыра в HTTP
         * телеметрии). Явное значение всегда имеет приоритет над auto-derive — это escape hatch
         * для нетиповых сценариев (тесты, гибридные конфигурации), а не типовая настройка,
         * которую должен подбирать разработчик сервиса.
         */
        private Boolean suppressMicrometerTracing;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Validation {
        /** Runtime-mutable (JMX reconcile). */
        private boolean enabled = true;

        /** Строгий режим валидации (runtime-mutable, JMX reconcile). */
        private boolean strict = false;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Semantic {

        private SemconvValidationMode validationMode = SemconvValidationMode.WARN;

        private String disabledReason;
        private boolean allowUnsafeAttributes = false;
        private final Exception exception = new Exception();

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Exception {
            private boolean includeMessage = false;
            private boolean includeStacktrace = false;
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Propagation {
        /** Runtime-mutable toggle платформенной пропагации (JMX reconcile). */
        private boolean enabled = true;

        private final PlatformHeadersConfig platformHeaders = new PlatformHeadersConfig();
        private final Outbound outbound = new Outbound();

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
            private boolean enabled = false;
            private List<String> trustedHostPatterns = new ArrayList<>();
            private boolean allowIpLiterals = false;
            private boolean propagateForceTrace = false;
            private boolean propagateQaTrace = false;
            private boolean propagateRequestId = true;
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Kafka {
        private boolean batchLinksEnabled = false;
        private String mode = "agent-compatible";
        private boolean propagatePlatformHeaders = false;
        private List<String> trustedTopicPatterns = new ArrayList<>();
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class ThreadPoolTaskExecutorPropagation {
        /**
         * При {@code true} переносит текущий OTel Context и MDC в задачи Spring
         * {@code ThreadPoolTaskExecutor}, включая вызовы {@code @Async}. Перенос реализуется
         * через {@code ThreadPoolTaskExecutorContextPropagationBeanPostProcessor}.
         * <p>
         * Не создаёт span, не применяется к Reactor, произвольным {@code Executor} и
         * {@code CompletableFuture} без этого executor. По умолчанию выключен: перед включением
         * следует исключить уже действующую executor-propagation от Java Agent, Spring Boot или
         * пользовательского {@code TaskDecorator}. См. {@code ADR-async-task-decorator-opt-in}.
         */
        private boolean enabled = false;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Actuator {
        /** Enables {@code POST /actuator/tracing/{property}/{value}} mutation operations. */
        private boolean mutationEnabled = false;

        /** Явно разрешает типизированную поверхность управления Category A через Actuator. */
        private boolean controlEnabled = false;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Configuration {
        /** Единственный источник управления конфигурацией трассировки для текущего runtime. */
        private TracingConfigurationAuthority authority = TracingConfigurationAuthority.HELM;
    }

}
