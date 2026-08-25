package space.br1440.platform.tracing.api.propagation.control;

/**
 * Параметры управления распределённой трассировкой, извлечённые из carrier
 * (HTTP-заголовков, Kafka-заголовков) при входящем запросе.
 * <p>
 * Создание — через {@code InboundTraceControlExtractor} (реализация в {@code platform-tracing-otel}).
 */
public record InboundTraceControl(boolean forceTrace,
                                  boolean qaTrace,
                                  String requestId,
                                  String samplingReason,
                                  String rawForceTraceValue) {
}
