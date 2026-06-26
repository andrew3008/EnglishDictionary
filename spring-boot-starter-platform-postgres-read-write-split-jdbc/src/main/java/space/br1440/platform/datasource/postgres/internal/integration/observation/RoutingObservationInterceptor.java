package space.br1440.platform.datasource.postgres.internal.integration.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;

import javax.sql.DataSource;

public class RoutingObservationInterceptor {

    private final ObservationRegistry registry;
    private final RoutingObservationConvention convention;

    public RoutingObservationInterceptor(ObservationRegistry registry,
                                         RoutingObservationConvention convention) {
        this.registry = registry;
        this.convention = convention;
    }

    public Observation createObservation(DataSourceType intent, DataSource dataSource) {
        String poolName = PoolNames.nameOf(dataSource);
        RoutingObservationContext context = new RoutingObservationContext(intent, poolName);
        return Observation.createNotStarted(convention, () -> context, registry);
    }
}
