package space.br1440.platform.datasource.postgres.api.jooq;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import org.jooq.DSLContext;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Callback-фабрика для CQRS-маршрутизации jOOQ-операций <b>вне</b> контекста Spring {@code @Transactional}.
 * <p>
 * Методы {@code read*} направляют операцию в read-пул ({@code readDataSource}), методы
 * {@code write*} — в write-пул ({@code writeDataSource}). Физическая нода PostgreSQL для READ
 * определяется отдельно через {@code ReadStrategy} (topology-слой).
 * <p>
 * Использует единственный {@link DSLContext}, привязанный к {@code @Primary} {@code LazyConnectionDataSourceProxy}.
 * Маршрутизация управляется через {@link DataSourceContextHolder} с гарантированным вызовом {@code clear()}
 * в блоке {@code try/finally} — вызывающий код не может забыть сбросить контекст.
 * <p>
 * Для транзакционных jOOQ-операций используйте {@code @Transactional(readOnly = true/false)}.
 * Эта фабрика — escape-hatch, а не основной API.
 *
 * <h3>Пример использования</h3>
 * <pre>{@code
 * // Чтение из read-пула (с возвратом результата)
 * List<Order> orders = dslFactory.read(dsl ->
 *     dsl.selectFrom(ORDERS).where(ORDERS.STATUS.eq("NEW")).fetchInto(Order.class)
 * );
 *
 * // Запись в write-пул (без возврата результата)
 * dslFactory.writeVoid(dsl ->
 *     dsl.update(ORDERS).set(ORDERS.STATUS, "DONE").where(ORDERS.ID.eq(id)).execute()
 * );
 * }</pre>
 */
public class RoutingDSLContextFactory {

    private final DSLContext dsl;

    public RoutingDSLContextFactory(DSLContext dsl) {
        this.dsl = dsl;
    }

    public <T> T read(Function<DSLContext, T> query) {
        DataSourceContextHolder.set(DataSourceType.READ);

        try {
            return query.apply(dsl);
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    public void readVoid(Consumer<DSLContext> query) {
        DataSourceContextHolder.set(DataSourceType.READ);

        try {
            query.accept(dsl);
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    public <T> T write(Function<DSLContext, T> query) {
        DataSourceContextHolder.set(DataSourceType.WRITE);

        try {
            return query.apply(dsl);
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    public void writeVoid(Consumer<DSLContext> query) {
        DataSourceContextHolder.set(DataSourceType.WRITE);

        try {
            query.accept(dsl);
        } finally {
            DataSourceContextHolder.clear();
        }
    }
}
