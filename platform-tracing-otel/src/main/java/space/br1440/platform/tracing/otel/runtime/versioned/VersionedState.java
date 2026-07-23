package space.br1440.platform.tracing.otel.runtime.versioned;

/**
 * Контракт неизменяемого снапшота состояния, управляемого {@link VersionedStateHolder} по
 * lock-free CAS-протоколу с семантикой last-known-good.
 * <p>
 * Инварианты, которые обязана соблюдать каждая реализация:
 * <ul>
 *   <li>Объект полностью иммутабелен (final-поля, без setter'ов) — снапшот публикуется через
 *       {@link java.util.concurrent.atomic.AtomicReference} без дополнительной синхронизации
 *       читателями;</li>
 *   <li>{@link #version()} монотонно возрастает при каждом успешном {@code compareAndSet} у
 *       владеющего holder'а (конвенция — {@code prev.version() + 1}), чтобы конкурентные читатели
 *       могли обнаружить устаревшее состояние без блокировок;</li>
 *   <li>Построение кандидата на обновление (функция, передаваемая в {@code VersionedStateHolder#tryUpdate})
 *       не должно иметь побочных эффектов — при конкуренции на CAS она может быть вызвана повторно,
 *       а отклонённый кандидат не должен быть виден никому кроме своего builder'а.</li>
 * </ul>
 */
public interface VersionedState {

    /**
     * Версия снапшота в рамках владеющего его {@link VersionedStateHolder}.
     * <p>
     * @return монотонно неубывающий номер версии; конвенция holder'а — {@code prev.version() + 1}
     */
    long version();

}
