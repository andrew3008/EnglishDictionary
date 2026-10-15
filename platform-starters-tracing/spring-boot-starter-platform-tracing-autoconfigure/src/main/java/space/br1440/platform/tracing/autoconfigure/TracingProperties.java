package space.br1440.platform.tracing.autoconfigure;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
@ConfigurationProperties(prefix = TracingProperties.PREFIX)
public class TracingProperties {

    public static final String PREFIX = "platform.tracing";

    private final RequestIdResponseHeader requestIdHeader = new RequestIdResponseHeader();
    private final SpanAttributes spanAttributes = new SpanAttributes();
    private final ExceptionRecording exceptionRecording = new ExceptionRecording();
    private final Propagation propagation = new Propagation();
    private final Kafka kafka = new Kafka();

    /**
     * Политика записи request-id заголовка в HTTP-ответ (Servlet/WebFlux фильтры).
     * Property-namespace: {@code platform.tracing.request-id-header.*}.
     * Имя заголовка фиксировано: {@link PlatformHeaders#X_REQUEST_ID}.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class RequestIdResponseHeader {

        /**
         * Разрешает запись request-id заголовка в HTTP-ответ. По умолчанию {@code true}.
         * При {@code false} фильтр остаётся активным: {@code X-Trace-Id}, identity boundary
         * и cleanup продолжают работать.
         */
        private boolean enabled = true;
    }

    /**
     * Политика проверки атрибутов span'а, создаваемых платформенными builders.
     * Property-namespace: {@code platform.tracing.span-attributes.*}.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class SpanAttributes {

        private final Validation validation = new Validation();

        @Getter @Setter @Accessors(chain = true)
        public static class Validation {

            /**
             * Уровень проверки атрибутов span'а.
             * Значения — взаимоисключающие режимы окружений, а не градация строгости:
             * <ul>
             *     <li> <b>{@code WARNING}</b> — production default: нарушения логируются, но не блокируют. </li>
             *     <li> <b>{@code ERROR}</b> — только test/CI: бросает исключение до {@code startSpan()}.
             *          Это прикладной fail-fast builders, не агентская диагностика onEnding. </li>
             *     <li> <b>{@code DISABLED}</b> — аварийный opt-out, полностью отключающий валидацию;
             *          это не режим миграции, и он не влияет на PII-скрабинг. </li>
             * </ul>
             */
            private SpanAttributeValidationLevel level = SpanAttributeValidationLevel.WARNING;

            /**
             * Причина перевода {@link #level} в {@code DISABLED}, попадает в
             * стартовый WARN-лог для аудита. Не имеет эффекта при других режимах.
             */
            private String disabledReason;
        }
    }

    /**
     * Политика публикации текста исключения в span.
     * Property-namespace: {@code platform.tracing.exception-recording.*}.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class ExceptionRecording {

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

    @Getter @Setter @Accessors(chain = true)
    public static class Propagation {

        private final Outbound outbound = new Outbound();

        /**
         * Trust boundary для inbound {@code X-Correlation-Id}.
         * Property-namespace: {@code platform.tracing.propagation.correlation.*}.
         */
        private final Correlation correlation = new Correlation();

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

            /**
             * Пробрасывать canonical {@code X-Correlation-Id} ({@link PlatformHeaders#X_CORRELATION_ID})
             * на доверенный destination. Имя заголовка не конфигурируется — только этот
             * поведенческий переключатель; fail-closed по умолчанию.
             */
            private boolean propagateCorrelationId = false;
        }

        @Getter @Setter @Accessors(chain = true)
        public static class Correlation {

            /**
             * Принимать caller-supplied {@code X-Correlation-Id} на inbound-границе (Servlet/WebFlux).
             * По умолчанию {@code false} (fail-closed): canonical-режим не генерирует значение сам,
             * а без явного включения даже валидный inbound-заголовок игнорируется.
             * {@code accept-inbound=true} допустим только за доверенным ingress, снимающим клиентский заголовок.
             */
            private boolean acceptInbound = false;
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
