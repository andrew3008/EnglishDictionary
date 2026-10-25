package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLoggingRuntime;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogLevel;

public final class PlatformLogControl {

    private static final PlatformLogControl SHARED = new PlatformLogControl();
    private static final TracingSettings UNBOUND_POLICY = TracingSettingsSource.defaultPolicy();

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
            activeGeneration = 0L;
            applyUnboundDiagnosticsLevel();
        }
    }

    private static void applyUnboundDiagnosticsLevel() {
        AgentLoggingRuntime.shared().applyLevel(UNBOUND_POLICY.diagnosticsLogLevel());
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

            long token = generation.incrementAndGet();
            activeGeneration = token;
            policy.projectDiagnosticsLevel();
            return new AcceptedPolicyRegistration(token);
        }
    }

    private static AgentLoggingRuntime runtime() {
        return AgentLoggingRuntime.shared();
    }

    private final class AcceptedPolicyRegistration implements AutoCloseable {

        private final long token;
        private boolean closed;

        private AcceptedPolicyRegistration(long token) {
            this.token = token;
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

                activeGeneration = 0L;
                applyUnboundDiagnosticsLevel();
            }
        }
    }
}
