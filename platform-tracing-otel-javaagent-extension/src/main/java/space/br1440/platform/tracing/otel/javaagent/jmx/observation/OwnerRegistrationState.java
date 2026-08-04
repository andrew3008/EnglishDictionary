package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

/**
 * Presence-состояние владельца внутри {@link OwnerRegistrationSlot}. Отсутствие владельца
 * не теряет generation: {@code Absent} хранит generation последнего присутствовавшего
 * владельца, чтобы reattachment получил строго большую, а не начал заново с нуля.
 *
 * @param <T> тип заменяемого владельца (например, {@code SafeSpanExporter})
 */
public sealed interface OwnerRegistrationState<T> permits OwnerRegistrationState.Present, OwnerRegistrationState.Absent {

    long generation();

    record Present<T>(T owner, long generation) implements OwnerRegistrationState<T> {

        public Present {
            if (owner == null) {
                throw new IllegalArgumentException("owner не может быть null в Present — используйте Absent");
            }
            if (generation <= 0) {
                throw new IllegalArgumentException("generation присутствующего владельца должна быть положительной: " + generation);
            }
        }
    }

    record Absent<T>(long generation) implements OwnerRegistrationState<T> {

        public Absent {
            if (generation < 0) {
                throw new IllegalArgumentException("generation не может быть отрицательной: " + generation);
            }
        }
    }
}
