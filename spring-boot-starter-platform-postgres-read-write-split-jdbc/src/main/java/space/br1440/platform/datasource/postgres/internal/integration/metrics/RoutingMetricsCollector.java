package space.br1440.platform.datasource.postgres.internal.integration.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;

public class RoutingMetricsCollector {

    private final Counter writeCounter;
    private final Counter readCounter;

    public RoutingMetricsCollector(MeterRegistry registry,
                                   ReadMode readMode) {
        this.writeCounter = Counter.builder(RoutingMetricNames.COUNTER_ROUTING_REQUESTS)
                .tag(RoutingMetricNames.TAG_DB_ROUTING_TARGET, "write")
                .description("Queries routed to write DataSource")
                .register(registry);

        this.readCounter = Counter.builder(RoutingMetricNames.COUNTER_ROUTING_REQUESTS)
                .tag(RoutingMetricNames.TAG_DB_ROUTING_TARGET, "read")
                .description("Queries routed to read DataSource")
                .register(registry);

        Gauge.builder(RoutingMetricNames.GAUGE_READ_STRATEGY, () -> 1.0)
                .tag(RoutingMetricNames.TAG_DB_ROUTING_READ_STRATEGY, readMode.name().toLowerCase())
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
