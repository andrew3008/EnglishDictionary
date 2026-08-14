package space.br1440.platform.tracing.otel.javaagent.propagation;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlatformPropagationGate {

    private static final PlatformPropagationGate SHARED = new PlatformPropagationGate();

    private final AtomicReference<TracingSettingsSource> acceptedPolicy =
            new AtomicReference<>(TracingSettingsSource.isolatedDefaults());
    private final AtomicLong generation = new AtomicLong();
    private final Object bindLock = new Object();
    private long activeGeneration;

    PlatformPropagationGate() {
    }

    public static PlatformPropagationGate shared() {
        return SHARED;
    }

    public static void resetSharedForTests() {
        SHARED.releaseActiveForTests();
    }

    private void releaseActiveForTests() {
        synchronized (bindLock) {
            acceptedPolicy.set(TracingSettingsSource.isolatedDefaults());
            activeGeneration = 0L;
        }
    }

    public boolean isEnabled() {
        return acceptedPolicy.get().current().traceControlHeadersEnabled();
    }

    public AutoCloseable bindAcceptedPolicy(TracingSettingsSource policy) {
        Objects.requireNonNull(policy, "policy");
        synchronized (bindLock) {
            if (activeGeneration != 0L) {
                throw new IllegalStateException(
                        "PlatformPropagationGate already has an active accepted-policy registration");
            }

            TracingSettingsSource previous = acceptedPolicy.get();
            long token = generation.incrementAndGet();
            acceptedPolicy.set(policy);
            activeGeneration = token;
            return new AcceptedPolicyRegistration(token, previous);
        }
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
            }
        }
    }
}
