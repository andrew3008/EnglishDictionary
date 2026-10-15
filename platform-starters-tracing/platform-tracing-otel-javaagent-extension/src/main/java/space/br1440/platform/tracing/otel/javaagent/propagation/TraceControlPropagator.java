package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Collection;
import java.util.List;
import jakarta.annotation.Nonnull;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;

final class TraceControlPropagator implements TextMapPropagator {

    private final Collection<String> fields;
    private final InboundTraceControlExtractor inboundExtractor;
    private final PlatformPropagationGate gate;

    TraceControlPropagator() {
        this(PlatformPropagationGate.shared());
    }

    TraceControlPropagator(PlatformPropagationGate gate) {
        this.fields = List.of(PlatformHeaders.X_TRACE_ON, PlatformHeaders.X_QA_TRACE, PlatformHeaders.X_REQUEST_ID);
        this.inboundExtractor = new DefaultInboundTraceControlExtractor();
        this.gate = gate;
    }

    @Override
    public Collection<String> fields() {
        return fields;
    }

    @Override
    public <C> Context extract(@Nonnull Context context, C carrier, @Nonnull TextMapGetter<C> getter) {
        if (!gate.isEnabled()) {
            return context;
        }

        String forceTraceVal = getter.get(carrier, PlatformHeaders.X_TRACE_ON);
        String qaTraceVal = getter.get(carrier, PlatformHeaders.X_QA_TRACE);
        if (StringUtils.isNullOrBlank(forceTraceVal) && StringUtils.isNullOrBlank(qaTraceVal)) {
            return context;
        }

        InboundTraceControl control = inboundExtractor.fromHeaders(forceTraceVal, qaTraceVal, null);
        return context.with(PlatformTraceContextKeys.TRACE_CONTROL, control);
    }

    @Override
    public <C> void inject(@Nonnull Context context, C carrier, @Nonnull TextMapSetter<C> setter) {
        // Преднамеренно no-op: исходящая (outbound) инъекция платформенных заголовков принадлежит
        // app-plane порту PlatformOutboundPropagation.resolve(decision, control),
        // а не agent-plane пропагатору.
    }
}
