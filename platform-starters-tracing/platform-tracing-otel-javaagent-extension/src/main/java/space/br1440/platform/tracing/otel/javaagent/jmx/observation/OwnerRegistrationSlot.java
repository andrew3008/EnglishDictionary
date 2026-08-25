package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class OwnerRegistrationSlot<T> {

    private final AtomicReference<OwnerRegistrationState<T>> current;

    public OwnerRegistrationSlot() {
        this.current = new AtomicReference<>(new OwnerRegistrationState.Absent<>(0L));
    }

    public OwnerRegistrationState<T> replace(T newOwner) {
        Objects.requireNonNull(newOwner, "newOwner");

        return current.updateAndGet(previous ->
                new OwnerRegistrationState.Present<>(newOwner, Math.incrementExact(previous.generation()))
        );
    }

    public OwnerRegistrationState<T> remove() {
        return current.updateAndGet(previous -> new OwnerRegistrationState.Absent<>(previous.generation()));
    }

    public OwnerRegistrationState<T> capture() {
        return current.get();
    }
}
