package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;

public final class PlatformLogControl {

    private static final AgentLogLevel PARSE_FALLBACK = AgentLogLevel.WARN;
    private static final PlatformLogControl SHARED = new PlatformLogControl();

    private final AtomicReference<TracingSettingsSource> acceptedPolicy = new AtomicReference<>(TracingSettingsSource.isolatedDefaults());
    private final AtomicLong bindCount = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final Object bindLock = new Object();
    private long activeGeneration;

    public static PlatformLogControl shared() {
        return SHARED;
    }

    public static void resetSharedForTests() {
        SHARED.releaseActiveForTests();
        AgentLoggingRuntime.resetSharedForTests();
    }

    private void releaseActiveForTests() {
        synchronized (bindLock) {
            acceptedPolicy.set(TracingSettingsSource.isolatedDefaults());
            activeGeneration = 0L;
        }
    }

    public AgentLogLevel getLevel() {
        return runtime().effectiveLevel();
    }

    public void setLevel(AgentLogLevel newLevel) {
        if (newLevel == null) {
            return;
        }

        projectLevel(newLevel);
    }

    public boolean setLevel(String raw) {
        if (raw == null) {
            return false;
        }

        AgentLogLevel parsed = AgentLogLevel.parseOrDefault(raw, null);
        if (parsed == null) {
            return false;
        }

        projectLevel(parsed);
        return true;
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
            projectConfiguredPolicy();
            return new AcceptedPolicyRegistration(token, previous);
        }
    }

    private void projectLevel(AgentLogLevel newLevel) {
        runtime().applyLevel(newLevel);
        acceptedPolicy.get().applyLogLevel(newLevel.name());
    }

    private void projectConfiguredPolicy() {
        AgentLogLevel configured = AgentLogLevel.parseOrDefault(
                acceptedPolicy.get().current().diagnosticsLogLevel(),
                PARSE_FALLBACK
        );
        runtime().applyLevel(configured);
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
                projectConfiguredPolicy();
            }
        }
    }
}
