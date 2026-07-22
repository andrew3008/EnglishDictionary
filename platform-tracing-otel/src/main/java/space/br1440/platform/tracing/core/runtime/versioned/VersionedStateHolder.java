package space.br1440.platform.tracing.core.runtime.versioned;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Lock-free CAS holder for immutable {@link VersionedState} snapshots with last-known-good semantics.
 * <p>
 * Agent-internal runtime primitive (sampler / scrubbing / validation policy holders in
 * {@code otel-extension}). Not part of the public application SDK.
 * <p>
 * The {@code builder} passed to {@link #tryUpdate(UnaryOperator)} and
 * {@link #tryUpdate(UnaryOperator, Predicate)} must be side-effect-free: it may run multiple times
 * under CAS contention.
 */
public final class VersionedStateHolder<T extends VersionedState> {

    private final AtomicReference<T> ref;

    public VersionedStateHolder(T initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial snapshot must not be null");
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
