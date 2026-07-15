package space.br1440.platform.tracing.api.propagation.control;

/**
 * Политика принятия решения об исходящей передаче платформенных заголовков на конкретный destination.
 * <p>
 * Реализация: {@code space.br1440.platform.tracing.core.propagation.control.DefaultOutboundPropagationPolicy}.
 */
public interface OutboundPropagationPolicy {

    /**
     * Принимает решение о распространении платформенных заголовков для указанного destination.
     *
     * @param destination HTTP-хост или Kafka-топик
     * @return решение о распространении; никогда не {@code null}
     */
    OutboundPropagationDecision decide(String destination);
}
