package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire;

import java.util.Objects;

@SuppressWarnings("unused")
public sealed interface JmxOwnerState<T> permits JmxOwnerState.Present, JmxOwnerState.Absent {

    long generation();

    record Present<T>(T values, long generation) implements JmxOwnerState<T> {

        public Present {
            Objects.requireNonNull(values, "values");

            if (generation <= 0) {
                throw new IllegalArgumentException("generation присутствующего владельца должна быть положительной: " + generation);
            }
        }
    }

    record Absent<T>(long generation) implements JmxOwnerState<T> {

        public Absent {
            if (generation < 0) {
                throw new IllegalArgumentException("generation не может быть отрицательной: " + generation);
            }
        }
    }
}
