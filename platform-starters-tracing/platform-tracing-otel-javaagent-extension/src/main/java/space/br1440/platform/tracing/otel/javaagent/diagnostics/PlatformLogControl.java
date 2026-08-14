package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class PlatformLogControl {

    public enum LogLevel {
        OFF,
        ERROR,
        WARN,
        INFO,
        DEBUG,
        TRACE;

        public int severity() {
            return ordinal();
        }

        @SuppressWarnings("SameParameterValue")
        static LogLevel parseOrDefault(String raw, LogLevel fallback) {
            if (raw == null) {
                return fallback;
            }

            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                return fallback;
            }

            for (LogLevel level : values()) {
                if (level.name().equals(normalized)) {
                    return level;
                }
            }

            return fallback;
        }
    }

    private static final LogLevel PARSE_FALLBACK = LogLevel.WARN;
    private static final PlatformLogControl SHARED = new PlatformLogControl();

    private final AtomicReference<TracingSettingsSource> acceptedPolicy =
            new AtomicReference<>(TracingSettingsSource.isolatedDefaults());
    private final AtomicLong bindCount = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final Object bindLock = new Object();
    private long activeGeneration;

    public static PlatformLogControl shared() {
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

    public LogLevel getLevel() {
        String raw = acceptedPolicy.get().current().diagnosticsLogLevel();
        return LogLevel.parseOrDefault(raw, PARSE_FALLBACK);
    }

    public void setLevel(LogLevel newLevel) {
        if (newLevel == null) {
            return;
        }

        acceptedPolicy.get().applyLogLevel(newLevel.name());
    }

    public boolean setLevel(String raw) {
        if (raw == null) {
            return false;
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return acceptedPolicy.get().applyLogLevel(normalized);
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
            return new AcceptedPolicyRegistration(token, previous);
        }
    }

    public boolean isEnabled(LogLevel messageLevel) {
        Objects.requireNonNull(messageLevel, "messageLevel");
        return (messageLevel != LogLevel.OFF) && (getLevel().severity() >= messageLevel.severity());
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
