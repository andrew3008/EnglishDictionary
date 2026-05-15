package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.AMQImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.AmqpResourceNotAvailableException;
import org.springframework.amqp.AmqpTimeoutException;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RabbitMqExceptionStrategy")
class RabbitMqExceptionStrategyTest {

    private final RabbitMqExceptionStrategy strategy =
            new RabbitMqExceptionStrategy(true, new StackHintExtractor(false, List.of()));

    private final RabbitMqExceptionStrategy strategyNoCoords =
            new RabbitMqExceptionStrategy(false, new StackHintExtractor(false, List.of()));

    private static ShutdownSignalException shutdownSignal(int replyCode, String replyText) {
        AMQImpl.Connection.Close reason = new AMQImpl.Connection.Close(replyCode, replyText, 0, 0);
        return new ShutdownSignalException(true, false, reason, /*ref=*/ "test-ref");
    }

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
        void orderEqualsRabbitMqConstant() {
            assertThat(strategy.getOrder()).isEqualTo(RootCauseExceptionStrategyOrder.RABBITMQ);
            assertThat(strategy.getOrder()).isEqualTo(445);
        }
    }

    // ============================================================================
    // supportsCause
    // ============================================================================

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsAmqpException() {
            assertThat(strategy.supportsCause(new AmqpException("base"))).isTrue();
        }

        @Test
        void acceptsAmqpAuthenticationException() {
            assertThat(strategy.supportsCause(
                    new AmqpAuthenticationException(new RuntimeException("bad creds")))).isTrue();
        }

        @Test
        void acceptsAmqpConnectException() {
            assertThat(strategy.supportsCause(new AmqpConnectException(new IOException("io")))).isTrue();
        }

        @Test
        void acceptsAmqpRejectAndDontRequeueException() {
            assertThat(strategy.supportsCause(new AmqpRejectAndDontRequeueException("DLQ"))).isTrue();
        }

        @Test
        void acceptsRawShutdownSignalException() {
            assertThat(strategy.supportsCause(shutdownSignal(404, "NOT_FOUND - no queue 'orders'"))).isTrue();
        }

        @Test
        void rejectsGenericException() {
            assertThat(strategy.supportsCause(new RuntimeException("oops"))).isFalse();
        }
    }

    // ============================================================================
    // Spring AMQP type-safe classification
    // ============================================================================

    @Nested
    @DisplayName("classification — Spring AMQP type-safe")
    class ClassificationSpringAmqp {

        @Test
        void amqpAuthenticationExceptionClassifiedAsAuthFailure() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new AmqpAuthenticationException(new RuntimeException("bad")));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("AUTH_FAILURE");
        }

        @Test
        void rejectAndDontRequeueClassifiedAsRejectNoRequeue() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new AmqpRejectAndDontRequeueException("dead-letter"));

            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(reasonOf(res)).isEqualTo("REJECT_NO_REQUEUE");
        }

        @Test
        void amqpResourceNotAvailableClassifiedCorrectly() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new AmqpResourceNotAvailableException("channel cache exhausted"));

            assertThat(reasonOf(res)).isEqualTo("RESOURCE_NOT_AVAILABLE");
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void amqpConnectExceptionClassifiedAsConnectionFailure() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new AmqpConnectException(new IOException("connection refused")));

            assertThat(reasonOf(res)).isEqualTo("CONNECTION_FAILURE");
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void amqpTimeoutExceptionClassifiedAsTimeout() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new AmqpTimeoutException(new IOException("timeout")));

            assertThat(reasonOf(res)).isEqualTo("TIMEOUT");
        }

        @Test
        void plainAmqpExceptionFallsToGeneric() {
            RootCauseResolution res = strategy.extractCauseDetails(new AmqpException("?"));

            assertThat(reasonOf(res)).isEqualTo("GENERIC");
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }
    }

    // ============================================================================
    // Reply code classification (AMQP 0-9-1)
    // ============================================================================

    @Nested
    @DisplayName("classification — AMQP reply code")
    class ClassificationByReplyCode {

        @Test
        void replyCode320classifiedAsConnectionForced() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(320, "CONNECTION_FORCED - broker maintenance"));

            assertThat(reasonOf(res)).isEqualTo("CONNECTION_FORCED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void replyCode403classifiedAsAccessRefused() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(403, "ACCESS_REFUSED - operation not permitted"));

            assertThat(reasonOf(res)).isEqualTo("ACCESS_REFUSED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void replyCode404classifiedAsResourceNotFound() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(404, "NOT_FOUND - no queue 'orders'"));

            assertThat(reasonOf(res)).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void replyCode405classifiedAsResourceLocked() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(405, "RESOURCE_LOCKED"));

            assertThat(reasonOf(res)).isEqualTo("RESOURCE_LOCKED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void replyCode406classifiedAsPreconditionFailed() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(406, "PRECONDITION_FAILED - inequivalent arg"));

            assertThat(reasonOf(res)).isEqualTo("PRECONDITION_FAILED");
        }

        @Test
        void replyCode506classifiedAsBrokerResourceError() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(506, "RESOURCE_ERROR - memory alarm"));

            assertThat(reasonOf(res)).isEqualTo("BROKER_RESOURCE_ERROR");
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void replyCode530classifiedAsAccessRefused() {
            // AMQP 530 not-allowed — semantically такой же AccessRefused.
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(530, "NOT_ALLOWED - vhost forbidden"));

            assertThat(reasonOf(res)).isEqualTo("ACCESS_REFUSED");
        }

        @Test
        void replyCode541classifiedAsBrokerInternalError() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(541, "INTERNAL_ERROR"));

            assertThat(reasonOf(res)).isEqualTo("BROKER_INTERNAL_ERROR");
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void replyCode502classifiedAsProtocolViolation() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(502, "SYNTAX_ERROR"));

            assertThat(reasonOf(res)).isEqualTo("PROTOCOL_VIOLATION");
        }

        @Test
        void unknownReplyCodeFallsToGeneric() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    shutdownSignal(999, "VENDOR_SPECIFIC_UNKNOWN"));

            assertThat(reasonOf(res)).isEqualTo("GENERIC");
        }
    }

    // ============================================================================
    // Cause chain traversal
    // ============================================================================

    @Nested
    @DisplayName("cause chain traversal")
    class CauseChainTraversal {

        @Test
        void detectsShutdownSignalInsideAmqpException() {
            // AmqpException → ShutdownSignalException (нативное rabbitmq).
            ShutdownSignalException sse = shutdownSignal(404, "NOT_FOUND - queue missing");
            AmqpException wrapped = new AmqpException("operation failed", sse);

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(metadataOf(res)).containsEntry("amqpReplyCode", "404");
        }

        @Test
        void detectsConnectExceptionInCauseChain() {
            AmqpException wrapped = new AmqpException(
                    "io", new RuntimeException("layer", new ConnectException("refused")));

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("CONNECTION_FAILURE");
        }

        @Test
        void detectsSocketTimeoutAsTimeout() {
            AmqpException wrapped = new AmqpException("io", new SocketTimeoutException("read"));

            RootCauseResolution res = strategy.extractCauseDetails(wrapped);

            assertThat(reasonOf(res)).isEqualTo("TIMEOUT");
        }

        @Test
        void springTypeWinsOverReplyCode() {
            // AmqpAuthenticationException перекрывает любой reply code.
            AmqpAuthenticationException ex = new AmqpAuthenticationException(
                    shutdownSignal(530, "NOT_ALLOWED"));

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(reasonOf(res)).isEqualTo("AUTH_FAILURE");
        }
    }

    // ============================================================================
    // GENERIC retryability
    // ============================================================================

    @Nested
    @DisplayName("GENERIC retryability invariant")
    class GenericRetryability {

        @Test
        void genericIsNotRetryable() {
            RootCauseResolution res = strategy.extractCauseDetails(new AmqpException("?"));

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
        void containsBrokerTypeAndExceptionClassAlways() {
            Map<String, String> meta = metadataOf(
                    strategy.extractCauseDetails(new AmqpException("x")));

            assertThat(meta).containsEntry("brokerType", "RABBIT");
            assertThat(meta).containsEntry("exceptionClass", AmqpException.class.getName());
        }

        @Test
        void includesAmqpReplyCodeAndText() {
            Map<String, String> meta = metadataOf(
                    strategy.extractCauseDetails(shutdownSignal(404, "NOT_FOUND")));

            assertThat(meta).containsEntry("amqpReplyCode", "404");
            assertThat(meta.get("amqpReplyText")).contains("NOT_FOUND");
        }

        @Test
        void replyTextTruncatedTo200Chars() {
            // Утилита truncate добавляет суффикс "[TRUNCATED]" к строке длины 200,
            // поэтому проверяем не общую длину результата, а длину префикса исходной строки
            // и наличие маркера усечения.
            String longText = "X".repeat(500);
            Map<String, String> meta = metadataOf(
                    strategy.extractCauseDetails(shutdownSignal(404, longText)));

            String replyText = meta.get("amqpReplyText");
            assertThat(replyText).startsWith("X".repeat(200));
            assertThat(replyText).contains("[TRUNCATED]");
            assertThat(replyText.length()).isLessThan(longText.length());
        }

        @Test
        void includesNetworkCauseFieldsForConnectionFailure() {
            AmqpException wrapped = new AmqpException("net", new ConnectException("connection refused"));

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(wrapped));

            assertThat(meta).containsEntry("networkCauseClass", ConnectException.class.getName());
            assertThat(meta.get("networkCauseMessage")).contains("connection refused");
        }
    }

    // ============================================================================
    // includeBrokerCoordinates=false: queue для REJECT_NO_REQUEUE
    // ============================================================================

    @Nested
    @DisplayName("includeBrokerCoordinates=false invariants")
    class CoordinatesFlagInvariants {

        @Test
        void coordinatesFlagOff_doesNotIncludeBrokerHeaders() {
            // Без координат: при отсутствии failedMessage в простом AmqpException
            // никаких queue/routingKey/exchange в metadata быть не должно.
            Map<String, String> meta = metadataOf(
                    strategyNoCoords.extractCauseDetails(new AmqpException("?")));

            assertThat(meta).doesNotContainKey("queue");
            assertThat(meta).doesNotContainKey("routingKey");
            assertThat(meta).doesNotContainKey("exchange");
            // Базовые поля всё равно есть.
            assertThat(meta).containsEntry("brokerType", "RABBIT");
        }

        @Test
        void coordinatesFlagOff_butRejectNoRequeue_stillTriesToExtractQueue() {
            // Контракт: REJECT_NO_REQUEUE — единственный сценарий, в котором утилита
            // попытается извлечь queue даже при флаге false. Поскольку у нашего
            // AmqpRejectAndDontRequeueException нет failedMessage, queue в metadata
            // не появится, но классификация и базовые поля корректны.
            RootCauseResolution res = strategyNoCoords.extractCauseDetails(
                    new AmqpRejectAndDontRequeueException("DLQ"));

            assertThat(reasonOf(res)).isEqualTo("REJECT_NO_REQUEUE");
            // queue отсутствует, потому что headers нет; контракт не нарушен.
            assertThat(metadataOf(res)).doesNotContainKey("queue");
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
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE).errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(new AmqpException("x"), entry)).isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void warnFor4xx() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.NOT_FOUND).errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(shutdownSignal(404, "x"), entry)).isEqualTo(LogPolicy.WARN);
        }
    }
}
