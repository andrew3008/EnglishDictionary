package space.br1440.platform.datasource.postgres.internal.integration.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import org.jetbrains.annotations.NotNull;

/**
 * Конвенция именования observation для CQRS-маршрутизации DataSource.
 * <p>
 * Потребители могут зарегистрировать свою реализацию как бин,
 * чтобы переопределить имя observation или набор тегов.
 *
 * <h3>Low-cardinality tags (по умолчанию)</h3>
 * <ul>
 *   <li>{@code db.system.name} = {@code postgresql} — OTel semantic convention для системы БД.</li>
 *   <li>{@code db.connection.pool.name} = имя HikariCP-пула
 *       ({@code postgres-cluster-write-pool} / {@code postgres-cluster-read-pool}) —
 *       OTel semantic convention для пула соединений.</li>
 *   <li>{@code db.routing.target} = {@code write} / {@code read} — платформенное расширение
 *       поверх OTel: CQRS-намерение запроса (intent-словарь).</li>
 * </ul>
 *
 * <p>Имена {@code db.system.name} и {@code db.connection.pool.name} соответствуют
 * <a href="https://opentelemetry.io/docs/specs/semconv/database/database-spans/">OTel Database Semantic Conventions</a>.
 */
public interface RoutingObservationConvention extends ObservationConvention<RoutingObservationContext> {

    String OBSERVATION_NAME = "datasource.routing";

    String KEY_DB_SYSTEM_NAME = "db.system.name";
    String KEY_DB_CONNECTION_POOL_NAME = "db.connection.pool.name";
    String KEY_DB_ROUTING_TARGET = "db.routing.target";

    String UNKNOWN_POOL = "unknown";

    @Override
    default boolean supportsContext(Observation.@NotNull Context context) {
        return context instanceof RoutingObservationContext;
    }

    @Override
    default String getName() {
        return OBSERVATION_NAME;
    }

    class Default implements RoutingObservationConvention {

        @Override
        public @NotNull KeyValues getLowCardinalityKeyValues(RoutingObservationContext context) {
            String target = switch (context.getIntent()) {
                case WRITE -> "write";
                case READ -> "read";
            };

            String poolName = (context.getPoolName() != null) ? context.getPoolName() : UNKNOWN_POOL;

            return KeyValues.of(
                    KEY_DB_SYSTEM_NAME, "postgresql",
                    KEY_DB_CONNECTION_POOL_NAME, poolName,
                    KEY_DB_ROUTING_TARGET, target
            );
        }
    }
}
