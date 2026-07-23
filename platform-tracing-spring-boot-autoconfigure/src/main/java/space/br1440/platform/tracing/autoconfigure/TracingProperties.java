package space.br1440.platform.tracing.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Платформенная конфигурация модуля трассировки.
 * <p>
 * Spring владеет только тем, что Spring применяет или реконсилит в агент по JMX.
 * SDK limits/queue/exporter/resource/enriching/watchdog и прочие agent-only параметры
 * конфигурируются в agent-канале ({@code OTEL_*}, {@code PLATFORM_TRACING_*},
 * {@code PlatformTracingDefaultsProvider}). См. ADR-spring-owns-only-what-spring-applies.
 */
@Getter
@Setter
@Accessors(chain = true)
@ConfigurationProperties(prefix = TracingProperties.PREFIX)
public class TracingProperties {

    public static final String PREFIX = "platform.tracing";

    /**
     * Глобальный переключатель. Значение {@code true} требует {@code sdk.mode=AGENT},
     * значение {@code false} требует {@code sdk.mode=DISABLED}.
     */
    private boolean enabled = true;

    private final Sdk sdk = new Sdk();
    private final Service service = new Service();
    private final Sampling sampling = new Sampling();
    private final Scrubbing scrubbing = new Scrubbing();
    private final Exporter exporter = new Exporter();
    private final Response response = new Response();
    private final Aop aop = new Aop();
    private final Suppression suppression = new Suppression();
    private final Validation validation = new Validation();
    private final Semantic semantic = new Semantic();
    private final Propagation propagation = new Propagation();
    private final Kafka kafka = new Kafka();
    private final ContextPropagation contextPropagation = new ContextPropagation();
    private final Diagnostics diagnostics = new Diagnostics();
    private final Actuator actuator = new Actuator();

    /**
     * Production-режим владения OpenTelemetry SDK.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Sdk {
        private space.br1440.platform.tracing.autoconfigure.support.SdkMode mode =
                space.br1440.platform.tracing.autoconfigure.support.SdkMode.AGENT;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Service {
        /** Логическое имя сервиса (для {@code PlatformLocalServiceNameProvider}). */
        private String name;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Sampling {
        /** Runtime-mutable kill-switch сэмплирования (JMX reconcile). */
        private boolean enabled = true;

        /** Базовая вероятность сэмплирования trace'ов в диапазоне [0.0, 1.0]. */
        private double ratio = 0.1;

        /** Per-route вероятности сэмплирования. */
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
        /** Runtime-mutable kill-switch scrubbing (JMX reconcile). */
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
        /** Export kill-switch (JMX reconcile). */
        private boolean enabled = true;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Response {
        private boolean exposeRequestIdHeader = true;
        private String headerName = PlatformHeaders.X_REQUEST_ID;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Aop {
        private Mode mode = Mode.ENRICH_CURRENT;

        public enum Mode {
            ENRICH_CURRENT,
            CHILD_SPAN
        }
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Suppression {
        private boolean suppressMicrometerTracing = false;
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

        private space.br1440.platform.tracing.api.semconv.SemconvValidationMode validationMode =
                space.br1440.platform.tracing.api.semconv.SemconvValidationMode.WARN;

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
    public static class ContextPropagation {
        private final Async async = new Async();
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Async {
        private boolean enabled = false;
        private String mode = "propagate-current-context";
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Diagnostics {
        /**
         * Желаемый уровень логирования платформенных логгеров (runtime-mutable, JMX reconcile).
         */
        private String logLevel;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Actuator {
        /** Enables {@code POST /actuator/tracing/{property}/{value}} mutation operations. */
        private boolean mutationEnabled = false;
    }
}
