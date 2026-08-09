package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

public sealed interface OwnerRegistrationState<T> permits OwnerRegistrationState.Present, OwnerRegistrationState.Absent {

    long generation();

    record Present<T>(T owner, long generation) implements OwnerRegistrationState<T> {

        public Present {
            if (owner == null) {
                throw new IllegalArgumentException("owner must not be null in Present — use Absent instead");
            }

            if (generation <= 0) {
                throw new IllegalArgumentException("generation of a present owner must be positive: " + generation);
            }
        }
    }

    record Absent<T>(long generation) implements OwnerRegistrationState<T> {

        public Absent {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative: " + generation);
            }
        }
    }
}
