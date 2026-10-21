package space.br1440.platform.tracing.otel.javaagent.control.settings;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsSourceAuthority;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;

public final class TracingSettingsStore {

    private final AtomicReference<TracingSettingsRevision> current;

    public TracingSettingsStore(AgentInstanceId agentInstanceId,
                                TracingSettings initialPolicy) {
        TracingSettingsRevision revision = new TracingSettingsRevision(
                agentInstanceId,
                0L,
                initialPolicy,
                SettingsSourceAuthority.BOOTSTRAP
        );
        this.current = new AtomicReference<>(revision);
    }

    public TracingSettingsRevision current() {
        return current.get();
    }

    public SettingsCommitResult commit(SettingsRevision expectedRevision,
                                       TracingSettings candidate,
                                       SettingsSourceAuthority sourceAuthority) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(sourceAuthority, "sourceAuthority");

        TracingSettingsRevision currentRevision = current.get();
        if (!matches(currentRevision, expectedRevision)) {
            return new SettingsCommitResult(SettingsCommitOutcome.STALE, currentRevision);
        }

        TracingSettingsRevision next = new TracingSettingsRevision(
                currentRevision.agentInstanceId(),
                Math.incrementExact(currentRevision.acceptedSequence()),
                candidate,
                sourceAuthority
        );

        if (current.compareAndSet(currentRevision, next)) {
            return new SettingsCommitResult(SettingsCommitOutcome.APPLIED, next);
        }

        return new SettingsCommitResult(SettingsCommitOutcome.STALE, current.get());
    }

    private static boolean matches(TracingSettingsRevision envelope,
                                   SettingsRevision expectedBase) {
        return (envelope.acceptedSequence() == expectedBase.acceptedSequence())
                && envelope.agentInstanceId().equals(expectedBase.agentInstanceId());
    }
}
