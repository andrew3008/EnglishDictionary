package space.br1440.platform.tracing.api.propagation.control;

/**
 * Решение о распространении (propagation) платформенных заголовков на исходящем запросе.
 * <p>
 * Принимается на стороне клиента (например, в interceptor'е HTTP-клиента или Kafka producer'е)
 * на основе целевого адреса назначения (destination).
 */
public record PlatformPropagationDecision(boolean propagateForceTrace,
                                          boolean propagateQaTrace,
                                          boolean propagateRequestId) {
    /**
     * Стандартная политика: передавать ничего. Используется для ненадёжных/внешних получателей.
     */
    public static final PlatformPropagationDecision DENY_ALL =
            new PlatformPropagationDecision(false, false, false);

    /**
     * Стандартная политика: передавать всё. Используется для доверенных внутренних сервисов.
     */
    public static final PlatformPropagationDecision ALLOW_ALL =
            new PlatformPropagationDecision(true, true, true);

}
