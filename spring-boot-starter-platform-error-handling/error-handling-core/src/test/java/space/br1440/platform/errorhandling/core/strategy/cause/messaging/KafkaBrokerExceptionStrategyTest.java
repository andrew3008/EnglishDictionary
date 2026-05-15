package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.OutOfOrderSequenceException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.requestreply.KafkaReplyTimeoutException;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты {@link KafkaBrokerExceptionStrategy}.
 *
 * <p>Покрывают пять контрактных аспектов:
 * <ol>
 *   <li>порядок стратегии — {@code KAFKA_BROKER = 435};</li>
 *   <li>классификация (auth/authz/transactional/timeout/connection/generic);</li>
 *   <li>type-safe определение нативных kafka-clients исключений (без Spring-обёртки);</li>
 *   <li>обход cause chain — нативное исключение в глубине (через ExecutionException и т.п.);</li>
 *   <li>безопасность {@code diagnosticMetadata}: координаты только при флаге, никаких payload/headers целиком.</li>
 * </ol>
 */
@DisplayName("KafkaBrokerExceptionStrategy")
class KafkaBrokerExceptionStrategyTest {

    /** Стратегия с включёнными координатами и без stackHint — для большинства тестов. */
    private final KafkaBrokerExceptionStrategy strategy =
            new KafkaBrokerExceptionStrategy(true, new StackHintExtractor(false, List.of()));

    /** Стратегия без координат — для проверки secure-mode. */
    private final KafkaBrokerExceptionStrategy strategyNoCoords =
            new KafkaBrokerExceptionStrategy(false, new StackHintExtractor(false, List.of()));

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

    // ============================================================================
    // order
    // ============================================================================

    @Nested
    @DisplayName("order")
    class Order {

        @Test
        void orderEqualsKafkaBrokerConstant() {
            assertThat(strategy.getOrder()).isEqualTo(RootCauseExceptionStrategyOrder.KAFKA_BROKER);
            assertThat(strategy.getOrder()).isEqualTo(435);
        }
    }

    // ============================================================================
    // supportsCause
    // ============================================================================

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsSpringKafkaException() {
            assertThat(strategy.supportsCause(new KafkaException("broker down"))).isTrue();
        }

        @Test
        void acceptsKafkaProducerException() {
            assertThat(strategy.supportsCause(
                    new KafkaProducerException(null, "send failed", new RuntimeException()))).isTrue();
        }

        @Test
        void acceptsKafkaReplyTimeoutException() {
            assertThat(strategy.supportsCause(new KafkaReplyTimeoutException("reply timeout"))).isTrue();
        }

        @Test
        void acceptsNativeKafkaAuthenticationException() {
            // SaslAuthenticationException — нативный kafka-clients тип, наследник AuthenticationException.
            assertThat(strategy.supportsCause(
                    new SaslAuthenticationException("PLAIN auth rejected"))).isTrue();
        }

        @Test
        void acceptsNativeProducerFencedException() {
            assertThat(strategy.supportsCause(new ProducerFencedException("fenced"))).isTrue();
        }

        @Test
        void rejectsGenericExceptions() {
            assertThat(strategy.supportsCause(new RuntimeException("oops"))).isFalse();
            assertThat(strategy.supportsCause(new IllegalStateException("nope"))).isFalse();
        }
    }

    // ============================================================================
    // Классификация — top-level Spring Kafka обёртки
    // ============================================================================

    @Nested
    @DisplayName("classification — Spring Kafka")
    class ClassificationSpringKafka {

        @Test
        void kafkaReplyTimeoutClassifiedAsTimeout() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new KafkaReplyTimeoutException("no reply within 5s"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("TIMEOUT");
            assertThat(res.details().stream().anyMatch(d -> d instanceof RetryInfoDetail)).isTrue();
        }

        @Test
        void plainKafkaExceptionFallsToGeneric() {
            RootCauseResolution res = strategy.extractCauseDetails(new KafkaException("ctx"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("GENERIC");
            assertThat(res.details().stream().noneMatch(d -> d instanceof RetryInfoDetail)).isTrue();
        }
    }

    // ============================================================================
    // Классификация — нативные kafka-clients типы
    // ============================================================================

    @Nested
    @DisplayName("classification — native kafka-clients")
    class ClassificationNative {

        @Test
        void nativeAuthenticationExceptionClassifiedAsAuthFailure() {
            // type-safe: instanceof AuthenticationException
            RootCauseResolution res = strategy.extractCauseDetails(
                    new SaslAuthenticationException("invalid token"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("AUTH_FAILURE");
        }

        @Test
        void nativeAuthorizationExceptionClassifiedAsAuthorizationDenied() {
            RootCauseResolution res = strategy.extractCauseDetails(new AuthorizationException("not authorized"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("AUTHORIZATION_DENIED");
        }

        @Test
        void nativeTimeoutExceptionClassifiedAsTimeout() {
            RootCauseResolution res = strategy.extractCauseDetails(new TimeoutException("metadata fetch"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("TIMEOUT");
        }

        @Test
        void producerFencedExceptionClassifiedAsTransactionFatal() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ProducerFencedException("transactional.id=tx-1 fenced"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("TRANSACTION_FATAL");
        }

        @Test
        void outOfOrderSequenceClassifiedAsTransactionFatal() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new OutOfOrderSequenceException("transactional.id=tx-2 out-of-order"));

            assertThat(reasonOf(res)).isEqualTo("TRANSACTION_FATAL");
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }
    }

    // ============================================================================
    // Cause chain traversal
    // ============================================================================

    @Nested
    @DisplayName("cause chain traversal")
    class CauseChainTraversal {

        @Test
        void detectsAuthExceptionWrappedInKafkaException() {
            // KafkaException → AuthenticationException
            KafkaException wrapped = new KafkaException("kafka", new SaslAuthenticationException("bad creds"));

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("AUTH_FAILURE");
        }

        @Test
        void detectsConnectExceptionInCauseChain() {
            // KafkaException → RuntimeException → ConnectException
            KafkaException wrapped = new KafkaException(
                    "send failed",
                    new RuntimeException("layer", new ConnectException("connection refused")));

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("CONNECTION_FAILURE");
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void detectsUnknownHostInCauseChain() {
            KafkaException wrapped = new KafkaException("dns", new UnknownHostException("kafka-broker"));

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("CONNECTION_FAILURE");
        }

        @Test
        void detectsSocketTimeoutAsTimeout() {
            // SocketTimeout — это TIMEOUT, не CONNECTION_FAILURE.
            KafkaException wrapped = new KafkaException("io", new SocketTimeoutException("read"));

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("TIMEOUT");
        }
    }

    // ============================================================================
    // GENERIC — invariant retryable=false
    // ============================================================================

    @Nested
    @DisplayName("GENERIC retryability invariant")
    class GenericRetryability {

        @Test
        void genericIsNotRetryable() {
            // Контрактная регрессионная проверка: GENERIC не должен быть retryable
            // (риск retry-storm на неклассифицированной ошибке).
            RootCauseResolution res = strategy.extractCauseDetails(new KafkaException("?"));

            assertThat(reasonOf(res)).isEqualTo("GENERIC");
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(res.details().stream().noneMatch(d -> d instanceof RetryInfoDetail)).isTrue();
        }
    }

    // ============================================================================
    // diagnosticMetadata
    // ============================================================================

    @Nested
    @DisplayName("diagnosticMetadata")
    class Metadata {

        @Test
        void doesNotContainRedundantBrokerTypeAndExceptionClass() {
            // brokerType=KAFKA дублирует domain=kafka из ErrorInfoDetail; exceptionClass дублирует
            // rootCauseClass/kafkaErrorClass — оба удалены ради компактности SRE-метаданных.
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(new KafkaException("x")));

            assertThat(meta).doesNotContainKey("brokerType");
            assertThat(meta).doesNotContainKey("exceptionClass");
        }

        @Test
        void includesCauseMessageTruncated() {
            // Длинное сообщение усекается до 200 символов плюс маркер усечения (унифицировано
            // с другими strategy для предсказуемого размера log payload).
            String longMsg = "a".repeat(500);
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(new KafkaException(longMsg)));

            String causeMessage = meta.get("causeMessage");
            assertThat(causeMessage).startsWith("a".repeat(200));
            assertThat(causeMessage).contains("[TRUNCATED]");
            assertThat(causeMessage.length()).isLessThan(longMsg.length());
        }

        @Test
        void includesNetworkCauseFieldsForConnectionFailure() {
            KafkaException wrapped = new KafkaException(
                    "io", new ConnectException("connection refused at host:9092"));

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(wrapped));

            assertThat(meta).containsEntry("networkCauseClass", ConnectException.class.getName());
            assertThat(meta.get("networkCauseMessage")).contains("connection refused");
        }

        @Test
        void includesAuthMechanismRegexHintForAuthFailure() {
            // SASL/PLAIN/SCRAM в сообщении подхватывается regex.
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new SaslAuthenticationException("Authentication failed: SASL PLAIN mechanism rejected")));

            assertThat(meta).containsKey("authMechanism");
            assertThat(meta.get("authMechanism")).isIn("SASL", "PLAIN");
        }

        @Test
        void includesTransactionalIdForTransactionFatal() {
            ProducerFencedException ex = new ProducerFencedException(
                    "Producer with transactional.id=order-svc-1 was fenced");

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("transactionalId", "order-svc-1");
        }

        @Test
        void includesNativeKafkaErrorClassHint() {
            // SimpleName нативного класса попадает в kafkaErrorClass.
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new SaslAuthenticationException("bad")));

            assertThat(meta).containsEntry("kafkaErrorClass", "SaslAuthenticationException");
        }

        @Test
        void includesTopicFromProducerRecord_whenCoordinatesEnabled() {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>("orders.created", "key", "value");
            KafkaProducerException ex = new KafkaProducerException(
                    record, "send failed", new RuntimeException("net"));

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("topic", "orders.created");
        }

        @Test
        void operationIsSendForKafkaProducerException() {
            ProducerRecord<String, String> record = new ProducerRecord<>("t", "k", "v");
            KafkaProducerException ex = new KafkaProducerException(record, "send failed", new RuntimeException());

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            assertThat(meta).containsEntry("kafkaOperation", "SEND");
        }

        @Test
        void doesNotIncludeBrokerCoordinates_whenFlagDisabled() {
            ProducerRecord<String, String> record = new ProducerRecord<>("topic", "k", "v");
            KafkaProducerException ex = new KafkaProducerException(record, "send failed", new RuntimeException());

            Map<String, String> meta = metadataOf(strategyNoCoords.extractCauseDetails(ex));

            // При false: ни topic, ни kafkaOperation не попадают.
            assertThat(meta).doesNotContainKey("topic");
            assertThat(meta).doesNotContainKey("kafkaOperation");
            // Базовые SRE-поля cause-chain остаются (без избыточных brokerType/exceptionClass).
            assertThat(meta).containsKey("rootCauseClass");
        }

        @Test
        void transactionalIdIsLengthCapped() {
            // Защита от непредвиденно длинного transactional.id: regex может извлечь до 249
            // символов (Kafka spec), но в diagnosticMetadata кладётся cap 128.
            String longId = "a".repeat(300);
            ProducerFencedException ex = new ProducerFencedException(
                    "Producer with transactional.id=" + longId + " was fenced");

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            String txId = meta.get("transactionalId");
            assertThat(txId).isNotNull();
            assertThat(txId.length()).isLessThanOrEqualTo(128 + "[TRUNCATED]".length() + 16);
            assertThat(txId).startsWith("a".repeat(128));
        }
    }

    // ============================================================================
    // logPolicy
    // ============================================================================

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyContract {

        @Test
        void errorFor5xx() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(new KafkaException("x"), entry)).isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void warnFor4xx() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.FORBIDDEN)
                    .errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(new AuthorizationException("x"), entry)).isEqualTo(LogPolicy.WARN);
        }
    }

    // ============================================================================
    // CWE-209: secure metadata
    // ============================================================================

    @Nested
    @DisplayName("CWE-209 — no payload / no application headers")
    class SecureMetadata {

        @Test
        void noFreeFormHeadersInMetadata() {
            // У ProducerRecord могут быть произвольные application headers — стратегия
            // не должна вытягивать их в metadata: только whitelist системных kafka_* ключей
            // через MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY.
            ProducerRecord<String, String> record = new ProducerRecord<>("t", "k", "v");
            record.headers().add("X-Tenant-Id", "tenant-secret-42".getBytes());
            record.headers().add("Authorization", "Bearer SECRET-TOKEN".getBytes());

            KafkaProducerException ex = new KafkaProducerException(record, "send failed", new RuntimeException());

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(ex));

            // Application-defined headers не попадают.
            assertThat(meta.values())
                    .noneMatch(v -> v.contains("tenant-secret-42"))
                    .noneMatch(v -> v.contains("Bearer SECRET-TOKEN"));
            assertThat(meta)
                    .doesNotContainKey("X-Tenant-Id")
                    .doesNotContainKey("Authorization");
        }
    }
}
