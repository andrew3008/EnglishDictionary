package space.br1440.platform.tracing.otel.control.protocol;

import java.util.Objects;

/**
 * Результат оценки {@link RuntimeControlMutationPolicy}.
 */
public record RuntimeControlMutationDecision(boolean allowed, String reason) {

    public RuntimeControlMutationDecision {
        Objects.requireNonNull(reason, "reason");
        if (allowed && !reason.isEmpty()) {
            throw new IllegalArgumentException("allowed decision must not carry a rejection reason");
        }
        if (!allowed && reason.isBlank()) {
            throw new IllegalArgumentException("rejected decision must carry a reason");
        }
    }

    public static RuntimeControlMutationDecision permitted() {
        return new RuntimeControlMutationDecision(true, "");
    }

    public static RuntimeControlMutationDecision rejected(String reason) {
        return new RuntimeControlMutationDecision(false, reason);
    }
}
