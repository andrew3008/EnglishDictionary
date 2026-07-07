package space.br1440.platform.tracing.api.runtime.state;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public final class VersionedStateHolder<T extends VersionedState> {

    private final AtomicReference<T> ref;

    public VersionedStateHolder(T initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial config snapshot must not be null");
        }

        this.ref = new AtomicReference<>(initial);
    }

    public T current() {
        return ref.get();
    }

    public long version() {
        return ref.get().version();
    }

    public boolean tryUpdate(UnaryOperator<T> builder) {
        return tryUpdate(builder, null);
    }

    public boolean tryUpdate(UnaryOperator<T> builder, Predicate<T> validator) {
        if (builder == null) {
            return false;
        }

        for (; ; ) {
            T previous = ref.get();
            T candidate;
            try {
                candidate = builder.apply(previous);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                return false;
            }

            if (candidate == null) {
                return false;
            }

            if (validator != null) {
                boolean valid;
                try {
                    valid = validator.test(candidate);
                } catch (Throwable t) {
                    rethrowIfFatal(t);
                    return false;
                }

                if (!valid) {
                    return false;
                }
            }

            if (ref.compareAndSet(previous, candidate)) {
                return true;
            }
        }
    }

    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof LinkageError) {
            throw (Error) t;
        }

        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
