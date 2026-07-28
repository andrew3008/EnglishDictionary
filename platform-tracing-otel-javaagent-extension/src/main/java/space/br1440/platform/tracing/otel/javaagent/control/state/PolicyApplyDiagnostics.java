package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Метаданные попыток применения. Они намеренно не входят в accepted-state CAS payload.
 */
final class PolicyApplyDiagnostics {

    private final AtomicReference<PolicyApplyDiagnosticsSnapshot> snapshot =
            new AtomicReference<>(PolicyApplyDiagnosticsSnapshot.empty());

    PolicyApplyDiagnosticsSnapshot snapshot() {
        return snapshot.get();
    }

    void record(PolicyApplyAttempt attempt, PolicyApplyResult result) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        snapshot.updateAndGet(previous -> previous.next(attempt, result));
    }
}
