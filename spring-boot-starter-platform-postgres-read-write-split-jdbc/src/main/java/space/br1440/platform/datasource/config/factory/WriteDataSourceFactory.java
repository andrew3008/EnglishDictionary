package space.br1440.platform.datasource.config.factory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.datasource.config.HikariRoutingProperties;
import space.br1440.platform.datasource.config.JdbcUrlBuilder;
import space.br1440.platform.datasource.config.support.PoolDefaults;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.support.ConnectParametersMerger;
import space.br1440.platform.datasource.configuration.support.ReadPoolUrlStrategy;
import space.br1440.platform.datasource.configuration.support.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.configuration.support.RoutingStartupValidator;
import space.br1440.platform.datasource.metrics.RoutingMetricNames;

/**
 * Фабрика write-пула HikariCP. Публичный контракт — единственный метод
 * {@link #build(PostgresClusterDataSourceProperties, HikariRoutingProperties)}.
 *
 * <p>Здесь централизованно происходит:
 * <ul>
 *   <li>Стартовая валидация обязательных свойств ({@link RoutingStartupValidator#validate}).</li>
 *   <li>Single-run логирование активной read-стратегии ({@link ReadStrategyStartupLogger#logStrategy}).</li>
 *   <li>Сборка JDBC URL через {@link JdbcUrlBuilder} с общими {@code connection-properties};
 *       write-пул всегда {@code targetServerType=primary} и {@code loadBalanced=false}.</li>
 *   <li>Применение защищённых умолчаний write-пула через {@link PoolDefaults}.</li>
 * </ul>
 *
 * <p>Фабрика вызывается только из {@code @Bean}-метода {@code writeDataSource}. Если пользователь
 * переопределил этот бин, фабрика не инстанцируется в цепочке — соответственно, валидация
 * и логирование не запускаются. Такое же поведение было у исходного конфига.
 */
@Slf4j
public final class WriteDataSourceFactory {

    private final RoutingStartupValidator validator;
    private final ReadStrategyStartupLogger readStrategyLogger;

    public WriteDataSourceFactory(RoutingStartupValidator validator,
                                  ReadStrategyStartupLogger readStrategyLogger) {
        this.validator = validator;
        this.readStrategyLogger = readStrategyLogger;
    }

    /**
     * Собирает и возвращает write-{@link HikariDataSource} по свойствам маршрутизации и Hikari.
     */
    public HikariDataSource build(PostgresClusterDataSourceProperties props, HikariRoutingProperties hikari) {
        validator.validate(props);
        readStrategyLogger.logStrategy(props);

        String jdbcUrl = buildJdbcUrl(props);
        log.debug("Creating write HikariCP pool: url={}", jdbcUrl);

        HikariConfig config = newBaseConfig(props, jdbcUrl);
        hikari.getWrite().applyTo(config);

        PoolDefaults.applyPoolNameIfAbsent(config, RoutingMetricNames.DEFAULT_WRITE_POOL_NAME);
        PoolDefaults.applyWriteDefaults(config);

        return new HikariDataSource(config);
    }

    private static String buildJdbcUrl(PostgresClusterDataSourceProperties props) {
        // targetServerType write-пула — инвариант CQRS (всегда Primary).
        // loadBalanced=false: write-нагрузка не балансируется, всегда Primary.
        return JdbcUrlBuilder.buildUrl(
                String.join(",", props.getNodes()),
                props.getDatabase(),
                ReadPoolUrlStrategy.WRITE_TARGET_SERVER_TYPE,
                false,
                ConnectParametersMerger.merge(props.getConnectionProperties(), null)
        );
    }

    private static HikariConfig newBaseConfig(PostgresClusterDataSourceProperties props, String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(props.getCredentials().getUsername());
        config.setPassword(props.getCredentials().getPassword());
        return config;
    }
}
