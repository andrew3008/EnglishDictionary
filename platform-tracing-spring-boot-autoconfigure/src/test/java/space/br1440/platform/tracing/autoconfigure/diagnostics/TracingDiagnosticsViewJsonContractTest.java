package space.br1440.platform.tracing.autoconfigure.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 7: stable JSON contract for {@link TracingDiagnosticsView}.
 */
class TracingDiagnosticsViewJsonContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void actuatorMap_serializesStableTopLevelKeysOnly() throws Exception {
        TracingDiagnosticsView view = new TracingDiagnosticsView(
                "ENABLED",
                null,
                Map.of("source", "test"));

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(view.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "details");
        assertThat(json.get("mode")).isEqualTo("ENABLED");
        assertThat(json.get("details")).isEqualTo(Map.of("source", "test"));
    }

    @Test
    void actuatorMap_includesReasonWhenPresent() throws Exception {
        TracingDiagnosticsView view = TracingDiagnosticsMapper.fromState(
                NoOpTracingRuntime.disabledByConfiguration("platform.tracing.sdk.mode=DISABLED").state());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(view.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "reason", "details");
        assertThat(json.get("mode")).isEqualTo("DISABLED_BY_CONFIGURATION");
        assertThat(json.get("reason")).isEqualTo("platform.tracing.sdk.mode=DISABLED");
        assertThat(json.get("details")).isEqualTo(Map.of());
    }

    @Test
    void internalTestMode_mapsToUnknownPublicMode() {
        TracingDiagnosticsView view = TracingDiagnosticsMapper.fromState(new TestModeTracingRuntime().state());

        assertThat(view.mode()).isEqualTo("UNKNOWN");
        assertThat(view.reason()).isEqualTo("test-primary");
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
            String mapped = TracingDiagnosticsMapper.toPublicMode(internal);
            assertThat(approved).contains(mapped);
        }
    }

    private static final class TestModeTracingRuntime implements space.br1440.platform.tracing.core.runtime.TracingRuntime {
        @Override
        public space.br1440.platform.tracing.api.span.spec.SpanHandle startSpan(
                space.br1440.platform.tracing.api.span.spec.SpanSpec spec) {
            return space.br1440.platform.tracing.core.runtime.NoOpSpanHandle.INSTANCE;
        }

        @Override
        public space.br1440.platform.tracing.api.manual.ActiveTraceContextView currentTraceContext() {
            return NoOpTracingRuntime.noop().currentTraceContext();
        }

        @Override
        public void recordException(
                space.br1440.platform.tracing.api.span.spec.SpanHandle span, Throwable throwable) {
        }

        @Override
        public space.br1440.platform.tracing.core.runtime.state.TracingState state() {
            return new space.br1440.platform.tracing.core.runtime.state.TracingState() {
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
        public space.br1440.platform.tracing.core.semconv.policy.AttributePolicy attributePolicy() {
            return new space.br1440.platform.tracing.core.semconv.policy.AttributePolicy();
        }
    }
}
