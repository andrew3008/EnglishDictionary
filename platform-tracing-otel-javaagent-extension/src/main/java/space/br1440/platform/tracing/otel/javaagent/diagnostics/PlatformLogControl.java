package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyUpdateOutcome;

public final class PlatformLogControl {

    public enum LogLevel {
        OFF(0),
        ERROR(1),
        WARN(2),
        INFO(3),
        DEBUG(4),
        TRACE(5);

        private final int severity;

        LogLevel(int severity) {
            this.severity = severity;
        }

        public int severity() {
            return severity;
        }
    }

    private static final PlatformLogControl SHARED = new PlatformLogControl();

    private final AtomicReference<AcceptedPolicyAccess> acceptedPolicy = new AtomicReference<>(AcceptedPolicyAccess.isolatedDefaults());

    public static PlatformLogControl shared() {
        return SHARED;
    }

    public LogLevel getLevel() {
        return LogLevel.valueOf(acceptedPolicy.get().current().diagnosticsLogLevel());
    }

    public void setLevel(LogLevel newLevel) {
        if (newLevel != null) {
            acceptedPolicy.get().updateDiagnosticsLogLevel(newLevel.name());
        }
    }

    public boolean setLevel(String raw) {
        if (raw == null) {
            return false;
        }

        AcceptedPolicyUpdateOutcome outcome = acceptedPolicy.get().updateDiagnosticsLogLevel(raw.trim().toUpperCase(Locale.ROOT));
        return (outcome == AcceptedPolicyUpdateOutcome.APPLIED) || (outcome == AcceptedPolicyUpdateOutcome.UNCHANGED);
    }

    public void bindAcceptedPolicy(AcceptedPolicyAccess policy) {
        acceptedPolicy.set(Objects.requireNonNull(policy, "policy"));
    }

    public boolean isEnabled(LogLevel messageLevel) {
        return (messageLevel != LogLevel.OFF) && (getLevel().severity() >= messageLevel.severity());
    }
}
