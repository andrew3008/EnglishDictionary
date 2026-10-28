package space.br1440.platform.tracing.otel.javaagent.logging;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.logging.event.AgentLogEventType;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

public final class AgentLoggingRuntime {

    private static final PlatformStderrSink PRODUCTION_SINK = new PlatformStderrSink();
    private static final AgentLoggingRuntime SHARED = new AgentLoggingRuntime();

    private final PlatformLogEventEncoder encoder;
    private final AtomicReference<RuntimeState> state;
    private final ThreadLocal<Boolean> emissionInProgress = ThreadLocal.withInitial(() -> false);
    private final Object installationLock = new Object();

    private ScopedInstallation activeInstallation;

    private AgentLoggingRuntime() {
        this(new PlatformLogEventEncoder(), RuntimeState.defaultState());
    }

    AgentLoggingRuntime(PlatformLogEventEncoder encoder, PlatformStderrSink sink, AgentLogLevel configuredLevel) {
        this(encoder, new RuntimeState(sink, configuredLevel));
    }

    private AgentLoggingRuntime(PlatformLogEventEncoder encoder, RuntimeState initialState) {
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.state = new AtomicReference<>(Objects.requireNonNull(initialState, "initialState"));
    }

    public static AgentLoggingRuntime shared() {
        return SHARED;
    }

    ScopedInstallation installScoped(PlatformStderrSink sink) {
        Objects.requireNonNull(sink, "sink");

        synchronized (installationLock) {
            if (activeInstallation != null) {
                throw new IllegalStateException("Agent logging test sink is already installed");
            }

            RuntimeState previous = state.get();
            activeInstallation = new ScopedInstallation(this, previous);
            state.set(new RuntimeState(sink, previous.configuredLevel()));
            return activeInstallation;
        }
    }

    static void reset() {
        SHARED.resetState();
    }

    boolean permits(AgentLogLevel eventLevel) {
        return state.get().configuredLevel().permits(eventLevel);
    }

    public AgentLogLevel effectiveLevel() {
        return state.get().configuredLevel();
    }

    public void applyLevel(AgentLogLevel configuredLevel) {
        Objects.requireNonNull(configuredLevel, "configuredLevel");
        state.updateAndGet(current -> current.withConfiguredLevel(configuredLevel));
    }

    EmitResult emitAdmitted(String logger,
                            AgentLogEventType eventType,
                            AgentLogFieldsWriter fieldsWriter,
                            long suppressedCount) {
        Objects.requireNonNull(logger, "logger");
        AgentLogEventType requiredEventType = Objects.requireNonNull(eventType, "eventType");
        AgentLogLevel requiredLevel = requiredEventType.level().requireEventLevel();
        Objects.requireNonNull(fieldsWriter, "fieldsWriter");

        return withinEmissionGuard(() ->
                buildEncodeAndDeliver(logger, requiredEventType, requiredLevel, fieldsWriter, suppressedCount));
    }

    EmitResult emitRateTransitionAdmitted(String logger,
                                          AgentLogEventType markerType,
                                          AgentLogFieldsWriter markerWriter,
                                          AgentLogEventType problemType,
                                          AgentLogFieldsWriter problemWriter,
                                          long suppressedCount) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(markerType, "markerType");
        Objects.requireNonNull(markerWriter, "markerWriter");
        Objects.requireNonNull(problemType, "problemType");
        Objects.requireNonNull(problemWriter, "problemWriter");

        return withinEmissionGuard(() -> {
            buildEncodeAndDeliver(logger, markerType, markerType.level(), markerWriter, 0L);
            return buildEncodeAndDeliver(logger, problemType, problemType.level(), problemWriter, suppressedCount);
        });
    }

    private EmitResult withinEmissionGuard(Supplier<EmitResult> emission) {
        Objects.requireNonNull(emission, "emission");

        if (emissionInProgress.get()) {
            return EmitResult.DELIVERY_FAILED;
        }

        emissionInProgress.set(true);
        try {
            return emission.get();
        } finally {
            emissionInProgress.remove();
        }
    }

    private EmitResult buildEncodeAndDeliver(String logger,
                                             AgentLogEventType eventType,
                                             AgentLogLevel level,
                                             AgentLogFieldsWriter fieldsWriter,
                                             long suppressedCount) {
        PlatformLogEventEncoder.EncodedLogEvent encoded;
        try {
            AgentLogFieldsBuilder fields = new AgentLogFieldsBuilder(eventType);
            fieldsWriter.write(fields);

            AgentLogFieldsBuilder.Snapshot snapshot = fields.snapshot();
            AgentLogRecord event = new AgentLogRecord(
                    Instant.now(),
                    level,
                    eventType.eventCode(),
                    eventType.name(),
                    logger,
                    eventType.message(),
                    snapshot.fields(),
                    snapshot.errorType(),
                    snapshot.throwableDigest(),
                    suppressedCount
            );

            encoded = encoder.encodeWithOutcome(event);
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            return encodeAndDeliverFallback(
                    level,
                    eventType.eventCode(),
                    eventType.name(),
                    FallbackReason.INVARIANT_VIOLATION,
                    suppressedCount
            );
        }

        EmitResult delivered = deliver(encoded.payload());
        if (delivered != EmitResult.EMITTED) {
            return delivered;
        }

        return encoded.fallback() ? EmitResult.FALLBACK_EMITTED : EmitResult.EMITTED;
    }

    @SuppressWarnings("SameParameterValue")
    private EmitResult encodeAndDeliverFallback(AgentLogLevel level,
                                                AgentEventCode original,
                                                String originalEventName,
                                                FallbackReason reason,
                                                long suppressedCount) {
        byte[] fallback;
        try {
            fallback = Objects.requireNonNull(
                    encoder.encodeFallback(
                            Instant.now(),
                            level,
                            original,
                            originalEventName,
                            reason,
                            suppressedCount
                    ),
                    "fallback"
            );

            if (fallback.length == 0 || fallback.length > AgentLogRecord.MAX_ENCODED_BYTES) {
                return EmitResult.DELIVERY_FAILED;
            }
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            return EmitResult.DELIVERY_FAILED;
        }

        EmitResult delivered = deliver(fallback);
        return (delivered == EmitResult.EMITTED) ? EmitResult.FALLBACK_EMITTED : delivered;
    }

    private EmitResult deliver(byte[] encoded) {
        try {
            state.get().sink().write(encoded);
            return EmitResult.EMITTED;
        } catch (Throwable failure) {
            PlatformThrowables.propagateIfFatal(failure);
            return EmitResult.DELIVERY_FAILED;
        }
    }

    private void restore(ScopedInstallation installation) {
        synchronized (installationLock) {
            if (installation.closed) {
                return;
            }

            if (activeInstallation != installation) {
                throw new IllegalStateException("Agent logging test sink scope is not active");
            }

            state.set(installation.previous);
            activeInstallation = null;
            installation.closed = true;
        }
    }

    private void resetState() {
        synchronized (installationLock) {
            if (activeInstallation != null) {
                activeInstallation.closed = true;
                activeInstallation = null;
            }

            state.set(RuntimeState.defaultState());
        }

        emissionInProgress.remove();
    }

    private record RuntimeState(PlatformStderrSink sink, AgentLogLevel configuredLevel) {

        private RuntimeState {
            Objects.requireNonNull(sink, "sink");
            Objects.requireNonNull(configuredLevel, "configuredLevel");
        }

        static RuntimeState defaultState() {
            return new RuntimeState(PRODUCTION_SINK, AgentLogLevel.INFO);
        }

        RuntimeState withConfiguredLevel(AgentLogLevel newLevel) {
            return new RuntimeState(sink, newLevel);
        }
    }

    static final class ScopedInstallation implements AutoCloseable {

        private final AgentLoggingRuntime runtime;
        private final RuntimeState previous;
        private boolean closed;

        private ScopedInstallation(AgentLoggingRuntime runtime, RuntimeState previous) {
            this.runtime = runtime;
            this.previous = previous;
        }

        @Override
        public void close() {
            runtime.restore(this);
        }
    }
}
