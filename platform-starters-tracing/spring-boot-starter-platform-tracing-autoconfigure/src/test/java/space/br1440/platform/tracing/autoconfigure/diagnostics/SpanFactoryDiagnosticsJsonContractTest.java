package space.br1440.platform.tracing.autoconfigure.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.api.context.ActiveTraceContextView;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.otel.attributes.validation.SpanAttributeValidator;
import space.br1440.platform.tracing.otel.runtime.NoOpSpanHandle;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TraceControlBoundaryState;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;
import space.br1440.platform.tracing.otel.runtime.state.TracingState;

class SpanFactoryDiagnosticsJsonContractTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void actuatorMap_serializesStableTopLevelKeysOnly() throws Exception {
        SpanFactoryDiagnostics diagnostics = new SpanFactoryDiagnostics(NoOpTracingRuntime.noop());

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
                NoOpTracingRuntime.disabledByConfiguration("platform.tracing.enabled=false"));

        @SuppressWarnings("unchecked")
        Map<String, Object> json = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(diagnostics.toActuatorMap()),
                Map.class);

        assertThat(json.keySet()).containsExactlyInAnyOrder("mode", "reason", "details");
        assertThat(json.get("mode")).isEqualTo("DISABLED_BY_CONFIGURATION");
        assertThat(json.get("reason")).isEqualTo("platform.tracing.enabled=false");
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
                "UNKNOWN"
        );

        for (TracingRuntimeMode internal : TracingRuntimeMode.values()) {
            String mapped = modeFrom(new TestModeTracingRuntimeWithMode(internal));
            assertThat(approved).contains(mapped);
        }
    }

    private static String modeFrom(TracingRuntime runtime) {
        return (String) new SpanFactoryDiagnostics(runtime).toActuatorMap().get("mode");
    }

    private static final class TestModeTracingRuntime implements TracingRuntime {

        private final TracingRuntime identityDelegate = NoOpTracingRuntime.noop();

        @Override
        @Nonnull
        public SpanHandle startSpan(@Nonnull SpanSpec spec) {
            return NoOpSpanHandle.INSTANCE;
        }

        @Override
        @Nonnull
        public ActiveTraceContextView currentTraceContext() {
            return identityDelegate.currentTraceContext();
        }

        @Override
        @Nonnull
        public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
            return identityDelegate.openCorrelationScope(correlationId);
        }

        @Override
        @Nonnull
        public CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
            return identityDelegate.openRequestIdentityScope(requestId, forceTrace, qaTrace);
        }

        @Override
        @Nonnull
        public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
            return identityDelegate.requireCanonicalCorrelationId(correlationId);
        }

        @Override
        @Nonnull
        public Optional<String> currentRequestId() {
            return identityDelegate.currentRequestId();
        }

        @Override
        @Nonnull
        public Optional<String> currentCorrelationId() {
            return identityDelegate.currentCorrelationId();
        }

        @Override
        @Nonnull
        public TraceControlBoundaryState currentTraceControlBoundary() {
            return identityDelegate.currentTraceControlBoundary();
        }

        @Override
        public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        }

        @Override
        @Nonnull
        public TracingState state() {
            return ImmutableTracingState.of(TracingRuntimeMode.TEST, "test-primary", Map.of());
        }

        @Override
        @Nonnull
        public SpanAttributeValidator spanAttributeValidator() {
            return new SpanAttributeValidator();
        }
    }

    private static final class TestModeTracingRuntimeWithMode implements TracingRuntime {

        private final TracingRuntimeMode mode;
        private final TracingRuntime identityDelegate = NoOpTracingRuntime.noop();

        private TestModeTracingRuntimeWithMode(TracingRuntimeMode mode) {
            this.mode = mode;
        }

        @Override
        @Nonnull
        public SpanHandle startSpan(@Nonnull SpanSpec spec) {
            return NoOpSpanHandle.INSTANCE;
        }

        @Override
        @Nonnull
        public ActiveTraceContextView currentTraceContext() {
            return identityDelegate.currentTraceContext();
        }

        @Override
        @Nonnull
        public CorrelationScope openCorrelationScope(@Nonnull String correlationId) {
            return identityDelegate.openCorrelationScope(correlationId);
        }

        @Override
        @Nonnull
        public CorrelationScope openRequestIdentityScope(@Nonnull String requestId, boolean forceTrace, boolean qaTrace) {
            return identityDelegate.openRequestIdentityScope(requestId, forceTrace, qaTrace);
        }

        @Override
        @Nonnull
        public String requireCanonicalCorrelationId(@Nonnull String correlationId) {
            return identityDelegate.requireCanonicalCorrelationId(correlationId);
        }

        @Override
        @Nonnull
        public Optional<String> currentRequestId() {
            return identityDelegate.currentRequestId();
        }

        @Override
        @Nonnull
        public Optional<String> currentCorrelationId() {
            return identityDelegate.currentCorrelationId();
        }

        @Override
        @Nonnull
        public TraceControlBoundaryState currentTraceControlBoundary() {
            return identityDelegate.currentTraceControlBoundary();
        }

        @Override
        public void recordException(@Nonnull SpanHandle span, @Nullable Throwable throwable) {
        }

        @Override
        @Nonnull
        public TracingState state() {
            return ImmutableTracingState.of(mode, null, Map.of());
        }

        @Override
        @Nonnull
        public SpanAttributeValidator spanAttributeValidator() {
            return new SpanAttributeValidator();
        }
    }
}
