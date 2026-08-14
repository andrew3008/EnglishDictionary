package space.br1440.platform.tracing.otel.javaagent.control.settings;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.ExpectedSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;

public final class TracingSettingsStore {

    private final AtomicReference<TracingSettingsRevision> current;

    public TracingSettingsStore(String runtimeInstanceId, TracingSettings initialPolicy) {
        TracingSettingsRevision revision = new TracingSettingsRevision(runtimeInstanceId, 0L, initialPolicy);
        this.current = new AtomicReference<>(revision);
    }

    public TracingSettingsRevision current() {
        return current.get();
    }

    public SettingsCommitResult commit(ExpectedSettingsRevision expectedRevision, TracingSettings candidate) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(candidate, "candidate");

        TracingSettingsRevision currentRevision = current.get();
        if (!matches(currentRevision, expectedRevision)) {
            return new SettingsCommitResult(SettingsCommitOutcome.STALE, currentRevision);
        }

        TracingSettingsRevision next = new TracingSettingsRevision(
                currentRevision.runtimeInstanceId(),
                Math.incrementExact(currentRevision.acceptedSequence()),
                candidate
        );

        if (current.compareAndSet(currentRevision, next)) {
            return new SettingsCommitResult(SettingsCommitOutcome.APPLIED, next);
        }

        return new SettingsCommitResult(SettingsCommitOutcome.STALE, current.get());
    }

    private static boolean matches(TracingSettingsRevision envelope,
                                   ExpectedSettingsRevision expectedBase) {
        return (envelope.acceptedSequence() == expectedBase.acceptedSequence())
                && envelope.runtimeInstanceId().equals(expectedBase.runtimeInstanceId());
    }
}
