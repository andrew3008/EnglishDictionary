package space.br1440.platform.tracing.otel.propagation.control;

import java.util.Objects;
import java.util.Optional;

import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import lombok.RequiredArgsConstructor;

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
public final class DefaultTraceControlHeaderInjector implements PlatformOutboundPropagation, TraceControlHeaderInjector {

    private final String forceTraceHeader;
    private final String qaTraceHeader;
    private final String requestIdHeader;

    @Nonnull
    @Override
    public OutboundPropagationHeaders resolve(OutboundPropagationDecision decision) {
        if (isDenied(decision)) {
            return OutboundPropagationHeaders.EMPTY;
        }

        try {
            return resolve(Context.current(), decision);
        } catch (RuntimeException ignored) {
            return OutboundPropagationHeaders.EMPTY;
        }
    }

    @Override
    public <C> void inject(Context context, C carrier, TextMapSetter<C> setter) {
        if (context == null || carrier == null || setter == null) {
            return;
        }

        OutboundPropagationDecision decision = context.get(PlatformTraceContextKeys.PROPAGATION_DECISION);
        OutboundPropagationHeaders headers = resolve(context, decision);
        headers.forceTrace().ifPresent(header -> injectHeader(setter, carrier, header));
        headers.qaTrace().ifPresent(header -> injectHeader(setter, carrier, header));
        headers.requestId().ifPresent(header -> injectHeader(setter, carrier, header));
    }

    private static <C> void injectHeader(TextMapSetter<C> setter, C carrier, OutboundPropagationHeaders.Header header) {
        Objects.requireNonNull(header.value(), "value");
        setter.set(carrier, header.name(), header.value());
    }

    private OutboundPropagationHeaders resolve(Context context, OutboundPropagationDecision decision) {
        if (context == null || isDenied(decision)) {
            return OutboundPropagationHeaders.EMPTY;
        }

        InboundTraceControl control = context.get(PlatformTraceContextKeys.TRACE_CONTROL);
        if (control == null) {
            return OutboundPropagationHeaders.EMPTY;
        }

        Optional<OutboundPropagationHeaders.Header> forceTrace =
                decision.propagateForceTrace() && control.forceTrace()
                        ? header(forceTraceHeader, "on")
                        : Optional.empty();

        Optional<OutboundPropagationHeaders.Header> qaTrace =
                decision.propagateQaTrace() && control.qaTrace()
                        ? header(qaTraceHeader, "1")
                        : Optional.empty();

        Optional<OutboundPropagationHeaders.Header> requestId =
                decision.propagateRequestId() && control.requestId() != null
                        ? header(requestIdHeader, control.requestId())
                        : Optional.empty();

        return new OutboundPropagationHeaders(forceTrace, qaTrace, requestId);
    }

    private static Optional<OutboundPropagationHeaders.Header> header(String name, String value) {
        return Optional.of(new OutboundPropagationHeaders.Header(name, value));
    }

    private static boolean isDenied(OutboundPropagationDecision decision) {
        return decision == null
                || (!decision.propagateForceTrace()
                && !decision.propagateQaTrace()
                && !decision.propagateRequestId());
    }
}
