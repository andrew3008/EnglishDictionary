package space.br1440.platform.tracing.api.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import lombok.RequiredArgsConstructor;

/**
 * Единый инжектор платформенных управляющих заголовков на исходящем вызове.
 * <p>
 * Источник истины для outbound-инжекции: и {@code InboundTraceControlPropagator.inject()}
 * (agent-сторона), и client-интерсепторы (Spring-бины) используют именно этот класс, что
 * исключает дрейф логики между классами и classloader'ами.
 *
 * <h3>Контракт</h3>
 * <ul>
 *   <li><b>Secure-by-default:</b> если в {@link Context} нет {@link OutboundPropagationDecision}
 *       (выставляется client-интерсептором по trusted-решению), наружу НЕ уходит ничего.</li>
 *   <li>Инжектируются только три платформенных заголовка; W3C {@code traceparent}/{@code tracestate}
 *       и {@code baggage} — зона OTel Java Agent, здесь не трогаются (idempotent, без дублей).</li>
 *   <li>{@code X-Trace-On} по умолчанию наружу не пробрасывается: решение о записи уже несёт
 *       sampled-flag в {@code traceparent}; проброс включается явно через decision.</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class TraceControlHeaderInjector {

    private final String forceTraceHeader;
    private final String qaTraceHeader;
    private final String requestIdHeader;

    /**
     * Инжектирует платформенные заголовки в carrier согласно решению в Context.
     */
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
