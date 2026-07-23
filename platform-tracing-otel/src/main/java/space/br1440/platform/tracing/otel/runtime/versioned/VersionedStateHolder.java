package space.br1440.platform.tracing.otel.runtime.versioned;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Lock-free CAS-хранилище иммутабельных снапшотов {@link VersionedState} с семантикой
 * «последнего известного корректного состояния» (last-known-good).
 * <p>
 * Внутренний runtime-примитив агента (держатели политик сэмплирования, скрабинга и валидации
 * в {@code otel-extension}). Не является частью публичного SDK приложения.
 * <p>
 * Функция {@code builder}, передаваемая в {@link #tryUpdate(UnaryOperator)} и
 * {@link #tryUpdate(UnaryOperator, Predicate)}, должна быть свободна от побочных эффектов:
 * при конкуренции на CAS она может быть вызвана несколько раз.
 */
public final class VersionedStateHolder<T extends VersionedState>{

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
        Objects.requireNonNull(builder, "builder");

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

    /**
     * Перебрасывает t, если это JVM-фатальная ошибка (VirtualMachineError, LinkageError),
     * которую нельзя проглотить без риска запуска JVM в неопределённом состоянии.
     * Восстанавливает interrupt-флаг для InterruptedException.
     * <p>
     * Паттерн: Reactor Exceptions.throwIfFatal(), RxJava Exceptions.throwIfFatal().
     */
    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError || t instanceof LinkageError) {
            throw (Error) t;
        }

        if (t instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
