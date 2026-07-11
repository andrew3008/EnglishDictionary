package space.br1440.platform.tracing.api.propagation.control;

import io.opentelemetry.context.ContextKey;
import lombok.experimental.UtilityClass;

/**
 * Ключи для хранения платформенных управляющих структур в OpenTelemetry {@link io.opentelemetry.context.Context}.
 */
@UtilityClass
public final class PlatformTraceContextKeys {

    /**
     * Ключ для хранения входящего сигнала {@link InboundTraceControl}, извлечённого из carrier'а.
     */
    public static final ContextKey<InboundTraceControl> TRACE_CONTROL = ContextKey.named("platform-trace-control");

    /**
     * Ключ для хранения решения об исходящей передаче {@link OutboundPropagationDecision}, принятого client interceptor'ом.
     */
    public static final ContextKey<OutboundPropagationDecision> PROPAGATION_DECISION = ContextKey.named("platform-propagation-decision");

}
