package space.br1440.platform.datasource.config;

import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для свойств {@link PostgresClusterDataSourceProperties}, относящихся к read-стратегии.
 */
class PostgresClusterDataSourcePropertiesReadStrategyTest {

    @Test
    void readStrategy_defaultsToLoadBalanced() {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();

        assertThat(props.getReadReplica().getStrategy())
                .as("По умолчанию read-стратегия должна быть LOAD_BALANCED (read-heavy сервисы по умолчанию)")
                .isEqualTo(Strategy.LOAD_BALANCED);
    }

    @Test
    void readStrategy_canBeSetToSecondaryOnly() {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();

        props.getReadReplica().setStrategy(Strategy.SECONDARY_ONLY);

        assertThat(props.getReadReplica().getStrategy())
                .isEqualTo(Strategy.SECONDARY_ONLY);
    }

    @Test
    void readStrategy_enumContainsExpectedValues() {
        assertThat(Strategy.values())
                .containsExactly(Strategy.SECONDARY_ONLY, Strategy.LOAD_BALANCED);
    }
}
