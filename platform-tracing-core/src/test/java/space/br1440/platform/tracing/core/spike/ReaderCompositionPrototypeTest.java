package space.br1440.platform.tracing.core.spike;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.propagation.OtelTraceparentReader;
import space.br1440.platform.tracing.api.span.RemoteSpanLink;
import space.br1440.platform.tracing.core.propagation.OtelTraceparentReaderImpl;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike A: проверяет composition semantics reader без static ServiceLoader holder.
 */
class ReaderCompositionPrototypeTest {

    private static final String TRACEPARENT =
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    void springDirectAndSpringAgentUseExplicitApplicationReader() {
        for (ApplicationMode mode : new ApplicationMode[]{
                ApplicationMode.SPRING,
                ApplicationMode.DIRECT,
                ApplicationMode.SPRING_WITH_AGENT}) {
            ReaderComposition composition = ReaderComposition.application(
                    mode, OtelTraceparentReaderImpl.INSTANCE);

            assertThat(composition.read(TRACEPARENT))
                    .get()
                    .extracting(RemoteSpanLink::traceId)
                    .isEqualTo("0af7651916cd43dd8448eb211c80319c");
        }
    }

    @Test
    void invalidTraceparentIsRejectedWithoutClassInitializationFailure() {
        ReaderComposition composition = ReaderComposition.application(
                ApplicationMode.DIRECT, OtelTraceparentReaderImpl.INSTANCE);

        assertThat(composition.read("not-a-traceparent")).isEmpty();
    }

    @Test
    void disabledUsesNoopReader() {
        ReaderComposition composition = ReaderComposition.application(
                ApplicationMode.DISABLED, NoOpReader.INSTANCE);

        assertThat(composition.read(TRACEPARENT)).isEmpty();
    }

    @Test
    void agentOnlyHasNoApplicationReader() {
        ReaderComposition composition = ReaderComposition.agentOnly();

        assertThat(composition.reader()).isEmpty();
        assertThat(composition.read(TRACEPARENT)).isEmpty();
    }

    private enum ApplicationMode {
        SPRING,
        SPRING_WITH_AGENT,
        DIRECT,
        DISABLED,
        AGENT_ONLY
    }

    private record ReaderComposition(
            ApplicationMode mode,
            Optional<OtelTraceparentReader> reader) {

        static ReaderComposition application(ApplicationMode mode, OtelTraceparentReader reader) {
            return new ReaderComposition(mode, Optional.of(reader));
        }

        static ReaderComposition agentOnly() {
            return new ReaderComposition(ApplicationMode.AGENT_ONLY, Optional.empty());
        }

        Optional<RemoteSpanLink> read(String traceparent) {
            return reader.flatMap(value -> value.read(traceparent));
        }
    }

    private enum NoOpReader implements OtelTraceparentReader {
        INSTANCE;

        @Override
        public Optional<RemoteSpanLink> read(String traceparent) {
            return Optional.empty();
        }

        @Override
        public Optional<RemoteSpanLink> read(String traceparent, String tracestate) {
            return Optional.empty();
        }

        @Override
        public RemoteSpanLink require(String traceparent) {
            throw new IllegalArgumentException("traceparent parsing is disabled");
        }
    }
}
