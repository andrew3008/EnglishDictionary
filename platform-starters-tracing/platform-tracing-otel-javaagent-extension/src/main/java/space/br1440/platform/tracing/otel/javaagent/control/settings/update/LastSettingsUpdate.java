package space.br1440.platform.tracing.otel.javaagent.control.settings.update;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LastSettingsUpdate {

    private final AtomicReference<LastSettingsUpdateSnapshot> snapshot =
            new AtomicReference<>(LastSettingsUpdateSnapshot.empty());

    public LastSettingsUpdateSnapshot snapshot() {
        return snapshot.get();
    }

    void record(SettingsUpdateAttempt attempt, SettingsUpdateResult result) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        snapshot.updateAndGet(previous -> previous.next(attempt, result));
    }

    void recordPreApplyWithoutAttempt(SettingsUpdateResult result, Instant observedAt) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(observedAt, "observedAt");
        snapshot.updateAndGet(previous -> previous.nextWithoutAttempt(result, observedAt));
    }
}
