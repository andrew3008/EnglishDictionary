package space.br1440.platform.datasource.postgres.internal.integration.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что {@link RoutingMetricsCollector} регистрирует info-style gauge
 * {@code datasource.routing.read.strategy} с тегом {@code db.routing.read.strategy}
 * в нижнем регистре и постоянным значением {@code 1.0}.
 *
 * <p>Тег генерируется из {@link ReadMode#name()}{@code .toLowerCase()}, поэтому
 * переименование enum автоматически меняет значение тега.
 */
class RoutingMetricsCollectorReadStrategyTest {

    @Test
    void readStrategyGauge_isRegisteredWithAnyNodeLoadBalancedTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RoutingMetricsCollector(registry, ReadMode.ANY_NODE_LOAD_BALANCED);

        Gauge gauge = registry.find(RoutingMetricNames.GAUGE_READ_STRATEGY)
                .tag(RoutingMetricNames.TAG_DB_ROUTING_READ_STRATEGY, "any_node_load_balanced")
                .gauge();

        assertThat(gauge).as("Gauge должен быть зарегистрирован с тегом db.routing.read.strategy=any_node_load_balanced")
                .isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void readStrategyGauge_isRegisteredWithSecondaryPreferredTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RoutingMetricsCollector(registry, ReadMode.SECONDARY_PREFERRED);

        Gauge gauge = registry.find(RoutingMetricNames.GAUGE_READ_STRATEGY)
                .tag(RoutingMetricNames.TAG_DB_ROUTING_READ_STRATEGY, "secondary_preferred")
                .gauge();

        assertThat(gauge).as("Gauge должен быть зарегистрирован с тегом db.routing.read.strategy=secondary_preferred")
                .isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }
}
