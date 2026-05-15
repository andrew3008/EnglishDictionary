package space.br1440.platform.datasource.metrics;

public interface RoutingMetricNames {

    String COUNTER_ROUTING_REQUESTS = "datasource.routing.requests";

    String GAUGE_READ_STRATEGY = "datasource.routing.read.strategy";

    String DEFAULT_WRITE_POOL_NAME = "postgres-cluster-write-pool";

    String DEFAULT_READ_POOL_NAME = "postgres-cluster-read-pool";

}
