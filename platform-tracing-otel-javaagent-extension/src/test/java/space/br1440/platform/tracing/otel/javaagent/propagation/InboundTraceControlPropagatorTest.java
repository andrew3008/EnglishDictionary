package space.br1440.platform.tracing.otel.javaagent.propagation;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.core.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InboundTraceControlPropagator: extract в Context + inject через decision")
class InboundTraceControlPropagatorTest {

    private final InboundTraceControlPropagator propagator =
            new InboundTraceControlPropagator("X-Trace-On", "X-QA-Trace", "X-Request-Id");

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> carrier.put(key, value);

    @Test
    @DisplayName("extract кладёт InboundTraceControl в Context")
    void extractStoresControl() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Trace-On", "on");
        carrier.put("X-Request-Id", "req-1");

        Context ctx = propagator.extract(Context.root(), carrier, MAP_GETTER);

        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);
        assertThat(control).isNotNull();
        assertThat(control.forceTrace()).isTrue();
        assertThat(control.requestId()).isEqualTo("req-1");
    }

    @Test
    @DisplayName("extract без платформенных заголовков не меняет Context")
    void extractNoHeadersKeepsContext() {
        Context ctx = propagator.extract(Context.root(), new HashMap<>(), MAP_GETTER);
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL)).isNull();
    }

    @Test
    @DisplayName("extract отбрасывает невалидный (CRLF) X-Request-Id")
    void extractRejectsInvalidRequestId() {
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Request-Id", "bad\r\nid");

        Context ctx = propagator.extract(Context.root(), carrier, MAP_GETTER);

        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);
        assertThat(control).isNotNull();
        assertThat(control.requestId()).isNull(); // reject-and-null, без мутации
    }

    @Test
    @DisplayName("inject без decision ничего не пишет (secure-by-default)")
    void injectWithoutDecisionWritesNothing() {
        Context ctx = Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(true, false, "req-1"));
        Map<String, String> carrier = new HashMap<>();

        propagator.inject(ctx, carrier, MAP_SETTER);

        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("inject с ALLOW_ALL пишет платформенные заголовки")
    void injectWithDecisionWrites() {
        Context ctx = Context.root()
                .with(PlatformTraceContextKeys.TRACE_CONTROL, InboundTraceControls.of(true, false, "req-1"))
                .with(PlatformTraceContextKeys.PROPAGATION_DECISION, OutboundPropagationDecision.ALLOW_ALL);
        Map<String, String> carrier = new HashMap<>();

        propagator.inject(ctx, carrier, MAP_SETTER);

        assertThat(carrier).containsEntry("X-Request-Id", "req-1");
        assertThat(carrier).containsEntry("X-Trace-On", "on");
    }

    @Test
    @DisplayName("Gate выключен (Фаза 14): extract не извлекает, inject не пишет — propagation no-op")
    void disabledGateMakesPropagationNoOp() {
        PlatformPropagationGate gate = new PlatformPropagationGate();
        gate.setEnabled(false);
        InboundTraceControlPropagator gated =
                new InboundTraceControlPropagator("X-Trace-On", "X-QA-Trace", "X-Request-Id", gate);

        Map<String, String> inbound = new HashMap<>();
        inbound.put("X-Trace-On", "on");
        inbound.put("X-Request-Id", "req-1");
        Context extracted = gated.extract(Context.root(), inbound, MAP_GETTER);
        assertThat(extracted.get(PlatformTraceContextKeys.TRACE_CONTROL)).isNull();
        assertThat(gate.getGatedExtracts()).isEqualTo(1);

        Context ctx = Context.root()
                .with(PlatformTraceContextKeys.TRACE_CONTROL, InboundTraceControls.of(true, false, "req-1"))
                .with(PlatformTraceContextKeys.PROPAGATION_DECISION, OutboundPropagationDecision.ALLOW_ALL);
        Map<String, String> outbound = new HashMap<>();
        gated.inject(ctx, outbound, MAP_SETTER);
        assertThat(outbound).isEmpty();
        assertThat(gate.getGatedInjects()).isEqualTo(1);
    }
}
