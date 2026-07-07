package space.br1440.platform.tracing.api.propagation.control;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlatformOutboundInjector: secure-by-default + per-header inject")
class PlatformOutboundInjectorTest {

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> carrier.put(key, value);

    private final PlatformOutboundInjector injector =
            new PlatformOutboundInjector("X-Trace-On", "X-QA-Trace", "X-Request-Id");

    private static Context contextWith(PlatformPropagationDecision decision) {
        Context ctx = Context.root()
                .with(PlatformTraceContextKeys.TRACE_CONTROL,
                        PlatformTraceControl.fromHeaders("on", "1", "req-123"));
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
        injector.inject(contextWith(PlatformPropagationDecision.DENY_ALL), carrier, MAP_SETTER);
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("requestId-only: только X-Request-Id")
    void requestIdOnly() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(new PlatformPropagationDecision(false, false, true)), carrier, MAP_SETTER);
        assertThat(carrier).containsOnlyKeys("X-Request-Id");
        assertThat(carrier.get("X-Request-Id")).isEqualTo("req-123");
    }

    @Test
    @DisplayName("force по умолчанию не пробрасывается (decision=false) даже при forceTrace в control")
    void forceSkippedWhenDecisionFalse() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(new PlatformPropagationDecision(false, false, false)), carrier, MAP_SETTER);
        assertThat(carrier).doesNotContainKey("X-Trace-On");
    }

    @Test
    @DisplayName("ALLOW_ALL инжектит все три заголовка")
    void allowAllInjectsAll() {
        Map<String, String> carrier = new HashMap<>();
        injector.inject(contextWith(PlatformPropagationDecision.ALLOW_ALL), carrier, MAP_SETTER);
        assertThat(carrier).containsKeys("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        assertThat(carrier.get("X-Trace-On")).isEqualTo("on");
    }
}
