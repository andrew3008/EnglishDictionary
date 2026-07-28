package space.br1440.platform.tracing.autoconfigure.configuration;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class TracingPolicyDeliveryDiagnostics {

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(Snapshot.empty());

    Snapshot current() {
        return current.get();
    }

    void record(
            TracingPolicySource source,
            String sourceRevision,
            TracingPolicyDeliveryResult result) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(result, "result");
        current.set(new Snapshot(
                result.attemptId(),
                source.name(),
                bounded(sourceRevision, 256),
                result.outcome().name(),
                result.messageCode(),
                result.acceptedSequence(),
                Instant.now()));
    }

    record Snapshot(
            String attemptId,
            String source,
            String sourceRevision,
            String outcome,
            String messageCode,
            long acceptedSequence,
            Instant timestamp) {

        Snapshot {
            attemptId = bounded(attemptId, 36);
            source = bounded(source, 32);
            sourceRevision = bounded(sourceRevision, 256);
            outcome = bounded(outcome, 32);
            messageCode = bounded(messageCode, 64);
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
        }

        private static Snapshot empty() {
            return new Snapshot("", "", "", "", "", 0L, Instant.EPOCH);
        }
    }

    private static String bounded(String value, int maxLength) {
        String normalized = value == null ? "" : value;
        return normalized.length() <= maxLength
                ? normalized
                : normalized.substring(0, maxLength);
    }
}
