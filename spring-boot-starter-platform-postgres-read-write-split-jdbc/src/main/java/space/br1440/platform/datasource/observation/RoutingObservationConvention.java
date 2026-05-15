package space.br1440.platform.datasource.observation;

import io.micrometer.common.KeyValues;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import org.jetbrains.annotations.NotNull;

/**
 * Конвенция именования для observation CQRS-маршрутизации DataSource.
 * <p>
 * Потребители могут зарегистрировать свою реализацию как бин,
 * чтобы переопределить имя observation или набор тегов.
 *
 * <h3>Low-cardinality tags (по умолчанию)</h3>
 * <ul>
 *   <li>{@code db.system} = {@code postgresql}</li>
 *   <li>{@code db.routing.target} = {@code write} / {@code read} (intent-словарь)</li>
 *   <li>{@code db.routing.pool} = имя HikariCP-пула ({@code postgres-cluster-write-pool} / {@code postgres-cluster-read-pool})</li>
 * </ul>
 */
public interface RoutingObservationConvention extends ObservationConvention<RoutingObservationContext> {

    @Override
    default boolean supportsContext(Observation.@NotNull Context context) {
        return context instanceof RoutingObservationContext;
    }

    @Override
    default String getName() {
        return "datasource.routing";
    }

    class Default implements RoutingObservationConvention {

        @Override
        public @NotNull KeyValues getLowCardinalityKeyValues(RoutingObservationContext context) {
            String target = switch (context.getIntent()) {
                case WRITE -> "write";
                case READ -> "read";
            };

            return KeyValues.of(
                    "db.system", "postgresql",
                    "db.routing.target", target,
                    "db.routing.pool", context.getPoolName() != null ? context.getPoolName() : "unknown"
            );
        }
    }
}
