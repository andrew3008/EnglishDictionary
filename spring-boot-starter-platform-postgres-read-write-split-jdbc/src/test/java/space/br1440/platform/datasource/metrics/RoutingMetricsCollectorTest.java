package space.br1440.platform.datasource.postgres.internal.integration.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

@DisplayName("RoutingMetricsCollector")
class RoutingMetricsCollectorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RoutingMetricsCollector collector =
            new RoutingMetricsCollector(registry, ReadMode.SECONDARY_PREFERRED);

    @Test
    void recordWrite_incrementsWriteCounter() {
        collector.recordRouting(DataSourceType.WRITE);

        RoutingAssertions.assertThat(registry)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS,
                        RoutingMetricNames.TAG_DB_ROUTING_TARGET, "write", 1.0);
    }

    @Test
    void recordRead_incrementsReadCounter() {
        collector.recordRouting(DataSourceType.READ);

        RoutingAssertions.assertThat(registry)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS,
                        RoutingMetricNames.TAG_DB_ROUTING_TARGET, "read", 1.0);
    }

    @Test
    void multipleRecords_accumulateCorrectly() {
        collector.recordRouting(DataSourceType.WRITE);
        collector.recordRouting(DataSourceType.WRITE);
        collector.recordRouting(DataSourceType.WRITE);
        collector.recordRouting(DataSourceType.READ);
        collector.recordRouting(DataSourceType.READ);

        RoutingAssertions.assertThat(registry)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS,
                        RoutingMetricNames.TAG_DB_ROUTING_TARGET, "write", 3.0)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS,
                        RoutingMetricNames.TAG_DB_ROUTING_TARGET, "read", 2.0);
    }

    @Test
    @DisplayName("Гейдж стратегии регистрируется со значением 1.0 и тегом db.routing.read.strategy")
    void readStrategyGaugeRegistered() {
        RoutingAssertions.assertThat(registry)
                .hasGaugeValue(RoutingMetricNames.GAUGE_READ_STRATEGY,
                        RoutingMetricNames.TAG_DB_ROUTING_READ_STRATEGY, "secondary_preferred", 1.0);
    }
}
