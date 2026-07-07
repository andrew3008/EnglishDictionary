package space.br1440.platform.tracing.api.mdc;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Расширяемая цепочка чтения {@code platform.remote.service} помимо ThreadLocal MDC.
 */
@UtilityClass
public final class RemoteServiceContextReaders {

    private static final List<Supplier<Optional<String>>> READERS = new CopyOnWriteArrayList<>();

    public static void register(Supplier<Optional<String>> reader) {
        if (reader != null) {
            READERS.add(reader);
        }
    }

    /**
     * Читает первое непустое значение из зарегистрированных readers.
     */
    public static Optional<String> readFirst() {
        for (Supplier<Optional<String>> reader : READERS) {
            try {
                Optional<String> value = reader.get();
                if (value != null && value.isPresent() && !value.get().isBlank()) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // ошибка mirror-reader не должна ломать error-handling
            }
        }

        return Optional.empty();
    }

    public static void clearForTesting() {
        READERS.clear();
    }
}
