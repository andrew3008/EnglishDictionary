package space.br1440.platform.tracing.otel.javaagent.propagation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

public final class PlatformPropagationGate {

    private static final PlatformPropagationGate SHARED = new PlatformPropagationGate();
    private static final TracingSettings UNBOUND_POLICY = TracingSettingsSource.defaultPolicy();

    private long activeGeneration;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<TracingSettingsSource> acceptedPolicy = new AtomicReference<>();
    private final Object bindLock = new Object();

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
            acceptedPolicy.set(null);
            activeGeneration = 0L;
        }
    }

    public boolean isEnabled() {
        TracingSettingsSource bound = acceptedPolicy.get();
        if (bound == null) {
            return UNBOUND_POLICY.traceControlHeadersEnabled();
        }

        return bound.current().traceControlHeadersEnabled();
    }

    public AutoCloseable bindAcceptedPolicy(TracingSettingsSource policy) {
        Objects.requireNonNull(policy, "policy");

        synchronized (bindLock) {
            if (activeGeneration != 0L) {
                String message = "PlatformPropagationGate already has an active accepted-policy registration";
                throw new IllegalStateException(message);
            }

            long token = generation.incrementAndGet();
            acceptedPolicy.set(policy);
            activeGeneration = token;
            return new AcceptedPolicyRegistration(token);
        }
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

                acceptedPolicy.set(null);
                activeGeneration = 0L;
            }
        }
    }
}
