package space.br1440.platform.datasource.configuration.support;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;

/**
 * Пишет в лог активную read-стратегию стартера и предупреждает о сомнительных конфигурациях.
 */
@Slf4j
public final class ReadStrategyStartupLogger {

    public void logStrategy(PostgresClusterDataSourceProperties props) {
        PostgresClusterDataSourceProperties.ReadReplica.Strategy strategy = props.getReadReplica().getStrategy();
        if (strategy == PostgresClusterDataSourceProperties.ReadReplica.Strategy.LOAD_BALANCED) {
            logLoadBalanced(props);
        } else {
            logSecondaryOnly();
        }
    }

    private void logLoadBalanced(PostgresClusterDataSourceProperties props) {
        int nodeCount = (props.getNodes() == null) ? 0 : props.getNodes().size();
        if (nodeCount < 2) {
            log.warn("Read strategy LOAD_BALANCED is configured but only {} pg-node(s) defined. "
                            + "Load balancing has no effect in single-node topology.",
                    nodeCount);
        }

        log.info("Read strategy: LOAD_BALANCED — read pool targetServerType={}, loadBalanceHosts=true",
                ReadPoolUrlStrategy.LOAD_BALANCED_TARGET_SERVER_TYPE);
    }

    private void logSecondaryOnly() {
        log.info("Read strategy: SECONDARY_ONLY — read pool targetServerType={}",
                ReadPoolUrlStrategy.SECONDARY_ONLY_TARGET_SERVER_TYPE);
    }
}
