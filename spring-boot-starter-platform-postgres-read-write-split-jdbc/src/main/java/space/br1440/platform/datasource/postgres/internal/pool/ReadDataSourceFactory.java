package space.br1440.platform.datasource.postgres.internal.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolGroupProperties;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricNames;

public final class ReadDataSourceFactory {

    public HikariDataSource build(PgClusterProperties properties, PoolGroupProperties pool) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JdbcUrlBuilder.readUrl(properties));
        applyCredentials(config, properties);

        config.setReadOnly(true);
        pool.read().applyTo(config);

        String poolName = pool.read().poolName();
        if (StringUtils.isEmpty(poolName)) {
            poolName = RoutingMetricNames.DEFAULT_READ_POOL_NAME;
        }
        config.setPoolName(poolName);

        return new HikariDataSource(config);
    }

    private static void applyCredentials(HikariConfig config, PgClusterProperties properties) {
        PgClusterProperties.Credentials credentials = properties.credentials();

        if (StringUtils.isNotEmpty(credentials.username())) {
            config.setUsername(credentials.username());
        }

        if (StringUtils.isNotEmpty(credentials.password())) {
            config.setPassword(credentials.password());
        }
    }
}
