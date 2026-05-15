package space.br1440.platform.datasource.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy;
import space.br1440.platform.datasource.routing.DataSourceType;
import space.br1440.platform.datasource.support.assertj.RoutingAssertions;

@DisplayName("RoutingMetricsCollector")
class RoutingMetricsCollectorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RoutingMetricsCollector collector =
            new RoutingMetricsCollector(registry, Strategy.SECONDARY_ONLY);

    @Test
    void recordWrite_incrementsWriteCounter() {
        collector.recordRouting(DataSourceType.WRITE);

        RoutingAssertions.assertThat(registry)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS, "target", "write", 1.0);
    }

    @Test
    void recordRead_incrementsReadCounter() {
        collector.recordRouting(DataSourceType.READ);

        RoutingAssertions.assertThat(registry)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS, "target", "read", 1.0);
    }

    @Test
    void multipleRecords_accumulateCorrectly() {
        collector.recordRouting(DataSourceType.WRITE);
        collector.recordRouting(DataSourceType.WRITE);
        collector.recordRouting(DataSourceType.WRITE);
        collector.recordRouting(DataSourceType.READ);
        collector.recordRouting(DataSourceType.READ);

        RoutingAssertions.assertThat(registry)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS, "target", "write", 3.0)
                .hasCounterValue(RoutingMetricNames.COUNTER_ROUTING_REQUESTS, "target", "read", 2.0);
    }

    @Test
    @DisplayName("Гейдж стратегии регистрируется со значением 1.0 и тегом")
    void readStrategyGaugeRegistered() {
        RoutingAssertions.assertThat(registry)
                .hasGaugeValue("datasource.routing.read.strategy", "strategy", "secondary_only", 1.0);
    }
}
