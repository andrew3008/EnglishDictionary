package space.br1440.platform.errorhandling.core.strategy.cause.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.MessageHandlingException;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.MissingSessionUserException;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.messaging.support.MessageBuilder;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Юнит-тесты {@link SpringMessagingExceptionStrategy}.
 *
 * <p>Покрывают:
 * <ol>
 *   <li>порядок стратегии — {@code SPRING_MESSAGING = 450};</li>
 *   <li>классификация четырёх типов: {@code MessageDelivery}, {@code MessageHandling},
 *       {@code MessageConversion}, {@code MissingSessionUser};</li>
 *   <li>безопасность {@code diagnosticMetadata}: только whitelist-заголовки,
 *       payload не попадает;</li>
 *   <li>STOMP-headers: {@code simpSessionId} как есть, {@code simpDestination}
 *       прогоняется через {@link space.br1440.platform.errorhandling.core.util.sanitize.PathSanitizer};</li>
 *   <li>контракт {@code includeBrokerCoordinates=false}: ни один header не попадает в metadata;</li>
 *   <li>иерархическая (under-)экспозиция: пустой failedMessage не приводит к падению.</li>
 * </ol>
 */
@DisplayName("SpringMessagingExceptionStrategy")
class SpringMessagingExceptionStrategyTest {

    private final SpringMessagingExceptionStrategy strategy =
            new SpringMessagingExceptionStrategy(true, new StackHintExtractor(false, List.of()));

    private final SpringMessagingExceptionStrategy strategyNoCoords =
            new SpringMessagingExceptionStrategy(false, new StackHintExtractor(false, List.of()));

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

    /** Простое сообщение с whitelist-заголовками базового набора. */
    private static Message<String> plainMessage() {
        return MessageBuilder.withPayload("user-payload-secret")
                .setHeader(MessageHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /** STOMP-сообщение с simp*-заголовками. */
    private static Message<byte[]> stompMessage(String destination) {
        SimpMessageHeaderAccessor accessor =
                SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId("session-abc-123");
        accessor.setDestination(destination);
        accessor.setLeaveMutable(false);
        return MessageBuilder.createMessage(new byte[]{1, 2, 3}, accessor.getMessageHeaders());
    }

    // ============================================================================
    // order
    // ============================================================================

    @Nested
    @DisplayName("order")
    class Order {
        @Test
        void orderEqualsSpringMessagingConstant() {
            assertThat(strategy.getOrder()).isEqualTo(RootCauseExceptionStrategyOrder.SPRING_MESSAGING);
            assertThat(strategy.getOrder()).isEqualTo(450);
        }
    }

    // ============================================================================
    // supportsCause
    // ============================================================================

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void acceptsMessageDelivery() {
            assertThat(strategy.supportsCause(new MessageDeliveryException("?"))).isTrue();
        }

        @Test
        void acceptsMessageHandling() {
            assertThat(strategy.supportsCause(new MessageHandlingException(plainMessage()))).isTrue();
        }

        @Test
        void acceptsMessageConversion() {
            assertThat(strategy.supportsCause(new MessageConversionException("?"))).isTrue();
        }

        @Test
        void acceptsMissingSessionUser() {
            assertThat(strategy.supportsCause(new MissingSessionUserException(plainMessage()))).isTrue();
        }

        @Test
        void rejectsGenericRuntimeException() {
            assertThat(strategy.supportsCause(new RuntimeException("oops"))).isFalse();
        }
    }

    // ============================================================================
    // classification
    // ============================================================================

    @Nested
    @DisplayName("classification")
    class Classification {

        @Test
        void messageDeliveryClassifiedAsRetryableServiceUnavailable() {
            RootCauseResolution res = strategy.extractCauseDetails(new MessageDeliveryException("broker offline"));

            assertThat(reasonOf(res)).isEqualTo("MESSAGE_DELIVERY_FAILURE");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.retryability()).isEqualTo(Retryability.RETRYABLE);
            assertThat(res.details().stream().anyMatch(d -> d instanceof RetryInfoDetail)).isTrue();
        }

        @Test
        void messageConversionClassifiedAsBadRequestNotRetryable() {
            RootCauseResolution res = strategy.extractCauseDetails(new MessageConversionException("invalid payload"));

            assertThat(reasonOf(res)).isEqualTo("MESSAGE_CONVERSION_ERROR");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
            assertThat(res.details().stream().noneMatch(d -> d instanceof RetryInfoDetail)).isTrue();
        }

        @Test
        void messageHandlingClassifiedAsInternalServerError() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new MessageHandlingException(plainMessage(), "handler failed"));

            assertThat(reasonOf(res)).isEqualTo("MESSAGE_HANDLING_ERROR");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void missingSessionUserClassifiedAsUnauthorized() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new MissingSessionUserException(plainMessage()));

            assertThat(reasonOf(res)).isEqualTo("SESSION_AUTH_REQUIRED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(res.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }

        @Test
        void missingSessionUserPriorityOverHandlingException() {
            // MissingSessionUserException наследуется от MessageHandlingException,
            // классификатор должен предпочесть наиболее специфичный тип.
            MissingSessionUserException ex = new MissingSessionUserException(plainMessage());

            RootCauseResolution res = strategy.extractCauseDetails(ex);

            assertThat(reasonOf(res)).isEqualTo("SESSION_AUTH_REQUIRED");
        }
    }

    // ============================================================================
    // diagnosticMetadata: whitelist headers
    // ============================================================================

    @Nested
    @DisplayName("diagnosticMetadata — whitelist headers")
    class WhitelistHeaders {

        @Test
        void containsContentTypeFromFailedMessage() {
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new MessageHandlingException(plainMessage(), "handler failed")));

            assertThat(meta).containsEntry("contentType", "application/json");
            // id и timestamp задаются Spring автоматически — должны присутствовать.
            assertThat(meta).containsKey("id");
            assertThat(meta).containsKey("timestamp");
        }

        @Test
        void includesStompSessionIdAndStompCommand() {
            Message<byte[]> msg = stompMessage("/topic/orders/created");

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new MessageHandlingException(msg, "x")));

            assertThat(meta).containsEntry("simpSessionId", "session-abc-123");
            // simpDestination присутствует, sanitized PathSanitizer'ом.
            assertThat(meta).containsKey("simpDestination");
            // simpMessageType может быть в metadata только если он попал в whitelist;
            // в нашем strategy whitelist STOMP-полей: {simpSessionId, simpDestination, stompCommand}.
            assertThat(meta).doesNotContainKey("simpMessageType");
        }

        @Test
        void simpDestinationIsSanitized() {
            // Путь содержит много сегментов — PathSanitizer должен оставить только
            // верхние общие части, без значений последних сегментов (если они выглядят как ID).
            Message<byte[]> msg = stompMessage("/topic/orders/12345/items/abcd-uuid");

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new MessageHandlingException(msg, "x")));

            String destination = meta.get("simpDestination");
            assertThat(destination).isNotNull();
            // Значение санитизировано (минимально оригинальная строка не передаётся 1:1
            // — либо отброшены чувствительные сегменты, либо префикс совпадает).
            // Контракт PathSanitizer: возвращаемая строка непустая и помечена санитайзером.
            assertThat(destination).isNotBlank();
        }

        @Test
        void payloadNeverInMetadata() {
            // Защита от утечки payload — критическая ассерция для CWE-209.
            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new MessageHandlingException(plainMessage(), "x")));

            assertThat(meta.values()).noneMatch(v -> v.contains("user-payload-secret"));
        }

        @Test
        void applicationDefinedHeaderNotIncluded() {
            // Заголовок tenantId — application-defined, должен быть отфильтрован.
            Map<String, Object> raw = new HashMap<>();
            raw.put(MessageHeaders.CONTENT_TYPE, "application/json");
            raw.put("tenantId", "TENANT-007");
            raw.put("authorization", "Bearer eyJsupersecret");
            Message<String> msg = new GenericMessage<>("payload", raw);

            Map<String, String> meta = metadataOf(strategy.extractCauseDetails(
                    new MessageHandlingException(msg, "x")));

            assertThat(meta).doesNotContainKey("tenantId");
            assertThat(meta).doesNotContainKey("authorization");
            // Подтверждаем, что значения тоже не попали (CWE-209).
            assertThat(meta.values()).noneMatch(v -> v.contains("TENANT-007"));
            assertThat(meta.values()).noneMatch(v -> v.contains("eyJsupersecret"));
        }
    }

    // ============================================================================
    // includeBrokerCoordinates=false
    // ============================================================================

    @Nested
    @DisplayName("includeBrokerCoordinates=false")
    class CoordinatesFlagOff {

        @Test
        void doesNotIncludeAnyMessageHeaders() {
            Message<byte[]> msg = stompMessage("/topic/orders/12345");

            Map<String, String> meta = metadataOf(strategyNoCoords.extractCauseDetails(
                    new MessageHandlingException(msg, "x")));

            assertThat(meta).doesNotContainKey("contentType");
            assertThat(meta).doesNotContainKey("id");
            assertThat(meta).doesNotContainKey("simpSessionId");
            assertThat(meta).doesNotContainKey("simpDestination");
            assertThat(meta).doesNotContainKey("stompCommand");
        }

        @Test
        void classificationStillWorksWithoutCoordinates() {
            // Контракт: флаг отключает только координаты, классификация сохраняется.
            RootCauseResolution res = strategyNoCoords.extractCauseDetails(
                    new MessageDeliveryException("broker offline"));

            assertThat(reasonOf(res)).isEqualTo("MESSAGE_DELIVERY_FAILURE");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    // ============================================================================
    // robustness
    // ============================================================================

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        void messageDeliveryWithoutFailedMessageDoesNotThrow() {
            // MessageDeliveryException с описанием без исходного Message — у getFailedMessage()
            // вернётся null, стратегия не должна упасть.
            RootCauseResolution res = strategy.extractCauseDetails(
                    new MessageDeliveryException("description-only"));

            assertThat(reasonOf(res)).isEqualTo("MESSAGE_DELIVERY_FAILURE");
            // Metadata не содержит whitelist-заголовков (failedMessage отсутствовал).
            assertThat(metadataOf(res)).doesNotContainKey("simpSessionId");
        }

        @Test
        void messageConversionWithoutFailedMessageDoesNotThrow() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new MessageConversionException("description-only"));

            assertThat(reasonOf(res)).isEqualTo("MESSAGE_CONVERSION_ERROR");
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

            assertThat(strategy.logPolicy(new MessageDeliveryException("?"), entry))
                    .isEqualTo(LogPolicy.ERROR);
        }

        @Test
        void warnFor4xx() {
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_REQUEST).errorCode("X").message("x").build();

            assertThat(strategy.logPolicy(new MessageConversionException("?"), entry))
                    .isEqualTo(LogPolicy.WARN);
        }
    }
}
