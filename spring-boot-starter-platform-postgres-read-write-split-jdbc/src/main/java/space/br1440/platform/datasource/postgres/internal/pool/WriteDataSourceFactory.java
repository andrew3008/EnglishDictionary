package space.br1440.platform.datasource.postgres.internal.pool;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolGroupProperties;
import space.br1440.platform.datasource.postgres.api.properties.WriterValidationMode;
import space.br1440.platform.datasource.postgres.internal.integration.logging.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricNames;

public final class WriteDataSourceFactory {

    private static final String WRITE_POOL_ENSURE_PRIMARY_INIT_SQL = """
        DO $$
        BEGIN
            IF pg_is_in_recovery() THEN
                RAISE EXCEPTION 'write pool: connection rejected, node is standby (replica)';
            END IF;
        END
        $$""";

    private static final String DEFAULT_WRITE_CONNECTION_TEST_QUERY = "SELECT 1";

    private final PgClusterPropertiesValidator validator;
    private final ReadStrategyStartupLogger readStrategyLogger;

    public WriteDataSourceFactory(PgClusterPropertiesValidator validator,
                                  ReadStrategyStartupLogger readStrategyLogger) {
        this.validator = validator;
        this.readStrategyLogger = readStrategyLogger;
    }

    public HikariDataSource build(PgClusterProperties properties, PoolGroupProperties pool) {
        validator.validate(properties);
        readStrategyLogger.logReadMode(properties);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(JdbcUrlBuilder.writeUrl(properties));
        applyCredentials(config, properties);
        pool.write().applyTo(config);

        String poolName = pool.write().poolName();
        if (StringUtils.isEmpty(poolName)) {
            poolName = RoutingMetricNames.DEFAULT_WRITE_POOL_NAME;
        }
        config.setPoolName(poolName);

        WriterValidationMode validationMode = pool.write().writerValidationMode();
        applyWriterValidation(config, validationMode);

        return new HikariDataSource(config);
    }

    private static void applyWriterValidation(HikariConfig config, WriterValidationMode mode) {
        String initSql = (mode == WriterValidationMode.PATRONI_PRIMARY_CHECK)
                ? WRITE_POOL_ENSURE_PRIMARY_INIT_SQL
                : null;

        String testQuery = (mode == WriterValidationMode.JDBC4)
                ? null
                : DEFAULT_WRITE_CONNECTION_TEST_QUERY;

        if (StringUtils.isEmpty(config.getConnectionInitSql()) && initSql != null) {
            config.setConnectionInitSql(initSql);
        }

        if (StringUtils.isEmpty(config.getConnectionTestQuery()) && testQuery != null) {
            config.setConnectionTestQuery(testQuery);
        }
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
