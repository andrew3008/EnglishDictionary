package space.br1440.platform.datasource.observation;

import com.zaxxer.hikari.HikariDataSource;

import java.util.function.Function;

/**
 * Извлекает имя HikariCP-пула из произвольного объекта (чаще всего — {@code javax.sql.DataSource}).
 *
 * <p>Используется как транспорт-специфичная реализация callback'а
 * {@link RoutingObservationInterceptor#RoutingObservationInterceptor(
 *     io.micrometer.observation.ObservationRegistry,
 *     RoutingObservationConvention, Function)}.
 * Core-модуль определяет интерфейс ({@code Function<Object,String>}) и не знает про HikariCP —
 * конкретная реализация добавляется в JDBC-стартере.
 *
 * <p>Класс без состояния: публичный статический {@link #INSTANCE} возвращает имя пула,
 * если объект является {@link HikariDataSource}, иначе {@code null}.
 */
public final class HikariPoolNameExtractor {

    /** Singleton экземпляр callback'а для передачи в {@link RoutingObservationInterceptor}. */
    public static final Function<Object, String> INSTANCE = target -> {
        if (target instanceof HikariDataSource hds) {
            return hds.getPoolName();
        }
        return null;
    };

    private HikariPoolNameExtractor() {
    }
}
