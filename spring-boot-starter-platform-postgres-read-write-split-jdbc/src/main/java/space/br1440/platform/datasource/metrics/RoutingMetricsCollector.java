package space.br1440.platform.datasource.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.routing.DataSourceType;

public class RoutingMetricsCollector {

    private final Counter writeCounter;
    private final Counter readCounter;

    public RoutingMetricsCollector(MeterRegistry registry,
                                   PostgresClusterDataSourceProperties.ReadReplica.Strategy strategy) {
        this.writeCounter = Counter.builder(RoutingMetricNames.COUNTER_ROUTING_REQUESTS)
                .tag("target", "write")
                .description("Queries routed to write DataSource")
                .register(registry);

        this.readCounter = Counter.builder(RoutingMetricNames.COUNTER_ROUTING_REQUESTS)
                .tag("target", "read")
                .description("Queries routed to read DataSource")
                .register(registry);

        Gauge.builder(RoutingMetricNames.GAUGE_READ_STRATEGY, () -> 1.0)
                .tag("strategy", strategy.name().toLowerCase())
                .description("Active read strategy profile (1=active)")
                .register(registry);
    }

    public void recordRouting(DataSourceType intent) {
        switch (intent) {
            case WRITE -> writeCounter.increment();
            case READ -> readCounter.increment();
        }
    }
}
