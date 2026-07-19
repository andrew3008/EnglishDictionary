package space.br1440.platform.tracing.core.manual;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.core.runtime.RecordingTracingRuntime;
import space.br1440.platform.tracing.core.runtime.state.ImmutableTracingState;
import space.br1440.platform.tracing.core.runtime.state.TracingMode;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSpanFactoryReaderCompositionTest {

    private static final RemoteSpanLink LINK = RemoteSpanLink.sampled(
            "0102030405060708090a0b0c0d0e0f10", "0102030405060708");

    @Test
    void manualBuilderUsesInjectedReader() {
        RecordingTracingRuntime runtime = new RecordingTracingRuntime();
        OtelTraceparentReader reader = new StubReader();
        DefaultSpanFactory factory = new DefaultSpanFactory(runtime, runtime.attributePolicy(), reader);

        factory.operation("test.operation")
                .root()
                .fromTraceparent("opaque-transport-value")
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

    private static final class StubReader implements OtelTraceparentReader {

        @Override
        public Optional<RemoteSpanLink> read(String traceparent) {
            return Optional.of(LINK);
        }

        @Override
        public Optional<RemoteSpanLink> read(String traceparent, String tracestate) {
            return Optional.of(LINK);
        }

        @Override
        public RemoteSpanLink require(String traceparent) {
            return LINK;
        }
    }
}
