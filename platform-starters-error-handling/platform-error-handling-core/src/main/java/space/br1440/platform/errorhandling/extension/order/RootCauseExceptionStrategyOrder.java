package space.br1440.platform.errorhandling.extension.order;

import lombok.experimental.UtilityClass;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;

/**
 * Константы приоритетов для {@link RootCauseExceptionHandlingStrategy}.
 *
 * <p>Меньшее значение — более высокий приоритет (проверяется раньше).
 *
 * <h2>Диапазоны значений ORDER по доменам</h2>
 * <pre>
 *   50        Platform core (ErrorEntry)
 *   100–170   Storage / persistence (Concurrency → HikariCP → Postgres → ClickHouse → Hibernate → jOOQ → DataAccess → BeanValidation → Parse)
 *   300       Redis (Lettuce / Jedis / Spring Data Redis)
 *   335–400   Network / infrastructure (Timeout → DNS → HTTP integration → SSL)
 *   430–450   Messaging (Kafka serde → Kafka broker → RabbitMQ → Spring Messaging)
 *   530       HashiCorp Vault (secrets / PKI / transit)
 *   550       Runtime (IllegalState)
 *   555–705   Enterprise / integration (MinIO → WebSocket → Feign → WebFlux → OAuth2 → FileIO → gRPC)
 *   750–775   Serialization (Jackson → YAML → Protobuf)
 * </pre>
 *
 * <p>Пример использования в микросервисе:
 * <pre>{@code
 * @Override
 * public int getOrder() {
 *     return RootCauseExceptionStrategyOrder.DATA_ACCESS + 10; // после DataAccess, до Redis
 * }
 * }</pre>
 */
@UtilityClass
public final class RootCauseExceptionStrategyOrder {

    // --- Platform core ---

    /**
     * Вложенные платформенные исключения ({@code AbstractErrorResponseException} в причине).
     */
    public static final int ERROR_ENTRY = 50;

    // --- Storage / persistence ---

    /**
     * JPA optimistic locking ({@code OptimisticLockException}, {@code ObjectOptimisticLockingFailureException})
     * и concurrency исключения. Ставится первым среди storage-стратегий, так как optimistic-lock —
     * чёткий сигнал конфликта записи, не требует дополнительной классификации через DataAccess.
     */
    public static final int CONCURRENCY = 100;

    /**
     * Spring {@code CannotCreateTransactionException} / {@code TransactionSystemException}.
     * После {@link #CONCURRENCY} (100) — транзакционные сбои менее специфичны,
     * чем optimistic-lock конфликты.
     */
    public static final int TRANSACTION = 115;

    /**
     * HikariCP pool exhaustion / connection acquisition timeout.
     * После {@link #TRANSACTION} (115) — pool-проблемы идут после транзакционных,
     * перед специфичными JDBC/SQL стратегиями.
     */
    public static final int HIKARI = 125;

    /**
     * PostgreSQL-специфичные JDBC/SQL исключения
     * (уникальные нарушения, deadlock, constraint violation и т.д.).
     */
    public static final int POSTGRES = 135;

    /**
     * ClickHouse-специфичные исключения нативного клиента
     * ({@code com.clickhouse.client.api.ClickHouseException} и подклассы:
     * {@code ServerException}, {@code ClientException}, {@code ConnectionInitiationException},
     * {@code DataTransferException}) и обёрток JDBC-драйвера v2
     * ({@code java.sql.SQLException} с {@code ServerException} в cause-chain).
     *
     * <p>Ставится между {@link #POSTGRES} (135) и {@link #HIBERNATE} (140):
     * {@code ClickHouseException} не пересекается ни с {@code PSQLException},
     * ни с иерархией Hibernate, но обязан выполняться перед {@link #DATA_ACCESS} (150),
     * так как ClickHouse JDBC v2 транслирует серверные ошибки в {@code SQLException}
     * с нестандартными SQL-state кодами ({@code 22000}, {@code 08000}, {@code HY000}),
     * не распознаваемыми Spring {@code SQLErrorCodeSQLExceptionTranslator},
     * что приводит к generic {@code UncategorizedSQLException} без классификации
     * по серверному коду ошибки.
     */
    public static final int CLICKHOUSE = 138;

    /**
     * Hibernate ORM исключения, не проксируемые через Spring {@code DataAccessException}
     * ({@code LazyInitializationException}, {@code StaleObjectStateException} и т.п.).
     * После {@link #CLICKHOUSE} (138), перед {@link #JOOQ} (145).
     */
    public static final int HIBERNATE = 140;

    /**
     * jOOQ исключения {@code DataAccessException}, {@code DetachedException}.
     * После {@link #HIBERNATE} (140), перед generic {@link #DATA_ACCESS} (150).
     */
    public static final int JOOQ = 145;

    /**
     * Spring {@code DataAccessException} / JDBC translation — широкий catch-all
     * для всех реляционных ошибок, не перехваченных специализированными стратегиями выше.
     */
    public static final int DATA_ACCESS = 150;

    /**
     * Jakarta Bean Validation / javax.validation {@code ConstraintViolationException}
     * как root cause (например, из service-слоя, не из MVC binding).
     */
    public static final int BEAN_VALIDATION = 160;

    /**
     * {@code ParseException} / {@code DateTimeParseException} / {@code NumberFormatException}
     * как root cause. Семантически близки к валидации ({@link #BEAN_VALIDATION} = 160),
     * но обрабатываются отдельно — это ошибки парсинга формата, а не нарушения constraint.
     */
    public static final int PARSE = 170;

    /**
     * Lettuce / Jedis / Spring Data Redis.
     * Значение {@value #REDIS} сохранено в бывшем диапазоне 200–300 для стабильности порядка кастомных стратегий;
     * встроенная MongoDB root-cause стратегия удалена (не используется в платформе).
     */
    public static final int REDIS = 300;

    // --- Network / infrastructure ---

    /**
     * Generic timeout исключения ({@code SocketTimeoutException},
     * {@code java.util.concurrent.TimeoutException} и т.п.).
     * Первым среди network-стратегий: таймаут — наиболее специфичная диагностика сетевого сбоя.
     */
    public static final int TIMEOUT = 335;

    /**
     * DNS / host resolution failures ({@code UnknownHostException}).
     * После {@link #TIMEOUT} (335), перед {@link #HTTP_INTEGRATION} (350).
     * Перехватывает DNS-ошибки раньше {@code HttpIntegrationExceptionStrategy},
     * обеспечивая DNS-специфичную диагностику вместо generic upstream-ошибки.
     */
    public static final int DNS = 340;

    /**
     * HTTP upstream исключения (RestTemplate, RestClient, WebClient —
     * статусы 4xx/5xx и сетевые ошибки, не перехваченные DNS/SSL).
     * Визуальный порядок в цепочке:
     * <pre>
     *   TIMEOUT (335) → DNS (340) → HTTP_INTEGRATION (350) → SSL (400)
     * </pre>
     */
    public static final int HTTP_INTEGRATION = 350;

    /**
     * TLS/SSL handshake и смежные исключения ({@code SSLHandshakeException},
     * {@code SSLException}).
     * После {@link #HTTP_INTEGRATION} (350) — SSL-ошибки более специфичны
     * и требуют отдельной диагностики сертификатов.
     */
    public static final int SSL = 400;

    // --- Messaging ---

    /**
     * Kafka serialization/deserialization исключения
     * ({@code org.apache.kafka.common.errors.SerializationException} и подобные).
     * Первой среди messaging-стратегий: проблема формата/схемы — клиентская ошибка 4xx,
     * не связана с доступностью брокера, должна классифицироваться раньше broker-уровня.
     */
    public static final int KAFKA_SERDE = 430;

    /**
     * Kafka broker-уровневые исключения
     * ({@code org.springframework.kafka.KafkaException} и нативные {@code org.apache.kafka.common.errors.*}:
     * {@code AuthenticationException}, {@code AuthorizationException}, {@code TimeoutException},
     * {@code ProducerFencedException}, {@code OutOfOrderSequenceException}, {@code KafkaProducerException},
     * {@code KafkaReplyTimeoutException}, {@code ListenerExecutionFailedException}).
     *
     * <p>Ставится после {@link #KAFKA_SERDE} (430): сначала классифицируются
     * формат/схема ошибки, и только если их не нашли — broker-уровневые сбои.
     * Перед {@link #RABBITMQ} (445): cross-broker exception types почти не
     * пересекаются, но Kafka проверяется первым по частоте использования
     * в типичной enterprise-системе.
     */
    public static final int KAFKA_BROKER = 435;

    /**
     * RabbitMQ / AMQP broker-уровневые исключения
     * ({@code org.springframework.amqp.AmqpException} и подклассы:
     * {@code AmqpConnectException}, {@code AmqpTimeoutException},
     * {@code AmqpAuthenticationException}, {@code AmqpRejectAndDontRequeueException},
     * {@code ListenerExecutionFailedException} из spring-rabbit).
     * Двухуровневый dispatch: Spring class → AMQP reply code из
     * {@code ShutdownSignalException.getReason()}.
     *
     * <p>Ставится после {@link #KAFKA_BROKER} (435), перед {@link #SPRING_MESSAGING} (450):
     * AMQP-специфичные коды должны классифицироваться раньше generic Spring Messaging
     * fallback (например, {@code AmqpException} наследует от {@code RuntimeException},
     * а {@code MessagingException} — от {@code MessagingException}, иерархии не пересекаются;
     * порядок диктуется только логикой specificity).
     */
    public static final int RABBITMQ = 445;

    /**
     * Generic Spring Messaging исключения
     * ({@code org.springframework.messaging.MessagingException} и подклассы:
     * {@code MessageDeliveryException}, {@code MessageConversionException},
     * {@code MessageHandlingException}, {@code MissingSessionUserException}).
     *
     * <p>Покрывает все транспортные стеки на базе spring-messaging:
     * STOMP over WebSocket ({@code simp.*}), Spring Integration, Spring Cloud Stream,
     * а также wrapping-сценарии в Spring Kafka ({@code @KafkaListener}) и Spring AMQP
     * ({@code MessagingMessageListenerAdapter}), когда внешняя обёртка —
     * {@code MessagingException}, а corner-case Kafka/AMQP причина уходит в
     * {@code rootCauseClass} через {@code DiagnosticMetadataBuilder.withRootCause}.
     *
     * <p>Последняя среди messaging-стратегий — generic fallback после {@link #KAFKA_SERDE},
     * {@link #KAFKA_BROKER} и {@link #RABBITMQ}. Активируется почти в любом Spring Boot
     * приложении, использующем библиотеку: {@code spring-messaging} транзитивно
     * подтягивается Spring Web/WebFlux/WebSocket. Это намеренное design-решение —
     * generic fallback для {@code MessagingException} в любом контексте.
     */
    public static final int SPRING_MESSAGING = 450;

    // --- HashiCorp Vault ---

    /**
     * HashiCorp Vault (Spring Vault) — управление секретами, PKI, transit encryption.
     * Значение {@value #VAULT} оставлено в бывшем «500+» диапазоне для стабильности порядка относительно кастомных стратегий;
     * встроенные retry/circuit-breaker root-cause стратегии удалены (не используются в платформе).
     */
    public static final int VAULT = 530;

    // --- Runtime ---

    /**
     * {@code IllegalStateException} как root cause — широкий fallback
     * для ошибок состояния компонентов. После {@link #VAULT} (530), до enterprise/integration (555+).
     */
    public static final int ILLEGAL_STATE = 550;

    // --- Enterprise / integration ---

    /**
     * MinIO / S3-compatible client.
     */
    public static final int MINIO = 555;

    /**
     * WebSocket session / handshake.
     */
    public static final int WEBSOCKET = 600;

    /**
     * OpenFeign / Spring Cloud OpenFeign HTTP-клиент.
     */
    public static final int FEIGN = 605;

    /**
     * Reactive stack (WebClient / WebFlux).
     */
    public static final int WEBFLUX = 630;

    /**
     * Spring Authorization Server / OAuth2 authorization.
     */
    public static final int OAUTH2_AUTH = 650;

    /**
     * Общие java.io/java.nio файловые ошибки — fallback после всех специализированных стратегий.
     */
    public static final int FILE_IO = 660;

    /**
     * gRPC status / details как root cause.
     */
    public static final int GRPC = 705;

    // --- Serialization ---

    /**
     * Jackson (de)serialization как root cause.
     */
    public static final int JACKSON = 750;

    /**
     * YAML (SnakeYAML / Jackson YAML) как root cause.
     */
    public static final int YAML = 760;

    /**
     * Protobuf parsing/serialization.
     */
    public static final int PROTOBUF = 775;

}
