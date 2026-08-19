package space.br1440.platform.errorhandling.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import space.br1440.platform.errorhandling.extension.LogPolicy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = ErrorHandlingProperties.PREFIX)
public class ErrorHandlingProperties {

    public static final String PREFIX = "platform.error-handling";

    private Boolean enabled = true;

    /**
     * <p>По умолчанию {@link LoggingStrategy#CATEGORY} — индустриальный стандарт
     * (Netflix, Spotify, Zalando): уровень логирования определяется категорией HTTP-статуса.
     * Явная установка значения в YAML требуется только при отклонении от умолчания.
     *
     * <table>
     *   <tr><th>Значение</th><th>Поведение</th></tr>
     *   <tr>
     *     <td>{@code CATEGORY} <em>(default)</em></td>
     *     <td>4xx → WARN, 5xx → ERROR с полным stack trace.
     *         Управление stack trace на других уровнях — через
     *         {@code stacktrace-log-levels}.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code ALL_DEBUG}</td>
     *     <td>DEBUG для всех ошибок — только имя класса и сообщение.
     *         Для load testing / высоко-нагруженных сред.</td>
     *   </tr>
     *   <tr>
     *     <td>{@code ALL_ERROR}</td>
     *     <td>ERROR с полным stack trace для всех ошибок.
     *         Для локальной разработки. <b>Не рекомендуется для production.</b></td>
     *   </tr>
     * </table> 
     */
    private LoggingStrategy loggingStrategy = LoggingStrategy.CATEGORY;

    /**
     * Включает добавление stack trace в тело ответа API (в metadata деталей ошибки).
     * <ul>
     *   <li>{@code false} (по умолчанию) — stack trace не включается (рекомендуется для production)</li>
     *   <li>{@code true} — stack trace добавляется в metadata (для разработки и отладки)</li>
     * </ul>
     */
    private Boolean includeStacktrace = false;

    /**
     * Включает добавление URI-пути запроса в тело ответа ошибки (поле {@code "path"} в metadata деталей).
     * Соответствует рекомендациям RFC 9457 Problem Details (поле {@code instance}).
     */
    private Boolean includeRequestPath = true;

    /**
     * Максимальная глубина обхода цепочки причин (cause chain) при классификации
     * исключений (например, {@code KafkaException → ExecutionException → TimeoutException}).
     *
     * <p>Значение {@code 1} проверяет только top-level исключение.
     * Default {@value #DEFAULT_MAX_CAUSE_DEPTH} покрывает типичные wrapping chains.
     * Допустимый диапазон: 1–50.
     *
     * @see space.br1440.platform.errorhandling.core.resolver.ResponseStatusResolverBean
     */
    public static final int DEFAULT_MAX_CAUSE_DEPTH = 10;

    @Min(value = 1, message = "maxCauseDepth must be >= 1")
    @Max(value = 50, message = "maxCauseDepth must be <= 50 (values > 50 are likely misconfiguration)")
    private int maxCauseDepth = DEFAULT_MAX_CAUSE_DEPTH;

    public int getMaxCauseDepth() {
        return maxCauseDepth;
    }

    public ErrorHandlingProperties setMaxCauseDepth(int maxCauseDepth) {
        if (maxCauseDepth < 1 || maxCauseDepth > 50) {
            throw new IllegalArgumentException("maxCauseDepth must be in range [1, 50], got: " + maxCauseDepth);
        }

        this.maxCauseDepth = maxCauseDepth;
        return this;
    }

    /**
     * Набор уровней {@link LogPolicy}, при которых stack trace включается в лог
     * в режиме {@link LoggingStrategy#CATEGORY}.
     *
     * <p>По умолчанию пустой: stack trace выводится только на уровне {@code ERROR}
     * (который всегда пишет stack trace безусловно).
     *
     * <p>Пример конфигурации для dev/staging — включить stack trace на уровнях
     * {@code WARN} и {@code DEBUG}:
     *
     * <pre>
     * platform:
     *   error-handling:
     *     stacktrace-log-levels:
     *       - WARN
     *       - DEBUG
     * </pre>
     *
     * <p>Допустимые значения: {@code DEBUG}, {@code INFO}, {@code WARN}.
     * {@code ERROR} не указывается — stack trace на этом уровне выводится всегда.
     */
    private Set<LogPolicy> stacktraceLogLevels = EnumSet.noneOf(LogPolicy.class);

    /**
     * Возвращает неизменяемое представление набора уровней логирования,
     * для которых выводится stack trace.
     */
    public Set<LogPolicy> getStacktraceLogLevels() {
        return Collections.unmodifiableSet(stacktraceLogLevels);
    }

    /**
     * Включает добавление {@code pgDetail} (PostgreSQL DETAIL message) в
     * {@code diagnosticMetadata} при обработке {@code PSQLException}.
     *
     * <p>При {@code true} (по умолчанию): {@code pgDetail} включается после
     * sanitization (значения данных заменяются на {@code (***)}).
     *
     * <p>При {@code false}: {@code pgDetail} полностью исключается.
     * Для compliance-sensitive сред, где даже sanitized detail нежелателен.
     *
     * <p>{@code pgDetail} никогда не включается в HTTP-ответ клиенту (CWE-209).
     *
     * <pre>
     * platform:
     *   error-handling:
     *     postgres-include-detail: false
     * </pre>
     */
    private Boolean postgresIncludeDetail = true;

    /**
     * Настройки обработки исключений ClickHouse Java client v2 / JDBC v2.
     * Гарантированно не {@code null} — null от YAML-binding'а заменяется на default.
     */
    private Clickhouse clickhouse = new Clickhouse();

    /**
     * Null-safe setter: Spring {@code @ConfigurationProperties} binding может передать
     * {@code null} при {@code platform.error-handling.clickhouse: null} в YAML.
     */
    public ErrorHandlingProperties setClickhouse(Clickhouse clickhouse) {
        this.clickhouse = (clickhouse != null) ? clickhouse : new Clickhouse();
        return this;
    }

    /**
     * Настройки {@code ClickHouseExceptionStrategy}. Все дефолты — secure-by-default.
     *
     * @see space.br1440.platform.errorhandling.core.strategy.cause.storage.ClickHouseExceptionStrategy
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Clickhouse {

        /**
         * Включать ли координаты сервера ClickHouse ({@code host:port}) в
         * {@code diagnosticMetadata} при обработке {@code ServerException}.
         *
         * <p>При {@code false} (по умолчанию, secure-by-default): {@code chServer}
         * не добавляется в metadata, что исключает раскрытие топологии кластера
         * (CWE-209). Рекомендуется в compliance-sensitive окружениях.
         *
         * <p>При {@code true}: в {@code diagnosticMetadata} добавляется поле
         * {@code chServer} в формате {@code host:port}. Полезно для multi-shard
         * диагностики, где знание конкретного шарда критично для расследования.
         * Username, password и database <b>не извлекаются</b> даже при включённом
         * флаге — попадает только {@code host:port}.
         *
         * <p>{@code chServer} никогда не попадает в HTTP-ответ клиенту —
         * только в {@code diagnosticMetadata} (SRE-only).
         *
         * <pre>
         * platform:
         *   error-handling:
         *     clickhouse:
         *       include-server-info: true
         * </pre>
         */
        private boolean includeServerInfo = false;
    }

    /**
     * Включает генерацию {@code stackHint} (1–3 значимых фрейма стектрейса)
     * в {@code diagnosticMetadata} стратегий обработки ошибок.
     *
     * <p>При {@code false} (по умолчанию): {@code stackHint} не генерируется.
     * Рекомендуется для production — исключает утечку внутренней топологии вызовов.
     *
     * <p>При {@code true}: {@code stackHint} добавляется в {@code diagnosticMetadata}
     * для упрощения диагностики (dev/staging).
     *
     * <pre>
     * platform:
     *   error-handling:
     *     include-stack-hint: true
     * </pre>
     */
    private Boolean includeStackHint = false;


    /**
     * Настройки обработки исключений брокеров сообщений.
     * Гарантированно не {@code null} — при попытке установить {@code null}
     * через setter или YAML-binding используется default-экземпляр.
     */
    private Messaging messaging = new Messaging();

    public ErrorHandlingProperties setMessaging(Messaging messaging) {
        this.messaging = (messaging != null) ? messaging : new Messaging();
        return this;
    }

    /**
     * Настройки обработки исключений messaging-стратегий, разделённые на три
     * независимые группы: {@link Kafka}, {@link Rabbit}, {@link SpringMessaging}.
     *
     * <p>Каждый брокер имеет собственный профиль безопасности:
     * Kafka и RabbitMQ могут потребовать различных настроек {@code includeBrokerCoordinates}
     * (например, Kafka-топики могут содержать tenant-сегменты, а RabbitMQ-очереди — нет,
     * или наоборот). Единый флаг для всех трёх стратегий не позволил бы дифференцировать
     * политики по брокерам без breaking change в YAML-конфигурации пользователей.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Messaging {

        private Kafka kafka = new Kafka();
        private Rabbit rabbit = new Rabbit();
        private SpringMessaging springMessaging = new SpringMessaging();

        public Messaging setKafka(Kafka kafka) {
            this.kafka = (kafka != null) ? kafka : new Kafka();
            return this;
        }

        public Messaging setRabbit(Rabbit rabbit) {
            this.rabbit = (rabbit != null) ? rabbit : new Rabbit();
            return this;
        }

        public Messaging setSpringMessaging(SpringMessaging springMessaging) {
            this.springMessaging = (springMessaging != null) ? springMessaging : new SpringMessaging();
            return this;
        }

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Kafka {

            /**
             * Включать ли координаты Kafka-брокера ({@code topic}, {@code partition},
             * {@code offset}, {@code consumerGroup}, {@code deliveryAttempt})
             * в {@code diagnosticMetadata} при обработке Kafka-исключений.
             *
             * <p>Default {@code true} — в отличие от {@code includeServerInfo=false}
             * у storage-стратегий: имя топика и партиция не раскрывают сетевую
             * топологию (host:port) и критичны для расследования инцидентов.
             * SRE без знания топика не может определить, какой listener или producer
             * упал. Координаты попадают исключительно в {@code diagnosticMetadata}
             * (SRE-only) — никогда в HTTP-ответ клиенту.
             *
             * <p>Установить в {@code false}, если имена топиков содержат
             * tenant-сегменты (например, {@code orders.tenant_42.created}) или
             * другие потенциально чувствительные данные.
             *
             * <pre>
             * platform:
             *   error-handling:
             *     messaging:
             *       kafka:
             *         include-broker-coordinates: false
             * </pre>
             *
             * @see space.br1440.platform.errorhandling.core.strategy.cause.messaging.KafkaBrokerExceptionStrategy
             */
            private boolean includeBrokerCoordinates = true;
        }

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class Rabbit {

            /**
             * Включать ли координаты RabbitMQ ({@code queue}, {@code routingKey},
             * {@code exchange}, {@code deliveryTag}, {@code redelivered})
             * в {@code diagnosticMetadata} при обработке AMQP-исключений.
             *
             * <p>Default {@code true} — по тем же причинам, что и в
             * {@link Kafka#isIncludeBrokerCoordinates()}: имена очередей и exchanges
             * нужны для диагностики и не раскрывают сетевую топологию.
             *
             * <p><b>Исключение из контракта:</b> для классификации
             * {@code REJECT_NO_REQUEUE} имя {@code queue} включается в
             * {@code diagnosticMetadata} <b>всегда</b> (даже при {@code false}) —
             * это критично для диагностики dead-letter сценариев. Поведение
             * подробно описано в Javadoc {@code RabbitMqExceptionStrategy}.
             *
             * <pre>
             * platform:
             *   error-handling:
             *     messaging:
             *       rabbit:
             *         include-broker-coordinates: false
             * </pre>
             *
             * @see space.br1440.platform.errorhandling.core.strategy.cause.messaging.RabbitMqExceptionStrategy
             */
            private boolean includeBrokerCoordinates = true;
        }

        @Getter
        @Setter
        @Accessors(chain = true)
        public static class SpringMessaging {

            /**
             * Включать ли whitelist системных заголовков Spring Messaging
             * ({@code id}, {@code timestamp}, {@code contentType}) и STOMP-специфичных
             * ({@code simpSessionId}, {@code simpDestination}, {@code stompCommand})
             * в {@code diagnosticMetadata}.
             *
             * <p>Default {@code true}: заголовки whitelist'а семантически
             * безопасны (UUID, timestamp, MIME-тип, sanitized destination через
             * {@code PathSanitizer}). Application-defined заголовки (correlation tokens,
             * tenant IDs, JWT claims) <b>никогда</b> не попадают в metadata —
             * они отсутствуют в whitelist на уровне утилиты.
             *
             * <p>Отключение полезно в окружениях с очень строгой compliance-политикой,
             * где даже sanitized destination нежелателен.
             *
             * <pre>
             * platform:
             *   error-handling:
             *     messaging:
             *       spring-messaging:
             *         include-broker-coordinates: false
             * </pre>
             *
             * @see space.br1440.platform.errorhandling.core.strategy.cause.messaging.SpringMessagingExceptionStrategy
             */
            private boolean includeBrokerCoordinates = true;
        }
    }

    /**
     * Настройки логирования событий безопасности (401, 403).
     * Гарантированно не {@code null} — при попытке установить {@code null}
     * через setter или YAML-binding используется default-экземпляр.
     */
    private Security security = new Security();

    public ErrorHandlingProperties setSecurity(Security security) {
        this.security = (security != null) ? security : new Security();
        return this;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Security {

        /**
         * Режим автоматической регистрации {@code SecurityProblemSupport}
         * во все бины {@code HttpSecurity} через {@code ProblemSecurityBeanPostProcessor}.
         *
         * <ul>
         *   <li>{@link AutoRegisterMode#ALL} (по умолчанию) — {@code BeanPostProcessor}
         *       автоматически вызывает {@code http.exceptionHandling()} с нашим
         *       {@code SecurityProblemSupport} для каждого {@code HttpSecurity}-бина.
         *       Цепочки, помеченные shared-object {@code SkipProblemSupport.class},
         *       пропускаются.</li>
         *   <li>{@link AutoRegisterMode#NONE} — авто-регистрация полностью отключена,
         *       {@code BeanPostProcessor} не создаётся.</li>
         * </ul>
         *
         * <p>Отключение полезно при необходимости кастомного {@code AuthenticationEntryPoint}
         * для отдельных {@code SecurityFilterChain} (например, SAML/OAuth2 redirect, form-login),
         * либо при конфликте с другим стартером, конфигурирующим {@code exceptionHandling}.
         *
         * <pre>
         * platform:
         *   error-handling:
         *     security:
         *       auto-register-mode: NONE
         * </pre>
         */
        private AutoRegisterMode autoRegisterMode = AutoRegisterMode.ALL;

        /**
         * Уровень логирования для ошибок аутентификации (401 {@code AuthenticationException}).
         *
         * <p>Default: {@link LogPolicy#WARN} — события аутентификации видимы в production-логах
         * и детектируются SIEM-системами. Brute-force на {@code /api/login} генерирует сотни 401
         * в секунду; при {@link LogPolicy#DEBUG} они полностью невидимы для alerting.
         *
         * <p>Устанавливайте {@link LogPolicy#DEBUG} только в dev-окружениях.
         *
         * <pre>
         * platform:
         *   error-handling:
         *     security:
         *       auth-failure-log-policy: WARN
         * </pre>
         */
        private LogPolicy authFailureLogPolicy = LogPolicy.WARN;

        /**
         * Уровень логирования для ошибок авторизации (403 {@code AccessDeniedException}).
         *
         * <p>Default: {@link LogPolicy#WARN} — попытки горизонтального privilege escalation
         * должны быть видимы для SIEM. {@link LogPolicy#DEBUG} скрывает их от alerting.
         *
         * <pre>
         * platform:
         *   error-handling:
         *     security:
         *       access-denied-log-policy: WARN
         * </pre>
         */
        private LogPolicy accessDeniedLogPolicy = LogPolicy.WARN;

        /**
         * Режим автоматической регистрации {@code SecurityProblemSupport} во все бины
         * {@code HttpSecurity}.
         */
        public enum AutoRegisterMode {

            /**
             * Регистрировать во все бины {@code HttpSecurity}, кроме помеченных shared-object
             * {@code SkipProblemSupport.class} (значение {@code Boolean.TRUE}).
             */
            ALL,

            /**
             * Не регистрировать ни в одном бине. {@code ProblemSecurityBeanPostProcessor}
             * не создаётся; пользователь подключает {@code ProblemSupportConfigurer} вручную.
             */
            NONE
        }
    }

    /**
     * Настройки разрешения correlation-id из HTTP-заголовков запроса.
     * Гарантированно не {@code null}.
     */
    private CorrelationId correlationId = new CorrelationId();

    public ErrorHandlingProperties setCorrelationId(CorrelationId correlationId) {
        this.correlationId = (correlationId != null) ? correlationId : new CorrelationId();
        return this;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class CorrelationId {

        /**
         * Дефолтное имя utility-класс это default constant value, дублируется в Javadoc у
         * соответствующего бина для удобства IDE-подсказок.
         */
        public static final int DEFAULT_MAX_LENGTH = 128;

        /**
         * Дефолтный whitelist-шаблон допустимых символов для значения correlation-id.
         * Соответствует traceparent-trace-id (hex), Zipkin/B3 trace-id, UUID и большинству
         * корпоративных соглашений.
         */
        public static final String DEFAULT_ALLOWED_PATTERN = "[A-Za-z0-9._\\-:]+";

        /**
         * Максимальная длина значения correlation-id. Значения длиннее отбрасываются,
         * генерируется UUID, инкрементируется счётчик
         * {@code error_handling_correlation_id_rejected_total{reason=length}}.
         *
         * <p>Default {@value #DEFAULT_MAX_LENGTH} символов покрывает 32-символьный traceparent
         * trace-id, 36-символьный UUID и большинство корпоративных correlation-id-форматов.
         */
        @Min(value = 16, message = "correlationId.maxLength must be >= 16")
        @Max(value = 1024, message = "correlationId.maxLength must be <= 1024")
        private int maxLength = DEFAULT_MAX_LENGTH;

        /**
         * Whitelist-шаблон регулярного выражения для допустимых символов значения correlation-id.
         * Значения, не соответствующие шаблону, отбрасываются (защита от CWE-113 Response Splitting
         * и CWE-117 Log Injection), генерируется UUID, инкрементируется счётчик
         * {@code error_handling_correlation_id_rejected_total{reason=charset}}.
         *
         * <p>Дефолт {@value #DEFAULT_ALLOWED_PATTERN} разрешает только ASCII-буквенно-цифровые символы
         * и нейтральные разделители {@code . _ - :}. Намеренно <b>не</b> допускает CR/LF/TAB,
         * пробелы и Unicode bidirectional override characters.
         */
        private String allowedPattern = DEFAULT_ALLOWED_PATTERN;
    }

    /**
     * Настройки взаимодействия с distributed tracing (W3C Trace Context, B3).
     */
    private Tracing tracing = new Tracing();

    public ErrorHandlingProperties setTracing(Tracing tracing) {
        this.tracing = (tracing != null) ? tracing : new Tracing();
        return this;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Tracing {

        /**
         * Принимать ли заголовок {@code traceparent} (W3C Trace Context) как источник
         * correlation-id. При {@code true} (по умолчанию) trace-id из {@code traceparent}
         * имеет наивысший приоритет в цепочке резолва.
         *
         * <p>Отключение может понадобиться, если приложение терминирует traceparent на gateway
         * и не желает прокидывать trace-id во внутреннюю correlation-схему.
         */
        private boolean acceptTraceparent = true;
    }

    /**
     * Базовый URL каталога ошибок для генерации {@code HelpDetail} ссылок.
     *
     * <p>Замена {@code ErrorCatalogUrlContainer} через Spring DI.
     * Если не задан, {@code HelpDetail} не генерируется.
     *
     * <pre>
     * platform:
     *   error-handling:
     *     error-catalog-base-url: https://errors.example.com/order-service/
     * </pre>
     */
    private String errorCatalogBaseUrl;


    /**
     * Настройки safety-net ответа (минимальный Problem JSON при сбое основного pipeline).
     * Гарантированно не {@code null}.
     */
    private SafetyNet safetyNet = new SafetyNet();

    public ErrorHandlingProperties setSafetyNet(SafetyNet safetyNet) {
        this.safetyNet = (safetyNet != null) ? safetyNet : new SafetyNet();
        return this;
    }

    @Getter
    @Setter
    @Accessors(chain = true)
    public static class SafetyNet {

        /**
         * Включать ли {@code applicationName} в тело safety-net ответа.
         * По умолчанию {@code false} (CWE-209: утечка внутреннего каталога сервисов).
         *
         * <pre>
         * platform:
         *   error-handling:
         *     safety-net:
         *       expose-application-name: true
         * </pre>
         */
        private boolean exposeApplicationName = false;
    }

    /**
     * Настройки маскирования чувствительных данных в свободных текстовых полях.
     */
    private MessageSanitizerProps messageSanitizer = new MessageSanitizerProps();

    public ErrorHandlingProperties setMessageSanitizer(MessageSanitizerProps messageSanitizer) {
        this.messageSanitizer = (messageSanitizer != null) ? messageSanitizer : new MessageSanitizerProps();
        return this;
    }

    /**
     * Конфигурация маскирования (CWE-209). Дополняет
     * {@code MessageSanitizer} default-набор кастомными regex-паттернами.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class MessageSanitizerProps {

        /**
         * Дополнительные regex-паттерны для маскирования. Match'и заменяются
         * на {@code ***}. Применяются <b>после</b> default-набора
         * (Bearer / Basic / cards / query secrets / JDBC password).
         *
         * <pre>
         * platform:
         *   error-handling:
         *     message-sanitizer:
         *       patterns:
         *         - "(?i)tenant=[a-z0-9-]+"
         *         - "internal-svc-[0-9]+\\.local"
         * </pre>
         *
         * <p>Невалидные regex-выражения приведут к {@code PatternSyntaxException}
         * на этапе использования (fail-fast, чтобы не пропустить misconfigured
         * sanitizer в production).
         */
        private List<String> patterns = List.of();
    }

}
