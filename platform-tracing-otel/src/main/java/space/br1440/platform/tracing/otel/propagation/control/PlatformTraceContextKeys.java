package space.br1440.platform.tracing.otel.propagation.control;

import io.opentelemetry.context.ContextKey;
import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;

/**
 * Внутренние OTel ContextKey платформенного control-plane.
 */
@UtilityClass
public final class PlatformTraceContextKeys {

    public static final ContextKey<InboundTraceControl> TRACE_CONTROL =
            ContextKey.named("platform-trace-control");

    public static final ContextKey<OutboundPropagationDecision> PROPAGATION_DECISION =
            ContextKey.named("platform-propagation-decision");

}
