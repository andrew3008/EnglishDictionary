package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class PlatformLogControl {

    private static final PlatformLogControl SHARED = new PlatformLogControl();

    private final AtomicLong bindCount = new AtomicLong();
    private TracingSettingsSource active = TracingSettingsSource.isolatedDefaults();
    private final AtomicLong generation = new AtomicLong();
    private final Object bindLock = new Object();
    private long activeGeneration;

    public static PlatformLogControl shared() {
        return SHARED;
    }

    static void reset() {
        SHARED.releaseActive();
    }

    private void releaseActive() {
        synchronized (bindLock) {
            active = TracingSettingsSource.isolatedDefaults();
            activeGeneration = 0L;
        }
    }

    public AgentLogLevel getLevel() {
        return runtime().effectiveLevel();
    }

    public AutoCloseable bindAcceptedPolicy(TracingSettingsSource policy) {
        Objects.requireNonNull(policy, "policy");

        synchronized (bindLock) {
            if (activeGeneration != 0L) {
                throw new IllegalStateException(
                        "PlatformLogControl already has an active accepted-policy registration");
            }

            TracingSettingsSource previous = active;
            long token = generation.incrementAndGet();
            active = policy;
            activeGeneration = token;
            bindCount.incrementAndGet();
            policy.projectDiagnosticsLevel();
            return new AcceptedPolicyRegistration(token, previous);
        }
    }

    private static AgentLoggingRuntime runtime() {
        return AgentLoggingRuntime.shared();
    }

    private final class AcceptedPolicyRegistration implements AutoCloseable {

        private final long token;
        private final TracingSettingsSource previous;
        private boolean closed;

        private AcceptedPolicyRegistration(long token, TracingSettingsSource previous) {
            this.token = token;
            this.previous = previous;
        }

        @Override
        public void close() {
            synchronized (bindLock) {
                if (closed) {
                    return;
                }
                closed = true;

                if (activeGeneration != token) {
                    return;
                }

                active = previous;
                activeGeneration = 0L;
                previous.projectDiagnosticsLevel();
            }
        }
    }
}
