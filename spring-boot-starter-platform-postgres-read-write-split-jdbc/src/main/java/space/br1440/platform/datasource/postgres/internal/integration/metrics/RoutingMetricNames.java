package space.br1440.platform.datasource.postgres.internal.integration.metrics;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class RoutingMetricNames {

    public String COUNTER_ROUTING_REQUESTS = "datasource.routing.requests";

    public String GAUGE_READ_STRATEGY = "datasource.routing.read.strategy";

    public String TAG_DB_ROUTING_TARGET = "db.routing.target";

    public String TAG_DB_ROUTING_READ_STRATEGY = "db.routing.read.strategy";

    public String DEFAULT_WRITE_POOL_NAME = "postgres-cluster-write-pool";

    public String DEFAULT_READ_POOL_NAME = "postgres-cluster-read-pool";

}
