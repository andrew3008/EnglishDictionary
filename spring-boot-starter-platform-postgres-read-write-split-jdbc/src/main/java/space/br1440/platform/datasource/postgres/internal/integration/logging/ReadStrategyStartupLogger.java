package space.br1440.platform.datasource.postgres.internal.integration.logging;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

@Slf4j
public final class ReadStrategyStartupLogger {

    private static final String SECONDARY_PREFERRED_TARGET = "preferSecondary";
    private static final String STRICT_SECONDARY_TARGET = "secondary";
    private static final String ANY_NODE_TARGET = "any";

    public void logReadMode(PgClusterProperties props) {
        ReadMode mode = props.read().mode();
        switch (mode) {
            case SECONDARY_PREFERRED -> log.info(
                    "Read mode: SECONDARY_PREFERRED — read pool targetServerType={}",
                    SECONDARY_PREFERRED_TARGET);

            case STRICT_SECONDARY -> log.info(
                    "Read mode: STRICT_SECONDARY — read pool targetServerType={} (no fallback to primary)",
                    STRICT_SECONDARY_TARGET);

            case ANY_NODE_LOAD_BALANCED -> logAnyNodeLoadBalanced(props);
        }
    }

    private void logAnyNodeLoadBalanced(PgClusterProperties props) {
        int nodeCount = (props.nodes() == null) ? 0 : props.nodes().size();
        log.warn("Read mode: ANY_NODE_LOAD_BALANCED — read pool may use primary "
                        + "(targetServerType={}, loadBalanceHosts=true). Configured cluster nodes: {}.",
                ANY_NODE_TARGET, nodeCount);
    }
}
