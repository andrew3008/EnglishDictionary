package space.br1440.platform.tracing.otel.javaagent.propagation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

@DisplayName("TraceControlPropagator: extract в Context; outbound inject — intentional no-op")
class TraceControlPropagatorTest {

    private final TraceControlPropagator propagator = new TraceControlPropagator();

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

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> {
        if (carrier == null) {
            throw new IllegalArgumentException("carrier must not be null");
        }

        carrier.put(key, value);
    };

    @Test
    @DisplayName("extract кладёт InboundTraceControl в Context")
    void extractStoresControl() {
        // given
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Trace-On", "on");
        carrier.put("X-Request-Id", "req-1");

        // when
        Context ctx = propagator.extract(Context.root(), carrier, MAP_GETTER);

        // then
        InboundTraceControl control = ctx.get(PlatformTraceContextKeys.TRACE_CONTROL);
        assertThat(control).isNotNull();
        assertThat(control.forceTrace()).isTrue();
        assertThat(control.requestId()).isNull();
    }

    @Test
    @DisplayName("extract без платформенных заголовков не меняет Context")
    void extractNoHeadersKeepsContext() {
        // when
        Context ctx = propagator.extract(Context.root(), new HashMap<>(), MAP_GETTER);

        // then
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL)).isNull();
    }

    @Test
    @DisplayName("extract ignores X-Request-Id and does not create control from it")
    void extractIgnoresRequestIdOnlyCarrier() {
        // given
        Map<String, String> carrier = new HashMap<>();
        carrier.put("X-Request-Id", "req-1");

        // when
        Context ctx = propagator.extract(Context.root(), carrier, MAP_GETTER);

        // then
        assertThat(ctx.get(PlatformTraceContextKeys.TRACE_CONTROL)).isNull();
    }

    @Test
    @DisplayName("inject — intentional no-op: outbound-инъекция принадлежит app-plane порту, не пропагатору")
    void injectIsIntentionalNoOp() {
        // given
        Context ctx = Context.root().with(PlatformTraceContextKeys.TRACE_CONTROL,
                InboundTraceControls.of(true, true, "req-1"));
        Map<String, String> carrier = new HashMap<>();

        // when
        propagator.inject(ctx, carrier, MAP_SETTER);

        // then
        assertThat(carrier).isEmpty();
    }

    @Test
    @DisplayName("Gate выключен: extract не извлекает — propagation no-op")
    void disabledGateMakesExtractNoOp() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        try (AutoCloseable ignored = gate.bindAcceptedPolicy(policy)) {
            SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyTraceControlHeaders(policy, false);
            assertThat(outcome).isIn(SettingsUpdateStatus.APPLIED, SettingsUpdateStatus.UNCHANGED);
            assertThat(gate.isEnabled()).isFalse();

            TraceControlPropagator gated = new TraceControlPropagator(gate);

            Map<String, String> inbound = new HashMap<>();
            inbound.put("X-Trace-On", "on");
            inbound.put("X-Request-Id", "req-1");
            Context input = Context.root();

            // when/then
            AtomicInteger getterCalls = new AtomicInteger();
            TextMapGetter<Map<String, String>> countingGetter = new TextMapGetter<>() {
                @Override
                public Iterable<String> keys(Map<String, String> carrier) {
                    return carrier.keySet();
                }

                @Override
                public String get(Map<String, String> carrier, String key) {
                    getterCalls.incrementAndGet();
                    return carrier == null ? null : carrier.get(key);
                }
            };
            Context extracted = gated.extract(input, inbound, countingGetter);
            assertThat(extracted).isSameAs(input);
            assertThat(getterCalls.get()).isZero();
        }
    }

    @Test
    @DisplayName("fields() не зависит от текущего gate value")
    void fields_doesNotDependOnGateValue() throws Exception {
        // given
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policy = TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random());
        try (AutoCloseable ignored = gate.bindAcceptedPolicy(policy)) {
            AcceptedPolicyMutationAccess.applyTraceControlHeaders(policy, false);
            TraceControlPropagator gated = new TraceControlPropagator(gate);

            // when/then
            assertThat(gated.fields()).containsExactlyInAnyOrder("X-Trace-On", "X-QA-Trace", "X-Request-Id");
        }
    }
}
