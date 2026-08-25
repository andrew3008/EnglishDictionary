package space.br1440.platform.tracing.api.propagation.control;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Политика принятия решения об исходящей передаче платформенных заголовков на конкретный destination.
 * <p>
 * Каноническая реализация — {@code DefaultOutboundPropagationPolicy} в {@code platform-tracing-otel}.
 * Реализации обязаны быть thread-safe и stateless.
 */
public interface OutboundPropagationPolicy {

    /**
     * Вычисляет решение об исходящей пропагации для данного destination.
     *
     * @param destination hostname или имя topic; {@code null} возвращает {@link OutboundPropagationDecision#DENY_ALL}
     * @return решение; никогда не {@code null}
     */
    @Nonnull
    OutboundPropagationDecision decide(@Nullable String destination);
}
