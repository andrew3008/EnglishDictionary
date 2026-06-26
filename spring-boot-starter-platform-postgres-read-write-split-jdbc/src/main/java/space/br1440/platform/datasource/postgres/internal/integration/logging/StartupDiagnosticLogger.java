package space.br1440.platform.datasource.postgres.internal.integration.logging;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolProperties;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricNames;

@Slf4j
public final class StartupDiagnosticLogger implements ApplicationListener<ContextRefreshedEvent> {

    private static final String WRITE_TARGET_SERVER_TYPE = "primary";

    private final PgClusterProperties properties;
    private final boolean healthEnabled;
    private final boolean metricsEnabled;

    public StartupDiagnosticLogger(PgClusterProperties properties,
                                   boolean healthEnabled,
                                   boolean metricsEnabled) {
        this.properties = properties;
        this.healthEnabled = healthEnabled;
        this.metricsEnabled = metricsEnabled;
    }

    @Override
    public void onApplicationEvent(@NotNull ContextRefreshedEvent event) {
        logResolvedConfig();
    }

    public void logResolvedConfig() {
        if (!log.isInfoEnabled()) {
            return;
        }

        ReadTarget readTarget = resolveReadTarget(properties.read().mode());
        PoolProperties writePool = properties.pool().write();
        PoolProperties readPool = properties.pool().read();
        int driverPropCount = (properties.jdbc().properties() == null)
                ? 0
                : properties.jdbc().properties().size();

        log.info("""
                Postgres routing starter resolved config:
                  datasourceRoutingMode={}
                  nodes(count)={}, database={}
                  readMode={}
                  write: targetServerType={}, loadBalanceHosts={}, pool={}, maxPoolSize={}, minIdle={}, writerValidationMode={}
                  read:  targetServerType={}, loadBalanceHosts={}, pool={}, maxPoolSize={}, minIdle={}
                  driverProperties(count)={}, keys={}
                  health.enabled={}, metrics.enabled={}""",
                properties.datasourceRoutingMode(),
                properties.nodes().size(), properties.database(),
                properties.read().mode(),
                WRITE_TARGET_SERVER_TYPE, false,
                resolvePoolName(writePool.poolName(), RoutingMetricNames.DEFAULT_WRITE_POOL_NAME),
                writePool.maximumPoolSize(), describeMinIdle(writePool.minimumIdle()),
                writePool.writerValidationMode(),
                readTarget.targetServerType(), readTarget.loadBalanceHosts(),
                resolvePoolName(readPool.poolName(), RoutingMetricNames.DEFAULT_READ_POOL_NAME),
                readPool.maximumPoolSize(), describeMinIdle(readPool.minimumIdle()),
                driverPropCount,
                properties.jdbc().properties() == null ? "[]" : properties.jdbc().properties().keySet(),
                healthEnabled, metricsEnabled);
    }

    private static ReadTarget resolveReadTarget(ReadMode mode) {
        return switch (mode) {
            case SECONDARY_PREFERRED -> new ReadTarget("preferSecondary", false);
            case STRICT_SECONDARY -> new ReadTarget("secondary", false);
            case ANY_NODE_LOAD_BALANCED -> new ReadTarget("any", true);
        };
    }

    private static String resolvePoolName(String configuredName, String defaultName) {
        return StringUtils.isNotEmpty(configuredName) ? configuredName : defaultName;
    }

    private static String describeMinIdle(Integer minIdle) {
        return (minIdle == null) ? "fixed-size(=maxPoolSize)" : minIdle.toString();
    }

    private record ReadTarget(String targetServerType, boolean loadBalanceHosts) {
    }
}
