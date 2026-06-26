package space.br1440.platform.errorhandling.core.strategy.top;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.base.AbstractTopExceptionStrategy;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.core.util.ClassUtils;
import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.ReflectionUtils;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;
import space.br1440.platform.errorhandling.extension.order.TopExceptionStrategyOrder;

import java.util.*;

/**
 * Стратегия обработки Kafka-исключений как top-level exception.
 *
 * <h2>Scope</h2>
 * <p>Покрывает <b>producer-side synchronous path</b>: когда контроллер вызывает
 * {@code kafkaTemplate.send(...).get()} или {@code sendDefault(...).get()}
 * и Kafka-исключение бросается напрямую в DispatcherServlet.
 *
 * <p>Listener/consumer semantics <b>намеренно не покрываются</b> —
 * у consumer свой error handling pipeline (SeekToCurrentErrorHandler, DeadLetterPublishingRecoverer и т.д.).
 *
 * <h2>Разграничение с root-cause стратегиями</h2>
 * <ul>
 *   <li>{@code KafkaSerializationExceptionStrategy} (root-cause, ORDER=430) — serialization/schema ошибки как cause внутри бизнес-исключений</li>
 *   <li>{@code MessagingExceptionStrategy} (root-cause, ORDER=450) — broker connectivity/timeout/auth как cause</li>
 *   <li>Данная стратегия (top-level, ORDER=675) — {@code KafkaException} брошенное <b>напрямую</b> из контроллера</li>
 * </ul>
 *
 * <h2>Поддерживаемые исключения</h2>
 * <ul>
 *   <li>{@code org.springframework.kafka.KafkaException} — базовый Spring Kafka</li>
 *   <li>{@code org.springframework.kafka.core.KafkaProducerException} — ошибка producer</li>
 *   <li>{@code org.springframework.kafka.requestreply.KafkaReplyTimeoutException} — timeout request-reply</li>
 * </ul>
 *
 * <h2>Безопасность (CWE-209)</h2>
 * <p>Broker addresses, topic names, partition details включаются только в {@code diagnosticMetadata} (SRE-only),
 * не в клиентский ответ.
 */
public class KafkaExceptionStrategy extends AbstractTopExceptionStrategy {

    private static final int MAX_MSG_LENGTH = 300;
    private static final ClassLoader LOADER = KafkaExceptionStrategy.class.getClassLoader();

    @Nullable
    private static final Class<?> KAFKA_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.KafkaException", LOADER);
    @Nullable
    private static final Class<?> KAFKA_PRODUCER_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.core.KafkaProducerException", LOADER);
    @Nullable
    private static final Class<?> KAFKA_REPLY_TIMEOUT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.springframework.kafka.requestreply.KafkaReplyTimeoutException", LOADER);

    @Nullable
    private static final Class<?> KAFKA_AUTH_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.AuthorizationException", LOADER);
    @Nullable
    private static final Class<?> KAFKA_TIMEOUT_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.TimeoutException", LOADER);
    @Nullable
    private static final Class<?> KAFKA_SERIALIZATION_EXCEPTION =
            ReflectionUtils.loadOptionalClass("org.apache.kafka.common.errors.SerializationException", LOADER);

    private final int maxCauseDepth;

    public KafkaExceptionStrategy(@NonNull CauseChainResolver causeChainResolver, int maxCauseDepth) {
        super(TopExceptionStrategyOrder.KAFKA, causeChainResolver);
        this.maxCauseDepth = maxCauseDepth;
    }

    @Override
    @NonNull
    public LogPolicy logPolicy(@NonNull Exception exception, @NonNull ErrorEntry entry) {
        return entry.getHttpStatus().is4xxClientError() ? LogPolicy.WARN : LogPolicy.ERROR;
    }

    @Override
    public boolean supports(@NonNull Exception exception) {
        return ClassUtils.isInstanceOf(KAFKA_EXCEPTION, exception);
    }

    @Override
    @NonNull
    protected ErrorEntry doHandle(@NonNull Exception exception, @NonNull ErrorHandlingContext context) {
        KafkaErrorType type = classify(exception, maxCauseDepth);

        List<ErrorDetail> details = new ArrayList<>();
        if (type.retryable) {
            details.add(new RetryInfoDetail());
        }

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.reason)
                .domain("kafka")
                .clientMetadata(Map.of("brokerType", "KAFKA", "operation", "SEND"))
                .diagnosticMetadata(buildDiagnosticMetadata(exception, type, context.getStackHintExtractor()))
                .build();
        details.add(info);

        return baseEntry(type.httpStatus, type.errorCode, type.message, context)
                .details(Collections.unmodifiableList(details))
                .build();
    }

    /**
     * Классифицирует Kafka-исключение, обходя полную cause chain до {@code maxCauseDepth}.
     *
     * <p>Реальная цепочка Kafka producer при {@code send().get()}:
     * <pre>
     * KafkaException
     *   └─ ExecutionException
     *        └─ org.apache.kafka.common.errors.TimeoutException  ← depth=2, 1-уровневый обход не находил
     * </pre>
     */
    @NonNull
    private static KafkaErrorType classify(@NonNull Exception exception, int maxCauseDepth) {
        if (ClassUtils.isInstanceOf(KAFKA_REPLY_TIMEOUT_EXCEPTION, exception)) {
            return KafkaErrorType.REPLY_TIMEOUT;
        }

        if (ClassUtils.isInstanceOf(KAFKA_PRODUCER_EXCEPTION, exception)) {
            return KafkaErrorType.PRODUCER_FAILURE;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = exception.getCause();
        int depth = 0;

        while (current != null && depth < maxCauseDepth && visited.add(current)) {
            if (ClassUtils.isInstanceOf(KAFKA_TIMEOUT_EXCEPTION, current)) {
                return KafkaErrorType.BROKER_TIMEOUT;
            }

            if (ClassUtils.isInstanceOf(KAFKA_AUTH_EXCEPTION, current)) {
                return KafkaErrorType.AUTHORIZATION;
            }

            if (ClassUtils.isInstanceOf(KAFKA_SERIALIZATION_EXCEPTION, current)) {
                return KafkaErrorType.SERIALIZATION;
            }

            current = current.getCause();
            depth++;
        }

        return KafkaErrorType.GENERIC;
    }

    @NonNull
    private static Map<String, String> buildDiagnosticMetadata(@NonNull Exception exception,
                                                               @NonNull KafkaErrorType type,
                                                               @NonNull StackHintExtractor stackHintExtractor) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(exception, stackHintExtractor);
        builder.put("kafkaErrorType", type.reason);

        String message = exception.getMessage();
        builder.putTruncatedIfPresent("causeMessage", message, MAX_MSG_LENGTH);

        Throwable cause = exception.getCause();
        if (cause != null && cause != exception) {
            builder.put("rootCauseClass", cause.getClass().getName());

            String causeMessage = cause.getMessage();
            builder.putTruncatedIfPresent("rootCauseMessage", causeMessage, MAX_MSG_LENGTH);
        }

        ReflectionUtils.invokeMethod(exception, "getFailedProducerRecord", record -> {
            if (record == null) {
                return;
            }

            ReflectionUtils.invokeMethod(record, "topic", topic -> {
                if (topic instanceof String s && StringUtils.hasText(s)) {
                    builder.put("topic", s);
                }
            });
        });

        return builder.withStackHint().build();
    }

    private enum KafkaErrorType {
        BROKER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "DEADLINE_EXCEEDED", "BROKER_TIMEOUT", true,
                "The Kafka broker did not respond within the configured timeout."),

        REPLY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "DEADLINE_EXCEEDED", "REPLY_TIMEOUT", true,
                "The Kafka request-reply operation timed out waiting for a response."),

        AUTHORIZATION(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "KAFKA_AUTHORIZATION", false,
                "The Kafka client is not authorized to perform this operation."),

        SERIALIZATION(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "SERIALIZATION_FAILURE", false,
                "Failed to serialize the message for Kafka."),

        PRODUCER_FAILURE(HttpStatus.BAD_GATEWAY, "UNAVAILABLE", "PRODUCER_FAILURE", true,
                "The Kafka producer failed to send the message."),

        GENERIC(HttpStatus.BAD_GATEWAY, "UNAVAILABLE", "KAFKA_ERROR", true,
                "An error occurred while communicating with the Kafka broker.");

        final HttpStatus httpStatus;
        final String errorCode;
        final String reason;
        final boolean retryable;
        final String message;

        KafkaErrorType(HttpStatus httpStatus,
                       String errorCode,
                       String reason,
                       boolean retryable,
                       String message) {
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
            this.reason = reason;
            this.retryable = retryable;
            this.message = message;
        }
    }
}
