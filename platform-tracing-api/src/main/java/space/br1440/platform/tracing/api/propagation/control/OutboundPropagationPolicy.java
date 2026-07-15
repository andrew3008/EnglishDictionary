package space.br1440.platform.tracing.api.propagation.control;

/**
 * Политика принятия решения об исходящей передаче платформенных заголовков на конкретный destination.
 * <p>
 * Каноническая реализация — {@code DefaultOutboundPropagationPolicy} в {@code platform-tracing-core}.
 */
public interface OutboundPropagationPolicy {

    OutboundPropagationDecision decide(String destination);
}
