package space.br1440.platform.tracing.runtime.propagation.control;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

import io.opentelemetry.context.ContextKey;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformTraceContextKeys {

    public static final ContextKey<InboundTraceControl> TRACE_CONTROL = ContextKey.named("platform-trace-control");

}
