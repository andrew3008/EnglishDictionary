package space.br1440.platform.datasource.postgres.internal.integration.observation;

import io.micrometer.observation.Observation;
import lombok.Getter;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;

@Getter
public class RoutingObservationContext extends Observation.Context {

    private final DataSourceType intent;
    private final String poolName;

    public RoutingObservationContext(DataSourceType intent, String poolName) {
        this.intent = intent;
        this.poolName = poolName;
    }
}
