package space.br1440.platform.tracing.core.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import lombok.RequiredArgsConstructor;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.TraceControlHeaderInjector;

/**
 * Каноническая реализация {@link TraceControlHeaderInjector}.
 * <p>
 * Единый источник истины для outbound-инжекции: и {@code InboundTraceControlPropagator.inject()}
 * (agent classloader), и client-интерсепторы Spring (app classloader) делегируют сюда — это
 * исключает дрейф логики между классами и classloader'ами (см. ADR-outbound-propagation).
 * <p>
 * <b>Secure-by-default:</b> без {@link OutboundPropagationDecision} в {@link Context} — no-op.
 * W3C {@code traceparent}/{@code tracestate} и {@code baggage} не трогаются (зона OTel Java Agent).
 * <p>
 * <b>{@code X-Trace-On}:</b> по умолчанию наружу не пробрасывается ({@code propagateForceTrace=false});
 * решение о записи уже несёт sampled-flag в {@code traceparent}; явный проброс включается через
 * {@link OutboundPropagationDecision#propagateForceTrace()}.
 */
@RequiredArgsConstructor
public final class DefaultTraceControlHeaderInjector implements TraceControlHeaderInjector {

    private final String forceTraceHeader;
    private final String qaTraceHeader;
    private final String requestIdHeader;

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        if (context == null || carrier == null || setter == null) {
            return;
        }

        OutboundPropagationDecision decision = context.get(PlatformTraceContextKeys.PROPAGATION_DECISION);
        if (decision == null) {
            return;
        }

        InboundTraceControl control = context.get(PlatformTraceContextKeys.TRACE_CONTROL);
        if (control == null) {
            return;
        }

        if (decision.propagateForceTrace() && control.forceTrace()) {
            setter.set(carrier, forceTraceHeader, "on");
        }

        if (decision.propagateQaTrace() && control.qaTrace()) {
            setter.set(carrier, qaTraceHeader, "1");
        }

        if (decision.propagateRequestId() && control.requestId() != null) {
            setter.set(carrier, requestIdHeader, control.requestId());
        }
    }
}
