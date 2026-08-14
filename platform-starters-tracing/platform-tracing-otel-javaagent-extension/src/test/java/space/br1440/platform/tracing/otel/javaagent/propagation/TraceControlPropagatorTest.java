package space.br1440.platform.tracing.otel.javaagent.propagation;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.otel.javaagent.control.AcceptedPolicyMutationAccess;
import space.br1440.platform.tracing.otel.propagation.control.PlatformTraceContextKeys;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.test.harness.InboundTraceControls;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceControlPropagator: extract в Context + inject через decision")
class TraceControlPropagatorTest {

    private final TraceControlPropagator propagator =
            new TraceControlPropagator("X-Trace-On", "X-QA-Trace", "X-Request-Id");

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
        // given: gate отключён через настоящий accepted-policy update, а не test-only setEnabled(...)
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policy = TracingSettingsSource.isolatedDefaults();
        gate.bindAcceptedPolicy(policy);
        SettingsUpdateStatus outcome = AcceptedPolicyMutationAccess.applyTraceControlHeaders(policy, false);
        assertThat(outcome).isIn(SettingsUpdateStatus.APPLIED, SettingsUpdateStatus.UNCHANGED);
        assertThat(gate.isEnabled()).isFalse();

        TraceControlPropagator gated =
                new TraceControlPropagator("X-Trace-On", "X-QA-Trace", "X-Request-Id", gate);

        Map<String, String> inbound = new HashMap<>();
        inbound.put("X-Trace-On", "on");
        inbound.put("X-Request-Id", "req-1");
        Context input = Context.root();

        // when/then: disabled extract возвращает ИМЕННО тот же экземпляр Context и не читает carrier
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

        // when/then: disabled inject не вызывает setter.set и не меняет carrier
        Context ctx = Context.root()
                .with(PlatformTraceContextKeys.TRACE_CONTROL, InboundTraceControls.of(true, false, "req-1"))
                .with(PlatformTraceContextKeys.PROPAGATION_DECISION, OutboundPropagationDecision.ALLOW_ALL);
        Map<String, String> outbound = new HashMap<>();
        AtomicInteger setterCalls = new AtomicInteger();
        TextMapSetter<Map<String, String>> countingSetter = (carrier, key, value) -> {
            setterCalls.incrementAndGet();
            carrier.put(key, value);
        };
        gated.inject(ctx, outbound, countingSetter);
        assertThat(setterCalls.get()).isZero();
        assertThat(outbound).isEmpty();
    }

    @Test
    @DisplayName("fields() не зависит от текущего gate value")
    void fields_doesNotDependOnGateValue() {
        PlatformPropagationGate gate = new PlatformPropagationGate();
        TracingSettingsSource policy = TracingSettingsSource.isolatedDefaults();
        gate.bindAcceptedPolicy(policy);
        AcceptedPolicyMutationAccess.applyTraceControlHeaders(policy, false);
        TraceControlPropagator gated =
                new TraceControlPropagator("X-Trace-On", "X-QA-Trace", "X-Request-Id", gate);

        assertThat(gated.fields()).containsExactlyInAnyOrder("X-Trace-On", "X-QA-Trace", "X-Request-Id");
    }

    @Test
    @DisplayName("характеризация: non-atomic multi-header inject — сбой второго setter.set оставляет ранее записанный заголовок")
    void inject_nonAtomicMultiHeaderWrite_characterization() {
        // given: decision, разрешающее все три заголовка, чтобы DefaultTraceControlHeaderInjector
        // вызвал setter.set() до трёх раз последовательно (см. RG-PROP-PARTIAL-WRITE, §0.4/§1.3 плана)
        Context ctx = Context.root()
                .with(PlatformTraceContextKeys.TRACE_CONTROL, InboundTraceControls.of(true, true, "req-1"))
                .with(PlatformTraceContextKeys.PROPAGATION_DECISION, OutboundPropagationDecision.ALLOW_ALL);
        Map<String, String> carrier = new LinkedHashMap<>();
        AtomicInteger callCount = new AtomicInteger();
        TextMapSetter<Map<String, String>> throwsOnSecondCall = (c, key, value) -> {
            if (callCount.incrementAndGet() == 2) {
                throw new IllegalStateException("simulated non-fatal failure on second header write");
            }
            c.put(key, value);
        };

        // when/then: TextMapSetter не имеет rollback API — исключение из второго вызова
        // пробрасывается наружу (TraceControlPropagator/DefaultTraceControlHeaderInjector
        // не содержат catch-блока), а первый уже записанный заголовок остаётся в carrier.
        // Это характеризованное implementation limitation, а не нормативный контракт.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> propagator.inject(ctx, carrier, throwsOnSecondCall));
        assertThat(carrier).hasSize(1);
    }
}
