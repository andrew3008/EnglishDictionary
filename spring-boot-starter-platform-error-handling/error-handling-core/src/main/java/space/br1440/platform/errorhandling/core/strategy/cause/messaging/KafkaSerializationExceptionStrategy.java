package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.core.util.sanitize.LogSanitizer;
import space.br1440.platform.errorhandling.extension.ErrorClassification;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Root-cause стратегия для ошибок сериализации/десериализации Kafka-сообщений.
 *
 * <h2>Поддерживаемые исключения</h2>
 *
 * <p>Все классы загружаются через {@link ReflectionUtils#loadOptionalClass} —
 * стратегия не активируется, если kafka-clients отсутствует на classpath.
 *
 * <pre>
 *   org.apache.kafka.common.errors.SerializationException     → 422  SERIALIZATION_FAILURE   NOT_RETRYABLE
 *   └── (все подклассы, включая Avro/Protobuf/JSON Schema)    → 422  SERIALIZATION_FAILURE   NOT_RETRYABLE
 *
 *   org.apache.kafka.common.errors.RecordTooLargeException    → 422  RECORD_TOO_LARGE        NOT_RETRYABLE
 *   org.apache.kafka.common.InvalidRecordException            → 422  INVALID_RECORD          NOT_RETRYABLE
 *   org.apache.kafka.common.errors.CorruptRecordException     → 503  CORRUPT_RECORD          RETRYABLE
 *   org.apache.kafka.common.errors.UnknownTopicOrPartitionException → 503  UNKNOWN_TOPIC      RETRYABLE
 *   org.apache.kafka.common.errors.InvalidTopicException      → 400  INVALID_TOPIC           NOT_RETRYABLE
 *
 *   org.springframework.kafka.support.serializer.DeserializationException → 422  SERIALIZATION_FAILURE  NOT_RETRYABLE
 * </pre>
 *
 * <h2>Безопасность (CWE-209)</h2>
 * <p>
 * В клиентский ответ (clientMetadata / DTO) намеренно <b>не вкладываются</b> payload,
 * полный текст сообщений и прочие данные, из которых восстанавливаются чувствительные
 * детали схемы или бизнес-содержимое. В {@code diagnosticMetadata} (не в DTO) допускаются
 * усечённые и перечисленные выше поля для расследований SRE.
 */
public class KafkaSerializationExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final String DOMAIN = "kafka-serialization";

    /** Лимит длины {@code topic} в diagnosticMetadata (Kafka spec: до 249 символов). */
    private static final int MAX_TOPIC_LENGTH = 128;

    private static final ClassLoader LOADER = KafkaSerializationExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> SERIALIZATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.SerializationException", LOADER);

    @Nullable
    private static final Class<?> RECORD_TOO_LARGE_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.RecordTooLargeException", LOADER);

    /**
     * Брокер отклонил запись как невалидную (нарушение формата записи на уровне протокола).
     *
     * <p>Класс располагается в пакете {@code org.apache.kafka.common} (не в {@code .errors}),
     * исторически перенесён туда в Kafka 2.x.
     */
    @Nullable
    private static final Class<?> INVALID_RECORD_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.InvalidRecordException", LOADER);

    @Nullable
    private static final Class<?> CORRUPT_RECORD_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.CorruptRecordException", LOADER);

    @Nullable
    private static final Class<?> UNKNOWN_TOPIC_OR_PARTITION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.UnknownTopicOrPartitionException", LOADER);

    @Nullable
    private static final Class<?> INVALID_TOPIC_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.InvalidTopicException", LOADER);

    @Nullable
    private static final Class<?> SPRING_DESERIALIZATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.support.serializer.DeserializationException", LOADER);

    private static final List<TypeMatch> TYPE_MATCHES = List.of(
            new TypeMatch(RECORD_TOO_LARGE_EXCEPTION, SerializationErrorType.RECORD_TOO_LARGE),
            new TypeMatch(CORRUPT_RECORD_EXCEPTION, SerializationErrorType.CORRUPT_RECORD),
            new TypeMatch(INVALID_RECORD_EXCEPTION, SerializationErrorType.INVALID_RECORD),
            new TypeMatch(UNKNOWN_TOPIC_OR_PARTITION_EXCEPTION, SerializationErrorType.UNKNOWN_TOPIC),
            new TypeMatch(INVALID_TOPIC_EXCEPTION, SerializationErrorType.INVALID_TOPIC)
    );

    public KafkaSerializationExceptionStrategy(@NonNull StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.KAFKA_SERDE, stackHintExtractor);
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return ClassUtils.isInstanceOf(SERIALIZATION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(RECORD_TOO_LARGE_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(INVALID_RECORD_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(CORRUPT_RECORD_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(UNKNOWN_TOPIC_OR_PARTITION_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(INVALID_TOPIC_EXCEPTION, cause)
                || ClassUtils.isInstanceOf(SPRING_DESERIALIZATION_EXCEPTION, cause);
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        SerializationErrorType type = classify(cause);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryability() == Retryability.RETRYABLE) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.name())
                .domain(DOMAIN)
                .diagnosticMetadata(buildDiagnosticMetadata(cause))
                .build();
        details.add(info);

        return type.toResolution(details);
    }

    /**
     * <ul>
     *   <li>5xx (CORRUPT_RECORD, UNKNOWN_TOPIC) — инфраструктурный сбой → ERROR.</li>
     *   <li>422 (SERIALIZATION_FAILURE/RECORD_TOO_LARGE/INVALID_RECORD) — contract drift
     *       или application-bug на producer-стороне; WARN достаточно (детальный
     *       payload-level error отличается LogPolicy самого приложения).</li>
     *   <li>400 (INVALID_TOPIC) — клиентская ошибка → WARN.</li>
     * </ul>
     * <p>Producer-side serialization failures (NPE в Avro, schema mismatch при отправке)
     * семантически попадают в 422 + WARN: предполагается, что приложение само логирует
     * ошибку в своём {@code KafkaTemplate.send().exceptionally}-handler с нужным уровнем.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        HttpStatus httpStatus = errorEntry.getHttpStatus();
        if (httpStatus.is5xxServerError()) {
            return LogPolicy.ERROR;
        }

        return LogPolicy.WARN;
    }

    @NonNull
    private static SerializationErrorType classify(@NonNull Throwable cause) {
        if (ClassUtils.isInstanceOf(SPRING_DESERIALIZATION_EXCEPTION, cause)) {
            return SerializationErrorType.SERIALIZATION_FAILURE;
        }

        for (TypeMatch match : TYPE_MATCHES) {
            if (ClassUtils.isInstanceOf(match.type(), cause)) {
                return match.errorType();
            }
        }

        return SerializationErrorType.SERIALIZATION_FAILURE;
    }

    /**
     * CWE-209: payload намеренно не включается ни в один слой metadata.
     * {@code topic}, {@code schemaId}, {@code causeMessage} допустимы
     * в {@code diagnosticMetadata} (SRE-only, не попадает в DTO).
     */
    @NonNull
    private Map<String, String> buildDiagnosticMetadata(@NonNull Throwable cause) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCauseRaw(cause, stackHintExtractor);

        builder.putTruncatedIfPresent(
                "causeMessage", cause.getMessage(), DiagnosticMetadataBuilder.DEFAULT_MAX_MESSAGE_LENGTH
        );

        enrichKafkaSerdeDiagnosticReflection(builder, cause);

        return builder.withRootCause().withStackHint().build();
    }

    private static void enrichKafkaSerdeDiagnosticReflection(@NonNull DiagnosticMetadataBuilder builder,
                                                             @NonNull Throwable cause) {
        ReflectionUtils.invokeMethod(cause, "getSerializer", serializer -> {
            // Некоторые API возвращают Class<?> вместо инстанса; трактуем оба случая.
            if (serializer instanceof Class<?> clazz) {
                builder.put("serializerClassName", clazz.getSimpleName());
            } else if (serializer != null) {
                builder.put("serializerClassName", serializer.getClass().getSimpleName());
            }
        });

        ReflectionUtils.invokeMethod(cause, "getTopic", topic -> {
            if (topic instanceof String s && StringUtils.hasText(s)) {
                builder.put("topic", LogSanitizer.sanitize(s, MAX_TOPIC_LENGTH));
            }
        });

        ReflectionUtils.invokeMethod(cause, "isKey", isKey -> {
            if (isKey instanceof Boolean b) {
                builder.put("payloadRole", b ? "KEY" : "VALUE");
            }
        });

        ReflectionUtils.invokeMethod(cause, "getSchemaId", schemaId -> {
            if (schemaId instanceof Integer i) {
                builder.put("schemaId", i.toString());
            }
        });

        enrichRecordTooLargeSize(builder, cause);
    }

    private static void enrichRecordTooLargeSize(@NonNull DiagnosticMetadataBuilder builder,
                                                 @NonNull Throwable cause) {
        ReflectionUtils.invokeMethod(cause, "recordTooLargePartitions", partitions -> {
            if (!(partitions instanceof Map<?, ?> map) || map.isEmpty()) {
                return;
            }

            long maxSize = 0L;
            for (Object value : map.values()) {
                if (value instanceof Number n) {
                    long size = n.longValue();
                    if (size > maxSize) {
                        maxSize = size;
                    }
                }
            }

            if (maxSize > 0L) {
                builder.put("recordSize", Long.toString(maxSize));
            }
        });
    }

    private record TypeMatch(@Nullable Class<?> type, @NonNull SerializationErrorType errorType) {
    }

    private enum SerializationErrorType implements ErrorClassification {

        SERIALIZATION_FAILURE(
                HttpStatus.UNPROCESSABLE_ENTITY,
                Retryability.NOT_RETRYABLE,
                "The Kafka message could not be serialized or deserialized. " +
                        "The message format or schema is invalid or incompatible with the expected schema."),

        RECORD_TOO_LARGE(
                HttpStatus.UNPROCESSABLE_ENTITY,
                Retryability.NOT_RETRYABLE,
                "The Kafka message exceeds the maximum allowed size. " +
                        "Reduce the message payload or increase the broker/producer size limits."),

        INVALID_RECORD(
                HttpStatus.UNPROCESSABLE_ENTITY,
                Retryability.NOT_RETRYABLE,
                "The Kafka record is invalid and was rejected by the broker. " +
                        "Verify the record structure and encoding."),

        CORRUPT_RECORD(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The Kafka record is corrupted (CRC mismatch or truncation). " +
                        "The broker may recover after segment failover."),

        UNKNOWN_TOPIC(
                HttpStatus.SERVICE_UNAVAILABLE,
                Retryability.RETRYABLE,
                "The specified Kafka topic does not exist or the partition is unavailable. " +
                        "This may be a transient metadata propagation delay; retry is recommended."),

        INVALID_TOPIC(
                HttpStatus.BAD_REQUEST,
                Retryability.NOT_RETRYABLE,
                "The Kafka topic name is invalid. " +
                        "Topic names must match the allowed pattern and length constraints.");

        private final HttpStatus httpStatus;
        private final Retryability retryability;
        private final String message;

        SerializationErrorType(@NonNull HttpStatus httpStatus,
                               @NonNull Retryability retryability,
                               @NonNull String message) {
            this.httpStatus = httpStatus;
            this.retryability = retryability;
            this.message = message;
        }

        @Override @NonNull public HttpStatus httpStatus() {
            return httpStatus;
        }

        @Override @NonNull public Retryability retryability() {
            return retryability;
        }

        @Override @NonNull public String message() {
            return message;
        }
    }
}
