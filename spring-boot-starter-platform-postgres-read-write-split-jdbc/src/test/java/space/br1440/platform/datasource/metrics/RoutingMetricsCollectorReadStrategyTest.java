package space.br1440.platform.datasource.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что {@link RoutingMetricsCollector} регистрирует info-style gauge
 * {@code datasource.routing.read.strategy} с тегом {@code strategy} в нижнем регистре
 * и постоянным значением {@code 1.0}.
 *
 * <p>Тег генерируется из {@link Strategy#name().toLowerCase()}, поэтому
 * переименование enum автоматически меняет значение тега: SECONDARY_ONLY → secondary_only.
 */
class RoutingMetricsCollectorReadStrategyTest {

    @Test
    void readStrategyGauge_isRegisteredWithLoadBalancedTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RoutingMetricsCollector(registry, Strategy.LOAD_BALANCED);

        Gauge gauge = registry.find(RoutingMetricNames.GAUGE_READ_STRATEGY)
                .tag("strategy", "load_balanced")
                .gauge();

        assertThat(gauge).as("Gauge должен быть зарегистрирован с тегом strategy=load_balanced")
                .isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    void readStrategyGauge_isRegisteredWithSecondaryOnlyTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RoutingMetricsCollector(registry, Strategy.SECONDARY_ONLY);

        // Тег формируется автоматически: SECONDARY_ONLY.name().toLowerCase() = "secondary_only"
        Gauge gauge = registry.find(RoutingMetricNames.GAUGE_READ_STRATEGY)
                .tag("strategy", "secondary_only")
                .gauge();

        assertThat(gauge).as("Gauge должен быть зарегистрирован с тегом strategy=secondary_only")
                .isNotNull();
        assertThat(gauge.value()).isEqualTo(1.0);
    }
}
