package space.br1440.platform.tracing.api.propagation.control;

/**
 * Извлечение {@link InboundTraceControl} из сырых значений carrier (HTTP/Kafka заголовков).
 * <p>
 * Каноническая реализация — {@code DefaultInboundTraceControlExtractor} в {@code platform-tracing-core}.
 */
public interface InboundTraceControlExtractor {

    InboundTraceControl fromHeaders(String traceOn, String qaTrace, String requestId);
}
