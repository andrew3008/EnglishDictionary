package space.br1440.platform.errorhandling.exception.internal;

import space.br1440.platform.errorhandling.exception.AbstractErrorResponseException;
import space.br1440.platform.errorhandling.utils.ClientMetadataBuilder;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Сбой обработки сообщения из брокера (Kafka, RabbitMQ и т.д.) — без HTTP-маркера, fallback 500 в HTTP-pipeline.
 */
public final class MessageProcessingException extends AbstractErrorResponseException {

    private static final String MESSAGE_TEMPLATE = "Failed to process message from {0}: {1}";

    public MessageProcessingException(String topic, String errorDescription) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, topic, errorDescription), buildClientMetadata(topic, errorDescription));
    }

    public MessageProcessingException(String topic, String errorDescription, Throwable cause) {
        super(MessageFormat.format(MESSAGE_TEMPLATE, topic, errorDescription), buildClientMetadata(topic, errorDescription), cause);
    }

    private static Map<String, String> buildClientMetadata(String topic, String errorDescription) {
        return ClientMetadataBuilder.builder()
                .put("topic", topic)
                .put("errorDescription", errorDescription)
                .build();
    }
}
