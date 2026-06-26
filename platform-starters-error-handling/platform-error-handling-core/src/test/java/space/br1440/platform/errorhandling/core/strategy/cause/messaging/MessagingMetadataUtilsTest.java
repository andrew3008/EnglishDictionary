package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.GenericMessage;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MessagingMetadataUtils")
public class MessagingMetadataUtilsTest {

    @Nested
    @DisplayName("isNetworkFailure")
    class IsNetworkFailure {

        @Test
        void returnsTrueForConnectException() {
            assertThat(MessagingMetadataUtils.isNetworkFailure(new ConnectException("refused"))).isTrue();
        }

        @Test
        void returnsTrueForUnknownHostException() {
            assertThat(MessagingMetadataUtils.isNetworkFailure(new UnknownHostException("kafka"))).isTrue();
        }

        @Test
        void returnsTrueForSocketTimeoutException() {
            assertThat(MessagingMetadataUtils.isNetworkFailure(new SocketTimeoutException("read timeout"))).isTrue();
        }

        @Test
        void returnsFalseForOtherExceptions() {
            assertThat(MessagingMetadataUtils.isNetworkFailure(new RuntimeException("oops"))).isFalse();
        }

        @Test
        void returnsFalseForNull() {
            assertThat(MessagingMetadataUtils.isNetworkFailure(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("unwrapNetworkCause")
    class UnwrapNetworkCause {

        @Test
        void findsConnectExceptionAtDepth2() {
            // given
            ConnectException network = new ConnectException("refused");
            RuntimeException wrap1 = new RuntimeException("layer1", network);
            RuntimeException top = new RuntimeException("top", wrap1);

            // when
            Throwable result = MessagingMetadataUtils.unwrapNetworkCause(top, 5);

            // then
            assertThat(result).isSameAs(network);
        }

        @Test
        void returnsOriginalIfNoNetworkInChain() {
            // given
            RuntimeException top = new RuntimeException("nothing network");

            // when
            Throwable result = MessagingMetadataUtils.unwrapNetworkCause(top, 5);

            // then
            assertThat(result).isSameAs(top);
        }

        @Test
        void respectsMaxDepth() {
            // given — сетевая причина глубже, чем maxDepth=1
            ConnectException network = new ConnectException("refused");
            RuntimeException wrap1 = new RuntimeException("layer1", network);
            RuntimeException wrap2 = new RuntimeException("layer2", wrap1);
            RuntimeException top = new RuntimeException("top", wrap2);

            // when
            Throwable result = MessagingMetadataUtils.unwrapNetworkCause(top, 1);

            // then
            assertThat(result).isSameAs(top);
        }

        @Test
        void doesNotInfiniteLoopOnSelfReference() {
            // given — рукотворный self-loop в cause chain
            RuntimeException self = new RuntimeException("self-loop") {
                @Override public Throwable getCause() { return this; }
            };

            // when
            Throwable result = MessagingMetadataUtils.unwrapNetworkCause(self, 10);

            // then
            assertThat(result).isSameAs(self);
        }
    }

    @Nested
    @DisplayName("isInstanceOfAny")
    class IsInstanceOfAny {

        @Test
        void returnsTrueForMatchingClass() {
            assertThat(MessagingMetadataUtils.isInstanceOfAny("hello", Number.class, CharSequence.class)).isTrue();
        }

        @Test
        void returnsFalseForNoMatch() {
            assertThat(MessagingMetadataUtils.isInstanceOfAny("hello", Number.class, Boolean.class)).isFalse();
        }

        @Test
        void ignoresNullClasses() {
            assertThat(MessagingMetadataUtils.isInstanceOfAny("hello", (Class<?>[]) null)).isFalse();
            assertThat(MessagingMetadataUtils.isInstanceOfAny("hello", null, String.class)).isTrue();
        }
    }

    @Nested
    @DisplayName("extractAuthMechanism")
    class ExtractAuthMechanism {

        @Test
        void extractsSaslMechanism() {
            assertThat(MessagingMetadataUtils.extractAuthMechanism("Auth failed via SASL/PLAIN"))
                    .isEqualTo("SASL");
        }

        @Test
        void extractsScramMechanism() {
            assertThat(MessagingMetadataUtils.extractAuthMechanism("SCRAM-SHA-256 negotiation failed"))
                    .isEqualTo("SCRAM");
        }

        @Test
        void extractsOauthbearerMechanism() {
            assertThat(MessagingMetadataUtils.extractAuthMechanism("OAUTHBEARER token expired"))
                    .isEqualTo("OAUTHBEARER");
        }

        @Test
        void returnsNullForBlankInput() {
            assertThat(MessagingMetadataUtils.extractAuthMechanism(null)).isNull();
            assertThat(MessagingMetadataUtils.extractAuthMechanism("")).isNull();
            assertThat(MessagingMetadataUtils.extractAuthMechanism("   ")).isNull();
        }

        @Test
        void returnsNullForUnknownMechanism() {
            assertThat(MessagingMetadataUtils.extractAuthMechanism("some unrelated error")).isNull();
        }
    }

    @Nested
    @DisplayName("extractTransactionalId")
    class ExtractTransactionalId {

        @Test
        void extractsBasicId() {
            assertThat(MessagingMetadataUtils.extractTransactionalId(
                    "Producer transactional.id=order-tx-1 was fenced"))
                    .isEqualTo("order-tx-1");
        }

        @Test
        void extractsWithColon() {
            assertThat(MessagingMetadataUtils.extractTransactionalId(
                    "transactional.id: my.producer.id"))
                    .isEqualTo("my.producer.id");
        }

        @Test
        void returnsNullIfPatternMissing() {
            assertThat(MessagingMetadataUtils.extractTransactionalId("plain message"))
                    .isNull();
        }

        @Test
        void returnsNullForBlankInput() {
            assertThat(MessagingMetadataUtils.extractTransactionalId(null)).isNull();
            assertThat(MessagingMetadataUtils.extractTransactionalId("")).isNull();
        }
    }

    @Nested
    @DisplayName("KAFKA_SAFE_HEADERS / KAFKA_HEADER_TO_LOGICAL_KEY")
    class KafkaWhitelist {

        @Test
        void containsCoreCoordinates() {
            assertThat(MessagingMetadataUtils.KAFKA_SAFE_HEADERS)
                    .contains(KafkaHeaders.RECEIVED_TOPIC,
                              KafkaHeaders.RECEIVED_PARTITION,
                              KafkaHeaders.OFFSET,
                              KafkaHeaders.GROUP_ID,
                              KafkaHeaders.DELIVERY_ATTEMPT);
        }

        @Test
        void logicalMapping_topicMapsToTopic() {
            Map<String, String> mapping = MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY;
            assertThat(mapping.get(KafkaHeaders.RECEIVED_TOPIC)).isEqualTo("topic");
            assertThat(mapping.get(KafkaHeaders.RECEIVED_PARTITION)).isEqualTo("partition");
            assertThat(mapping.get(KafkaHeaders.OFFSET)).isEqualTo("offset");
            assertThat(mapping.get(KafkaHeaders.GROUP_ID)).isEqualTo("consumerGroup");
            assertThat(mapping.get(KafkaHeaders.DELIVERY_ATTEMPT)).isEqualTo("deliveryAttempt");
        }
    }

    @Nested
    @DisplayName("RABBIT_SAFE_HEADERS / RABBIT_HEADER_TO_LOGICAL_KEY")
    class RabbitWhitelist {

        @Test
        void containsCoreCoordinates() {
            assertThat(MessagingMetadataUtils.RABBIT_SAFE_HEADERS)
                    .contains(AmqpHeaders.DELIVERY_TAG,
                              AmqpHeaders.REDELIVERED,
                              AmqpHeaders.RECEIVED_EXCHANGE,
                              AmqpHeaders.RECEIVED_ROUTING_KEY,
                              AmqpHeaders.CONSUMER_QUEUE);
        }

        @Test
        void logicalMapping_queueMapsToQueue() {
            Map<String, String> mapping = MessagingMetadataUtils.RABBIT_HEADER_TO_LOGICAL_KEY;
            assertThat(mapping.get(AmqpHeaders.CONSUMER_QUEUE)).isEqualTo("queue");
            assertThat(mapping.get(AmqpHeaders.RECEIVED_ROUTING_KEY)).isEqualTo("routingKey");
            assertThat(mapping.get(AmqpHeaders.RECEIVED_EXCHANGE)).isEqualTo("exchange");
            assertThat(mapping.get(AmqpHeaders.DELIVERY_TAG)).isEqualTo("deliveryTag");
            assertThat(mapping.get(AmqpHeaders.REDELIVERED)).isEqualTo("redelivered");
        }
    }

    @Nested
    @DisplayName("SPRING_MSG_SAFE_HEADERS")
    class SpringMessagingWhitelist {

        @Test
        void containsExactlyThreeSafeHeaders() {
            assertThat(MessagingMetadataUtils.SPRING_MSG_SAFE_HEADERS)
                    .containsExactlyInAnyOrder("id", "timestamp", "contentType");
        }

        @Test
        void doesNotContainApplicationDefinedHeaders() {
            assertThat(MessagingMetadataUtils.SPRING_MSG_SAFE_HEADERS)
                    .doesNotContain("authorization", "x-tenant-id", "user-context", "correlation-id");
        }
    }

    @Nested
    @DisplayName("enrichWithMessageHeaders — kafka")
    class EnrichKafkaHeaders {

        @Test
        void extractsAllWhitelistedKafkaHeaders() {
            // given
            Map<String, Object> rawHeaders = new HashMap<>();
            rawHeaders.put(KafkaHeaders.RECEIVED_TOPIC, "orders");
            rawHeaders.put(KafkaHeaders.RECEIVED_PARTITION, 3);
            rawHeaders.put(KafkaHeaders.OFFSET, 12345L);
            rawHeaders.put(KafkaHeaders.GROUP_ID, "order-group");
            rawHeaders.put(KafkaHeaders.DELIVERY_ATTEMPT, 2);
            rawHeaders.put("custom-application-header", "should-be-ignored");

            FakeFailedMessageException ex = new FakeFailedMessageException(rawHeaders);

            // when
            Map<String, String> collected = new LinkedHashMap<>();
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    ex,
                    MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY,
                    collected::put);

            // then
            assertThat(collected)
                    .containsEntry("topic", "orders")
                    .containsEntry("partition", "3")
                    .containsEntry("offset", "12345")
                    .containsEntry("consumerGroup", "order-group")
                    .containsEntry("deliveryAttempt", "2");
            assertThat(collected).doesNotContainKey("custom-application-header");
            assertThat(collected).doesNotContainKey(KafkaHeaders.RECEIVED_TOPIC);
        }

        @Test
        void handlesMissingFailedMessageGracefully() {
            // given — исключение без метода getFailedMessage()
            RuntimeException ex = new RuntimeException("no failed message accessor");

            // when / then — не должно бросить
            Map<String, String> collected = new LinkedHashMap<>();
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    ex,
                    MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY,
                    collected::put);
            assertThat(collected).isEmpty();
        }

        @Test
        void handlesNullFailedMessage() {
            // given
            FakeFailedMessageException ex = new FakeFailedMessageException(null);

            // when
            Map<String, String> collected = new LinkedHashMap<>();
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    ex,
                    MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY,
                    collected::put);

            // then
            assertThat(collected).isEmpty();
        }
    }

    @Nested
    @DisplayName("enrichWithMessageHeaders — rabbit")
    class EnrichRabbitHeaders {

        @Test
        void extractsAllWhitelistedRabbitHeaders() {
            // given
            Map<String, Object> rawHeaders = new HashMap<>();
            rawHeaders.put(AmqpHeaders.CONSUMER_QUEUE, "orders.queue");
            rawHeaders.put(AmqpHeaders.RECEIVED_ROUTING_KEY, "order.created");
            rawHeaders.put(AmqpHeaders.RECEIVED_EXCHANGE, "order.exchange");
            rawHeaders.put(AmqpHeaders.DELIVERY_TAG, 42L);
            rawHeaders.put(AmqpHeaders.REDELIVERED, false);

            FakeFailedMessageException ex = new FakeFailedMessageException(rawHeaders);

            // when
            Map<String, String> collected = new LinkedHashMap<>();
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    ex,
                    MessagingMetadataUtils.RABBIT_HEADER_TO_LOGICAL_KEY,
                    collected::put);

            // then
            assertThat(collected)
                    .containsEntry("queue", "orders.queue")
                    .containsEntry("routingKey", "order.created")
                    .containsEntry("exchange", "order.exchange")
                    .containsEntry("deliveryTag", "42")
                    .containsEntry("redelivered", "false");
        }
    }

    @Nested
    @DisplayName("enrichWithMessageHeaders — value stringification contract")
    class StringificationContract {

        @Test
        void uuidIsStringified() {
            // given
            UUID id = UUID.randomUUID();
            Map<String, Object> rawHeaders = new HashMap<>();
            rawHeaders.put(KafkaHeaders.RECEIVED_TOPIC, id);
            FakeFailedMessageException ex = new FakeFailedMessageException(rawHeaders);

            // when
            Map<String, String> collected = new LinkedHashMap<>();
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    ex,
                    MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY,
                    collected::put);

            // then
            assertThat(collected.get("topic")).isEqualTo(id.toString());
        }

        @Test
        void blankValueIsFiltered() {
            Map<String, Object> rawHeaders = new HashMap<>();
            rawHeaders.put(KafkaHeaders.RECEIVED_TOPIC, "");
            rawHeaders.put(KafkaHeaders.OFFSET, "   ");
            FakeFailedMessageException ex = new FakeFailedMessageException(rawHeaders);

            Map<String, String> collected = new LinkedHashMap<>();
            MessagingMetadataUtils.enrichWithMessageHeaders(
                    ex,
                    MessagingMetadataUtils.KAFKA_HEADER_TO_LOGICAL_KEY,
                    collected::put);

            assertThat(collected).isEmpty();
        }
    }

    /**
     * Минимальная заглушка исключения с методом {@code getFailedMessage()},
     * как у {@code MessageHandlingException}/{@code MessageDeliveryException}.
     * Позволяет тестировать reflection-извлечение headers без зависимости
     * от реальных Spring-классов в каждом сценарии.
     */
    public static final class FakeFailedMessageException extends RuntimeException {

        private final Message<?> failedMessage;

        public FakeFailedMessageException(Map<String, Object> headers) {
            super("synthetic");
            // GenericMessage напрямую — без MessageBuilder, чтобы исключить
            // любые перетасовки id/timestamp и гарантировать, что raw-headers
            // (включая kafka_*/amqp_*-заголовки) попадают в Message.getHeaders() 1:1.
            this.failedMessage = (headers != null)
                    ? new GenericMessage<>("payload", headers)
                    : null;
        }

        @SuppressWarnings("unused")
        public Message<?> getFailedMessage() {
            return failedMessage;
        }
    }
}
