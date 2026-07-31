package space.br1440.platform.tracing.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.semconv.SemconvValidationMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Платформенная конфигурация модуля трассировки.
 * <p>
 * Содержит только свойства, которые Spring реально применяет. Category A runtime policy
 * ({@code platform.tracing.policy.*}) доставляется через Config Server / Actuator control plane
 * и не зеркалируется здесь.
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

    private final RequestIdResponseHeader requestIdHeader = new RequestIdResponseHeader();
    private final Semantic semantic = new Semantic();
    private final Propagation propagation = new Propagation();
    private final Kafka kafka = new Kafka();

    /**
     * Политика записи {@code X-Request-Id} в HTTP-ответ (Servlet/WebFlux фильтры).
     * Property-namespace: {@code platform.tracing.request-id-header.*}.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class RequestIdResponseHeader {

        /**
         * Разрешает запись request-id заголовка (имя — {@link #name}) в HTTP-ответ.
         * По умолчанию {@code true}. При {@code false} фильтр остаётся активным:
         * {@code X-Trace-Id}, identity boundary и cleanup продолжают работать.
         */
        private boolean enabled = true;

        /** Имя заголовка request-id в HTTP-ответе. */
        private String name = PlatformHeaders.X_REQUEST_ID;
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Semantic {

        /**
         * Режим валидации платформенного semantic-контракта атрибутов span'а.
         * <ul>
         *     <li> <b>{@code WARN}</b> — production default: нарушения логируются, но не блокируют. </li>
         *     <li> <b>{@code STRICT}</b> — предназначен только для test/CI — бросает исключение на нарушении контракта. </li>
         *     <li> <b>{@code DISABLED}</b> — аварийный opt-out, полностью отключающий валидацию;
         *          это не режим миграции, и он не влияет на PII-скрабинг, который работает независимо. </li>
         * </ul>
         */
        private SemconvValidationMode validationMode = SemconvValidationMode.WARN;

        /**
         * Причина перевода {@link #validationMode} в {@code DISABLED}, попадает в
         * стартовый WARN-лог для аудита. Не имеет эффекта при других режимах.
         */
        private String disabledReason;

        /**
         * Escape hatch, разрешающий {@code AttributePolicy} пропускать нестандартные
         * (не входящие в платформенный semantic-контракт) атрибуты span'а вместо их
         * отбраковки. По умолчанию выключен — типового сценария использования нет.
         */
        private boolean allowUnsafeAttributes = false;

        /** Политика публикации текста исключения в span (см. {@link ExceptionEvent}). */
        private final ExceptionEvent exception = new ExceptionEvent();

        @Getter @Setter @Accessors(chain = true)
        public static class ExceptionEvent {

            /**
             * Публиковать {@code exception.message} в exception-событии span'а.
             * По-умолчанию выключено, так как сообщение исключения может содержать
             * PII или внутренние детали реализации; включение — осознанный выбор.
             */
            private boolean includeMessage = false;

            /**
             * Публиковать stacktrace в exception-событии span'а.
             * По-умолчанию выключено: stacktrace раскрывает внутреннюю структуру кода и
             * может быть избыточным по объёму; включение — осознанный выбор.
             */
            private boolean includeStacktrace = false;
        }
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Propagation {

        /**
         * Имена платформенных управляющих заголовков, которые читаются на входе на стороне Java Agent и записываются на выходе см. {@link Outbound}).
         * Единая точка настройки имён: изменение здесь одновременно меняет, какие заголовки распознаются как входящие и какие проставляются как исходящие.
         */
        private final PlatformHeaderNames platformHeaders = new PlatformHeaderNames();

        /**
         * Политика исходящей передачи платформенных заголовков на HTTP-клиентах приложения.
         * Не затрагивает Kafka — там отдельная политика на {@code platform.tracing.kafka.propagate-platform-headers}.
         */
        private final Outbound outbound = new Outbound();

        @Getter @Setter @Accessors(chain = true)
        public static class PlatformHeaderNames {
            private String forceTraceHeader = PlatformHeaders.X_TRACE_ON;
            private String qaTraceHeader = PlatformHeaders.X_QA_TRACE;
            private String requestIdHeader = PlatformHeaders.X_REQUEST_ID;
        }

        @Getter @Setter @Accessors(chain = true)
        public static class Outbound {

            /**
             * Разрешает передачу платформенных управляющих заголовков
             * ({@code X-Trace-On} / {@code X-QA-Trace} / {@code X-Request-Id}) в исходящих
             * HTTP-запросах приложения (RestClient/RestTemplate в WebMvc, WebClient в WebFlux).
             * <p>
             * Действует только на HTTP: на Kafka-producer не влияет — там отдельное свойство
             * {@code platform.tracing.kafka.propagate-platform-headers}.
             * <p>
             * По умолчанию {@code false} — заголовки не передаются никуда, даже на хосты из
             * {@link #trustedHostPatterns}. Список доверенных хостов сам по себе передачу не
             * включает, он только ограничивает её, когда {@code enabled=true}.
             */
            private boolean enabled = false;

            /**
             * Паттерны доверенных хостов.
             * {@code *} — один label,
             * {@code **} — несколько.
             * Пустой список (default) закрывает inject для всех хостов.
             */
            private List<String> trustedHostPatterns = new ArrayList<>();

            /** Разрешить IP-литералы в качестве доверенного destination. */
            private boolean allowIpLiterals = false;

            /**
             * Пробрасывать {@code X-Trace-On} на доверенный destination — escape hatch для
             * non-parent-based downstream.
             */
            private boolean propagateForceTrace = false;

            /** Пробрасывать {@code X-QA-Trace} на доверенный destination. */
            private boolean propagateQaTrace = false;

            /** Пробрасывать {@code X-Request-Id} на доверенный destination. */
            private boolean propagateRequestId = true;
        }
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Kafka {

        /**
         * Паттерны доверенных Kafka-топиков для исходящей инжекции
         * платформенных заголовков ({@code platform.tracing.kafka.propagate-platform-headers}).
         * {@code *} — один label,
         * {@code **} — несколько.
         * Пустой список (default) закрывает inject для всех топиков (secure-by-default DENY).
         */
        private List<String> trustedTopicPatterns = new ArrayList<>();
    }
}
