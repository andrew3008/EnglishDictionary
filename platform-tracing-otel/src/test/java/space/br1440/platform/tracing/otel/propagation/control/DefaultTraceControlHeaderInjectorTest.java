package space.br1440.platform.tracing.otel.propagation.control;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;

@DisplayName("DefaultTraceControlHeaderInjector: secure-by-default + per-header inject")
class DefaultTraceControlHeaderInjectorTest {

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> carrier.put(key, value);

    private final TraceControlHeaderInjector injector =
            new DefaultTraceControlHeaderInjector("X-Trace-On", "X-QA-Trace", "X-Request-Id");
    private final PlatformOutboundPropagation propagation =
            new DefaultTraceControlHeaderInjector("X-Trace-On", "X-QA-Trace", "X-Request-Id");

    private static Context contextWith(OutboundPropagationDecision decision) {
        InboundTraceControl control = new DefaultInboundTraceControlExtractor()
                .fromHeaders("on", "1", "req-123");
        Context ctx = Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL, control);
        if (decision != null) {
            ctx = ctx.with(PlatformTraceContextKeys.PROPAGATION_DECISION, decision);
        }
        return ctx;
    }

    @Test
    @DisplayName("без PROPAGATION_DECISION ничего не инжектится (secure-by-default)")
    void noDecisionInjectsNothing() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(null), carrier, MAP_SETTER);
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("DENY_ALL ничего не инжектит")
    void denyAllInjectsNothing() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(OutboundPropagationDecision.DENY_ALL), carrier, MAP_SETTER);
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("requestId-only: только X-Request-Id")
    void requestIdOnly() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(new OutboundPropagationDecision(false, false, true)), carrier, MAP_SETTER);
        assertThat(carrier).containsOnlyKeys("X-Request-Id");
        assertThat(carrier.get("X-Request-Id")).isEqualTo("req-123");
    }

    @Test
    @DisplayName("force по умолчанию не пробрасывается (decision=false) даже при forceTrace в control")
    void forceSkippedWhenDecisionFalse() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(new OutboundPropagationDecision(false, false, false)), carrier, MAP_SETTER);
        assertThat(carrier).doesNotContainKey("X-Trace-On");
    }

    @Test
    @DisplayName("ALLOW_ALL инжектит все три заголовка")
    void allowAllInjectsAll() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(OutboundPropagationDecision.ALLOW_ALL), carrier, MAP_SETTER);
        assertThat(carrier).containsKeys("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        assertThat(carrier.get("X-Trace-On")).isEqualTo("on");
    }

    @Test
    @DisplayName("application port читает current context и возвращает типизированные slots")
    void resolveReadsCurrentContext() {
        try (Scope ignored = contextWith(OutboundPropagationDecision.ALLOW_ALL).makeCurrent()) {
            OutboundPropagationHeaders headers = propagation.resolve(OutboundPropagationDecision.ALLOW_ALL);

            assertThat(headers.forceTrace()).contains(
                    new OutboundPropagationHeaders.Header("X-Trace-On", "on"));
            assertThat(headers.qaTrace()).contains(
                    new OutboundPropagationHeaders.Header("X-QA-Trace", "1"));
            assertThat(headers.requestId()).contains(
                    new OutboundPropagationHeaders.Header("X-Request-Id", "req-123"));
        }
    }

    @Test
    @DisplayName("application port fail-closed при отсутствии decision или current control")
    void resolveWithoutRequiredStateIsEmpty() {
        assertThat(propagation.resolve(null)).isEqualTo(OutboundPropagationHeaders.EMPTY);
        assertThat(propagation.resolve(OutboundPropagationDecision.ALLOW_ALL))
                .isEqualTo(OutboundPropagationHeaders.EMPTY);
    }
}
