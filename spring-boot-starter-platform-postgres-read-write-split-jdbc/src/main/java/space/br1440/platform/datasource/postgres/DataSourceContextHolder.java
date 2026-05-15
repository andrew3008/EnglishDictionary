package space.br1440.platform.datasource.postgres;

import space.br1440.platform.datasource.routing.DataSourceType;
import lombok.experimental.UtilityClass;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ThreadLocal-хранилище для явных переопределений CQRS-маршрутизации.
 * <p>
 * Хранит {@link DataSourceType} (WRITE/READ) — это <b>intent-слой</b>, который
 * потом транслируется в выбор {@code writeDataSource} или {@code readDataSource}
 * на уровне {@link RoutingDataSource}. Физическая нода PostgreSQL (Primary/Standby)
 * определяется отдельно — на topology-слое read-пула.
 * <p>
 * Использует стек ({@link Deque}) для корректной обработки вложенных вызовов:
 * вложенный {@code @UseReadReplica} внутри другого scope не теряет
 * контекст внешнего scope при очистке.
 * <p>
 * Это <b>escape-hatch</b>, а не основной механизм маршрутизации.
 * Основная маршрутизация управляется через
 * {@link org.springframework.transaction.support.TransactionSynchronizationManager}
 * внутри {@link RoutingDataSource#determineCurrentLookupKey()}.
 * <p>
 * Сценарии использования:
 * <ul>
 *   <li>{@code @UseReadReplica} — нетранзакционные jOOQ-вызовы в read-пул</li>
 *   <li>{@link space.br1440.platform.datasource.jooq.RoutingDSLContextFactory} — callback API</li>
 * </ul>
 * <p>
 *
 * <h3>Thread pool safety</h3>
 * Используется plain {@link ThreadLocal} (не {@code InheritableThreadLocal}) —
 * каждый поток имеет собственный стек. Для передачи контекста в async-потоки
 * используйте явные обёртки задач или библиотеку transmittable-thread-local.
 */
@UtilityClass
public final class DataSourceContextHolder {

    // TODO: при миграции на Java 24+ рассмотреть замену ThreadLocal на ScopedValue
    // ScopedValue требует structured concurrency
    // и не поддерживает push/pop семантику — потребуется изменение API.
    private static final ThreadLocal<Deque<DataSourceType>> CONTEXT = ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Push: добавляет routing override на вершину стека.
     * Каждый {@code set()} должен быть парен с {@link #clear()} в {@code finally}.
     */
    public static void set(DataSourceType intent) {
        CONTEXT.get().push(intent);
    }

    public static DataSourceType currentIfSet() {
        Deque<DataSourceType> stack = CONTEXT.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    public static void clear() {
        Deque<DataSourceType> stack = CONTEXT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }

        if (stack.isEmpty()) {
            CONTEXT.remove();
        }
    }
}
