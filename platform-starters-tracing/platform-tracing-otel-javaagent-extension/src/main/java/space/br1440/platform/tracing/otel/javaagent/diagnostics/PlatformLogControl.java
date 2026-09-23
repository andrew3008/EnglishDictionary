package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;

public final class PlatformLogControl {

    private static final PlatformLogControl SHARED = new PlatformLogControl();

    private final AtomicReference<TracingSettingsSource> acceptedPolicy = new AtomicReference<>(TracingSettingsSource.isolatedDefaults());
    private final AtomicLong bindCount = new AtomicLong();
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
            acceptedPolicy.set(TracingSettingsSource.isolatedDefaults());
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

            TracingSettingsSource previous = acceptedPolicy.get();
            long token = generation.incrementAndGet();
            acceptedPolicy.set(policy);
            activeGeneration = token;
            bindCount.incrementAndGet();
            policy.updater().runLocked(this::projectConfiguredPolicy);
            return new AcceptedPolicyRegistration(token, previous);
        }
    }

    private void projectConfiguredPolicy() {
        runtime().applyLevel(acceptedPolicy.get().current().policy().diagnosticsLogLevel());
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

                acceptedPolicy.set(previous);
                activeGeneration = 0L;
                previous.updater().runLocked(PlatformLogControl.this::projectConfiguredPolicy);
            }
        }
    }
}
