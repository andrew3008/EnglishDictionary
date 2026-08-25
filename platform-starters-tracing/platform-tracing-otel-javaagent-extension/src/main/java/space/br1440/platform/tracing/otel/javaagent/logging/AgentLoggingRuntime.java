package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class AgentLoggingRuntime {

    private static final AgentLoggingRuntime SHARED = new AgentLoggingRuntime();

    private final PlatformLogEventEncoder encoder = new PlatformLogEventEncoder();
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.defaultState());
    private final LongAdder encodeFailures = new LongAdder();
    private final LongAdder writeFailures = new LongAdder();

    private AgentLoggingRuntime() {
    }

    public static AgentLoggingRuntime shared() {
        return SHARED;
    }

    void installForTests(PlatformStderrSink sink) {
        Objects.requireNonNull(sink, "sink");
        state.set(new RuntimeState(sink, AgentLogLevel.INFO));
    }

    public static void resetSharedForTests() {
        SHARED.state.set(RuntimeState.defaultState());
        SHARED.encodeFailures.reset();
        SHARED.writeFailures.reset();
    }

    public boolean isEnabled(AgentLogLevel eventLevel) {
        return state.get().configuredLevel().permits(eventLevel);
    }

    public AgentLogLevel effectiveLevel() {
        return state.get().configuredLevel();
    }

    public void applyLevel(AgentLogLevel configuredLevel) {
        Objects.requireNonNull(configuredLevel, "configuredLevel");
        state.updateAndGet(current -> current.withConfiguredLevel(configuredLevel));
    }

    public EmitResult emit(AgentLogEvent event) {
        Objects.requireNonNull(event, "event");

        RuntimeState current = state.get();
        if (!current.configuredLevel().permits(event.level())) {
            return EmitResult.FILTERED_BY_LEVEL;
        }

        byte[] encoded;
        try {
            encoded = encoder.encode(event);
        } catch (RuntimeException exception) {
            encodeFailures.increment();
            return EmitResult.DELIVERY_FAILED;
        }

        try {
            current.sink().write(encoded);
            return EmitResult.EMITTED;
        } catch (IOException exception) {
            writeFailures.increment();
            return EmitResult.DELIVERY_FAILED;
        }
    }

    private record RuntimeState(PlatformStderrSink sink, AgentLogLevel configuredLevel) {

        static RuntimeState defaultState() {
            return new RuntimeState(new PlatformStderrSink(), AgentLogLevel.INFO);
        }

        RuntimeState withConfiguredLevel(AgentLogLevel newLevel) {
            return new RuntimeState(sink, newLevel);
        }
    }
}
