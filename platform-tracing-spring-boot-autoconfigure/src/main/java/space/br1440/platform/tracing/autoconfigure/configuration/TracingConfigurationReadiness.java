package space.br1440.platform.tracing.autoconfigure.configuration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Состояние готовности application-side доставки конфигурации трассировки.
 *
 * <p>Это состояние не подменяет readiness Java Agent и не входит в accepted-state CAS payload.
 */
final class TracingConfigurationReadiness {

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.PENDING, "INITIAL_APPLY_PENDING", 0L));

    Snapshot current() {
        return current.get();
    }

    void recordInitial(TracingPolicyDeliveryResult result) {
        Objects.requireNonNull(result, "result");
        State state = switch (result.outcome()) {
            case APPLIED, UNCHANGED -> State.READY;
            case REJECTED, STALE, FAILED -> State.BLOCKED;
        };
        current.set(new Snapshot(state, result.messageCode(), result.acceptedSequence()));
    }

    void recordInitialFailure(String messageCode) {
        current.set(new Snapshot(State.BLOCKED, messageCode, 0L));
    }

    enum State {
        PENDING,
        READY,
        BLOCKED
    }

    record Snapshot(State state, String messageCode, long acceptedSequence) {

        Snapshot {
            state = Objects.requireNonNull(state, "state");
            messageCode = boundedToken(messageCode);
            if (acceptedSequence < 0L) {
                throw new IllegalArgumentException("acceptedSequence must not be negative");
            }
        }

        private static String boundedToken(String value) {
            Objects.requireNonNull(value, "messageCode");
            if (value.length() > 64) {
                throw new IllegalArgumentException(
                        "readiness messageCode exceeds 64 characters");
            }
            return value;
        }
    }
}
