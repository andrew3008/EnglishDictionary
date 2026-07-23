package space.br1440.platform.tracing.otel.span;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.otel.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.otel.runtime.state.TracingMode;
import space.br1440.platform.tracing.otel.span.DefaultSpanFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSpanFactoryReaderCompositionTest {

    private static final String TRACEPARENT =
            "00-0102030405060708090a0b0c0d0e0f10-0102030405060708-01";
    private static final RemoteSpanLink LINK = RemoteSpanLink.sampled(
            "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

    @Test
    void enabledRuntimeUsesProductionReader() {
        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, runtime.attributePolicy());

        factory.operation("test.operation")
                .root()
                .fromTraceparent(TRACEPARENT)
                .start()
                .close();

        assertThat(runtime.receivedSpecs()).singleElement()
                .satisfies(spec -> assertThat(spec.relationship().links()).containsExactly(LINK));
    }

    @Test
    void disabledRuntimeDoesNotParseTransportInput() {
        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        runtime.setState(ImmutableTracingState.of(
                TracingMode.DISABLED_BY_CONFIGURATION, "test", Map.of()));
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, runtime.attributePolicy());

        assertThatThrownBy(() -> factory.operation("test.operation")
                .fromTraceparent("opaque-transport-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("traceparent parsing is disabled");
    }
}
