package space.br1440.platform.tracing.autoconfigure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7: stable JSON contract for {@link SpanFactoryDiagnostics#toActuatorMap()}.
 */
class SpanFactoryDiagnosticsJsonContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void actuatorMap_serializesStableTopLevelKeysOnly() throws Exception {
        SpanFactoryDiagnostics diagnostics = new SpanFactoryDiagnostics(
                NoOpTracingRuntime.noop());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(diagnostics.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "details");
        assertThat(json.get("mode")).isEqualTo("NOOP");
        assertThat(json.get("details")).isEqualTo(Map.of());
    }

    @Test
    void actuatorMap_includesReasonWhenPresent() throws Exception {
        SpanFactoryDiagnostics diagnostics = new SpanFactoryDiagnostics(
                NoOpTracingRuntime.disabledByConfiguration("platform.tracing.mode=DISABLED"));

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(diagnostics.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "reason", "details");
        assertThat(json.get("mode")).isEqualTo("DISABLED_BY_CONFIGURATION");
        assertThat(json.get("reason")).isEqualTo("platform.tracing.mode=DISABLED");
        assertThat(json.get("details")).isEqualTo(Map.of());
    }

    @Test
    void internalTestMode_mapsToUnknownPublicMode() {
        SpanFactoryDiagnostics diagnostics = new SpanFactoryDiagnostics(new TestModeTracingRuntime());

        Map<String, Object> map = diagnostics.toActuatorMap();
        assertThat(map.get("mode")).isEqualTo("UNKNOWN");
        assertThat(map.get("reason")).isEqualTo("test-primary");
    }

    @Test
    void approvedPublicModes_areClosedSet() {
        Set<String> approved = Set.of(
                "ENABLED",
                "DISABLED_BY_CONFIGURATION",
                "UNAVAILABLE",
                "NOOP",
                "UNKNOWN");

        for (TracingMode internal : TracingMode.values()) {
            String mapped = modeFrom(new TestModeTracingRuntimeWithMode(internal));
            assertThat(approved).contains(mapped);
        }
    }

    private static String modeFrom(space.br1440.platform.tracing.otel.runtime.TracingRuntime runtime) {
        return (String) new SpanFactoryDiagnostics(runtime).toActuatorMap().get("mode");
    }

    private static final class TestModeTracingRuntime implements space.br1440.platform.tracing.otel.runtime.TracingRuntime {

        private final space.br1440.platform.tracing.otel.runtime.TracingRuntime identityDelegate =
                NoOpTracingRuntime.noop();

        @Override
        public space.br1440.platform.tracing.api.span.spec.SpanHandle startSpan(
                space.br1440.platform.tracing.api.span.spec.SpanSpec spec) {
            return space.br1440.platform.tracing.otel.runtime.NoOpSpanHandle.INSTANCE;
        }

        @Override
        public space.br1440.platform.tracing.api.context.ActiveTraceContextView currentTraceContext() {
            return identityDelegate.currentTraceContext();
        }

        @Override
        public space.br1440.platform.tracing.api.context.CorrelationScope openCorrelationScope(String correlationId) {
            return identityDelegate.openCorrelationScope(correlationId);
        }

        @Override
        public space.br1440.platform.tracing.api.context.CorrelationScope openRequestIdentityScope(String requestId) {
            return identityDelegate.openRequestIdentityScope(requestId);
        }

        @Override
        public String requireCanonicalCorrelationId(String correlationId) {
            return identityDelegate.requireCanonicalCorrelationId(correlationId);
        }

        @Override
        public Optional<String> currentRequestId() {
            return identityDelegate.currentRequestId();
        }

        @Override
        public Optional<String> currentCorrelationId() {
            return identityDelegate.currentCorrelationId();
        }

        @Override
        public void recordException(
                space.br1440.platform.tracing.api.span.spec.SpanHandle span, Throwable throwable) {
        }

        @Override
        public space.br1440.platform.tracing.otel.runtime.state.TracingState state() {
            return new space.br1440.platform.tracing.otel.runtime.state.TracingState() {
                @Override
                public TracingMode mode() {
                    return TracingMode.TEST;
                }

                @Override
                public java.util.Optional<String> reason() {
                    return java.util.Optional.of("test-primary");
                }

                @Override
                public Map<String, String> details() {
                    return Map.of();
                }
            };
        }

        @Override
        public space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy attributePolicy() {
            return new space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy();
        }
    }

    private static final class TestModeTracingRuntimeWithMode implements space.br1440.platform.tracing.otel.runtime.TracingRuntime {

        private final TracingMode mode;
        private final space.br1440.platform.tracing.otel.runtime.TracingRuntime identityDelegate =
                NoOpTracingRuntime.noop();

        private TestModeTracingRuntimeWithMode(TracingMode mode) {
            this.mode = mode;
        }

        @Override
        public space.br1440.platform.tracing.api.span.spec.SpanHandle startSpan(
                space.br1440.platform.tracing.api.span.spec.SpanSpec spec) {
            return space.br1440.platform.tracing.otel.runtime.NoOpSpanHandle.INSTANCE;
        }

        @Override
        public space.br1440.platform.tracing.api.context.ActiveTraceContextView currentTraceContext() {
            return identityDelegate.currentTraceContext();
        }

        @Override
        public space.br1440.platform.tracing.api.context.CorrelationScope openCorrelationScope(String correlationId) {
            return identityDelegate.openCorrelationScope(correlationId);
        }

        @Override
        public space.br1440.platform.tracing.api.context.CorrelationScope openRequestIdentityScope(String requestId) {
            return identityDelegate.openRequestIdentityScope(requestId);
        }

        @Override
        public String requireCanonicalCorrelationId(String correlationId) {
            return identityDelegate.requireCanonicalCorrelationId(correlationId);
        }

        @Override
        public Optional<String> currentRequestId() {
            return identityDelegate.currentRequestId();
        }

        @Override
        public Optional<String> currentCorrelationId() {
            return identityDelegate.currentCorrelationId();
        }

        @Override
        public void recordException(
                space.br1440.platform.tracing.api.span.spec.SpanHandle span, Throwable throwable) {
        }

        @Override
        public space.br1440.platform.tracing.otel.runtime.state.TracingState state() {
            return new space.br1440.platform.tracing.otel.runtime.state.TracingState() {
                @Override
                public TracingMode mode() {
                    return mode;
                }

                @Override
                public Optional<String> reason() {
                    return Optional.empty();
                }

                @Override
                public Map<String, String> details() {
                    return Map.of();
                }
            };
        }

        @Override
        public space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy attributePolicy() {
            return new space.br1440.platform.tracing.otel.semconv.policy.AttributePolicy();
        }
    }
}
