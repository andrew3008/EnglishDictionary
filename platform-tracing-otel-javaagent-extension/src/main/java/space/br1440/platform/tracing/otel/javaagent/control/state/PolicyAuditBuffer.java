package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ограниченный metadata-only журнал последних попыток применения.
 */
final class PolicyAuditBuffer {

    static final int CAPACITY = 32;

    private final ArrayDeque<PolicyAuditEntry> entries = new ArrayDeque<>(CAPACITY);

    synchronized void record(PolicyApplyAttempt attempt, PolicyApplyResult result) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(result, "result");
        if (entries.size() == CAPACITY) {
            entries.removeFirst();
        }
        entries.addLast(new PolicyAuditEntry(
                attempt,
                result.outcome(),
                result.messageCode(),
                result.accepted().acceptedSequence(),
                result.payloadDigest()));
    }

    synchronized List<PolicyAuditEntry> recent() {
        return List.copyOf(new ArrayList<>(entries));
    }
}
