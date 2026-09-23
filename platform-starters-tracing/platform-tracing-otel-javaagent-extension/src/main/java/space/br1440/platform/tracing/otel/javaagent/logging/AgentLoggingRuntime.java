package space.br1440.platform.tracing.otel.javaagent.logging;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class AgentLoggingRuntime {

    private static final AgentLoggingRuntime SHARED = new AgentLoggingRuntime();

    private final PlatformLogEventEncoder encoder = new PlatformLogEventEncoder();
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.defaultState());

    private AgentLoggingRuntime() {
    }

    public static AgentLoggingRuntime shared() {
        return SHARED;
    }

    void install(PlatformStderrSink sink) {
        Objects.requireNonNull(sink, "sink");
        state.set(new RuntimeState(sink, AgentLogLevel.INFO));
    }

    static void reset() {
        SHARED.state.set(RuntimeState.defaultState());
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

    EmitResult emit(AgentLogEvent event) {
        Objects.requireNonNull(event, "event");
        return write(encoder.encode(event));
    }

    @SuppressWarnings("SameParameterValue")
    EmitResult emitFallback(AgentLogLevel level, AgentEventCode original, FallbackReason reason) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(reason, "reason");
        return write(encoder.encodeFallback(Instant.now(), level, original, reason));
    }

    private EmitResult write(byte[] encoded) {
        try {
            state.get().sink().write(encoded);
            return EmitResult.EMITTED;
        } catch (IOException exception) {
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
