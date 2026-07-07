package space.br1440.platform.tracing.api.util;

/**
 * Поставщик значения, способный пробросить checked {@link Exception}.
 * <p>
 * Платформенный аналог {@code java.util.function.Supplier}, не требующий обёрток вида
 * {@code try/catch + wrap-in-RuntimeException} при работе с lambda-выражениями,
 * бросающими checked-исключения.
 * <p>
 * Семантика повторяет {@code org.springframework.util.function.ThrowingSupplier} из Spring 6,
 * но интерфейс объявлен локально, чтобы не иметь зависимость API-модуля от Spring.
 */
@FunctionalInterface
public interface ThrowingSupplier<T> {

    T get() throws Exception;

}
