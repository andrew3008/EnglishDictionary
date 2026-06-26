package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.CorruptRecordException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.support.serializer.DeserializationException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты {@link KafkaSerializationExceptionStrategy}.
 *
 * <p>Покрывают пять контрактных аспектов:
 * <ol>
 *   <li>порядок стратегии — {@code KAFKA_SERDE = 430};</li>
 *   <li>классификация (serialization/recordTooLarge/invalidRecord/corruptRecord/unknownTopic/invalidTopic);</li>
 *   <li>retryability: 503 + RETRYABLE для CORRUPT_RECORD и UNKNOWN_TOPIC, NOT_RETRYABLE для остальных;</li>
 *   <li>обработка Spring-обёртки {@code DeserializationException} → SERIALIZATION_FAILURE;</li>
 *   <li>безопасность {@code diagnosticMetadata}: length-cap topic, никаких payload/headers целиком.</li>
 * </ol>
 */
@DisplayName("KafkaSerializationExceptionStrategy")
class KafkaSerializationExceptionStrategyTest {

    private final KafkaSerializationExceptionStrategy strategy =
            new KafkaSerializationExceptionStrategy(new StackHintExtractor(false, List.of()));

    private static ErrorInfoDetail findInfoDetail(RootCauseResolution resolution) {
        return resolution.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ErrorInfoDetail not found"));
    }

    private static String reasonOf(RootCauseResolution resolution) {
        return findInfoDetail(resolution).getReason();
    }

    private static Map<String, String> metadataOf(RootCauseResolution resolution) {
        return findInfoDetail(resolution).getDiagnosticMetadata();
    }

    private static boolean hasRetryDetail(RootCauseResolution resolution) {
        return resolution.details().stream().anyMatch(RetryInfoDetail.class::isInstance);
    }

    // ============================================================================
    // order
    // ============================================================================

    @Nested
    @DisplayName("order")
    class Order {

        @Test
        void exposesKafkaSerdeOrderConstant() {
            assertThat(strategy.getOrder()).isEqualTo(RootCauseExceptionStrategyOrder.KAFKA_SERDE);
        }
    }

    // ============================================================================
    // supportsCause
    // ============================================================================

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void supportsNativeSerializationException() {
            assertThat(strategy.supportsCause(new SerializationException("boom"))).isTrue();
        }

        @Test
        void supportsRecordTooLarge() {
            assertThat(strategy.supportsCause(new RecordTooLargeException("too big"))).isTrue();
        }

        @Test
        void supportsInvalidRecord() {
            assertThat(strategy.supportsCause(new InvalidRecordException("bad record"))).isTrue();
        }

        @Test
        void supportsCorruptRecord() {
            assertThat(strategy.supportsCause(new CorruptRecordException("corrupt"))).isTrue();
        }

        @Test
        void supportsUnknownTopicOrPartition() {
            assertThat(strategy.supportsCause(new UnknownTopicOrPartitionException("nope"))).isTrue();
        }

        @Test
        void supportsInvalidTopic() {
            assertThat(strategy.supportsCause(new InvalidTopicException("@@bad"))).isTrue();
        }

        @Test
        void supportsSpringDeserializationException() {
            // Spring-обёртка наследует KafkaException, а не нативный SerializationException.
            // Стратегия должна перехватывать её отдельно, чтобы она не попала в KafkaBrokerExceptionStrategy.
            DeserializationException ex = new DeserializationException(
                    "failed", new byte[]{1, 2, 3}, /*isKey*/ false, new RuntimeException("avro")
            );

            assertThat(strategy.supportsCause(ex)).isTrue();
        }

        @Test
        void doesNotSupportUnrelatedException() {
            assertThat(strategy.supportsCause(new IllegalStateException("nope"))).isFalse();
        }
    }

    // ============================================================================
    // classification + retryability
    // ============================================================================

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        void serializationFailureIs422NotRetryable() {
            RootCauseResolution res = strategy.extractCauseDetails(new SerializationException("avro mismatch"));

            assertThat(reasonOf(res)).isEqualTo("SERIALIZATION_FAILURE");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(hasRetryDetail(res)).isFalse();
        }

        @Test
        void springDeserializationExceptionMapsToSerializationFailure() {
            DeserializationException ex = new DeserializationException(
                    "failed", new byte[]{0}, /*isKey*/ true, new RuntimeException("avro")
            );

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(reasonOf(res)).isEqualTo("SERIALIZATION_FAILURE");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void recordTooLargeIs422NotRetryable() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new RecordTooLargeException("Records over limit"));

            assertThat(reasonOf(res)).isEqualTo("RECORD_TOO_LARGE");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void invalidRecordIs422NotRetryable() {
            RootCauseResolution res = strategy.extractCauseDetails(new InvalidRecordException("bad"));

            assertThat(reasonOf(res)).isEqualTo("INVALID_RECORD");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void corruptRecordIs503Retryable() {
            // Native CorruptRecordException extends RetriableException — broker может
            // оправиться после failover сегмента; повтор имеет смысл.
            RootCauseResolution res = strategy.extractCauseDetails(new CorruptRecordException("crc"));

            assertThat(reasonOf(res)).isEqualTo("CORRUPT_RECORD");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(hasRetryDetail(res)).isTrue();
        }

        @Test
        void unknownTopicIs503Retryable() {
            // Native UnknownTopicOrPartitionException extends RetriableException —
            // race с metadata propagation после admin-операции; повтор после паузы
            // обычно успешен.
            RootCauseResolution res = strategy.extractCauseDetails(
                    new UnknownTopicOrPartitionException("orders-v2"));

            assertThat(reasonOf(res)).isEqualTo("UNKNOWN_TOPIC");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(hasRetryDetail(res)).isTrue();
        }

        @Test
        void invalidTopicIs400NotRetryable() {
            RootCauseResolution res = strategy.extractCauseDetails(new InvalidTopicException("@@bad"));

            assertThat(reasonOf(res)).isEqualTo("INVALID_TOPIC");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(hasRetryDetail(res)).isFalse();
        }
    }

    // ============================================================================
    // logPolicy
    // ============================================================================

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTest {

        @Test
        void warnFor4xx() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                    .errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(new SerializationException("x"), entry))
                    .isEqualTo(LogPolicy.WARN);
        }

        @Test
        void errorFor5xx() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(new CorruptRecordException("x"), entry))
                    .isEqualTo(LogPolicy.ERROR);
        }
    }

    // ============================================================================
    // diagnosticMetadata
    // ============================================================================

    @Nested
    @DisplayName("diagnosticMetadata")
    class Diagnostic {

        @Test
        void doesNotIncludeRedundantExceptionClass() {
            // exceptionClass дублирует rootCauseClass (для не-обёрнутого случая —
            // одно и то же значение). Удалено ради компактности SRE-метаданных.
            Map<String, String> meta = metadataOf(
                    strategy.extractCauseDetails(new SerializationException("x"))
            );

            assertThat(meta).doesNotContainKey("exceptionClass");
        }

        @Test
        void includesRootCauseClass_whenCausePresent() {
            // CauseMetadataEnricher.enrichWithRootCause извлекает root только если
            // cause-chain непустая. Для исключений без вложенной причины секция отсутствует —
            // это контрактное поведение DiagnosticMetadataBuilder.withRootCause().
            DeserializationException ex = new DeserializationException(
                    "failed", new byte[]{0}, false, new RuntimeException("avro mismatch")
            );

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("rootCauseClass", RuntimeException.class.getName());
            assertThat(meta).containsEntry("rootCauseMessage", "avro mismatch");
        }

        @Test
        void includesCauseMessageTruncated() {
            String longMsg = "a".repeat(500);
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new SerializationException(longMsg)));

            String causeMessage = meta.get("causeMessage");
            assertThat(causeMessage).startsWith("a".repeat(200));
            assertThat(causeMessage).contains("[TRUNCATED]");
            assertThat(causeMessage.length()).isLessThan(longMsg.length());
        }

        @Test
        void usesTopicKey_notTopicName() {
            // Унификация с KafkaBrokerExceptionStrategy.KAFKA_HEADER_TO_LOGICAL_KEY: ключ "topic", не "topicName".
            DeserializationException ex = new DeserializationException(
                    "failed", new byte[]{0}, /*isKey*/ false, new RuntimeException("x"));
            // Spring DeserializationException не имеет getTopic() — извлечение отсутствует;
            // проверяем, что ключ "topicName" не появляется в любом случае.
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("topicName");
        }

        @Test
        void topicIsLengthCapped() {
            // Защита от непредвиденно длинного topic name (Kafka spec: до 249 символов):
            // в diagnosticMetadata length-cap 128.
            String longTopic = "t".repeat(300);
            // Используем reflection-only path через тестовую заглушку: создаём
            // SerializationException с подменой через subclass, который реализует getTopic().
            SerializationException ex = new TopicAwareSerializationException(longTopic);

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            String topic = meta.get("topic");
            assertThat(topic).isNotNull();
            assertThat(topic.length()).isLessThanOrEqualTo(128 + "[TRUNCATED]".length() + 16);
            assertThat(topic).startsWith("t".repeat(128));
        }

        @Test
        void includesPayloadRoleFromIsKeyAccessor() {
            DeserializationException ex = new DeserializationException(
                    "failed", new byte[]{0}, /*isKey*/ true, new RuntimeException("x"));

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("payloadRole", "KEY");
        }

        @Test
        void includesRecordSizeFromRecordTooLargePartitions() {
            RecordTooLargeException ex = new RecordTooLargeException(
                    "Records over limit",
                    Map.of(new TopicPartition("orders", 0), 5_242_880L));

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("recordSize", "5242880");
        }

        @Test
        void omitsRecordSize_whenAccessorReturnsEmpty() {
            // RecordTooLargeException без map → recordSize не извлекаемый.
            RecordTooLargeException ex = new RecordTooLargeException("no map");

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).doesNotContainKey("recordSize");
        }
    }

    /**
     * Тестовая заглушка: подкласс {@link SerializationException} с {@code getTopic()},
     * имитирующий поведение Confluent serializer-исключений.
     *
     * <p>Должен быть public-static, чтобы reflection в production-коде смогла
     * вызвать {@code getTopic()} без {@code setAccessible(true)}.
     */
    public static final class TopicAwareSerializationException extends SerializationException {
        private final String topic;

        public TopicAwareSerializationException(String topic) {
            super("schema mismatch on topic=" + topic);
            this.topic = topic;
        }

        @SuppressWarnings("unused")
        public String getTopic() {
            return topic;
        }
    }
}
