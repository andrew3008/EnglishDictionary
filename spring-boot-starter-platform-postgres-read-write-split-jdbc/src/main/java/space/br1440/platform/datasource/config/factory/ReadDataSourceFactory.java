package space.br1440.platform.datasource.config.factory;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.datasource.config.HikariRoutingProperties;
import space.br1440.platform.datasource.config.JdbcUrlBuilder;
import space.br1440.platform.datasource.config.support.PoolDefaults;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.support.ConnectParametersMerger;
import space.br1440.platform.datasource.configuration.support.ReadPoolUrlSpec;
import space.br1440.platform.datasource.configuration.support.ReadPoolUrlStrategy;
import space.br1440.platform.datasource.metrics.RoutingMetricNames;

import java.util.Map;

/**
 * Фабрика read-пула HikariCP. Публичный контракт — единственный метод
 * {@link #build(PostgresClusterDataSourceProperties, HikariRoutingProperties)}.
 *
 * <p>Последовательность сборки:
 * <ol>
 *   <li>{@link ReadPoolUrlStrategy#resolve} → {@link ReadPoolUrlSpec} с эффективными
 *       {@code targetServerType}, флагом {@code loadBalanced} и read-специфичными
 *       driver-параметрами.</li>
 *   <li>{@link ConnectParametersMerger#merge} — общие {@code connection-properties} + read extraParams.</li>
 *   <li>{@link JdbcUrlBuilder#buildUrl} — финальный multi-host JDBC URL; флаг
 *       {@code loadBalanced} превращается в driver-property {@code loadBalanceHosts=true}.</li>
 *   <li>{@link HikariConfig#setReadOnly(boolean) setReadOnly(true)} — read-пул всегда read-only,
 *       DML/DDL идут через write-пул.</li>
 *   <li>{@link PoolDefaults#applyPoolNameIfAbsent} — дефолтное имя, если пользователь не задал.</li>
 * </ol>
 *
 * <p>Валидация и startup-логирование вынесены в {@link WriteDataSourceFactory}: это гарантирует
 * единичный запуск этих сайд-эффектов при нормальной сборке пары пулов.
 */
@Slf4j
public final class ReadDataSourceFactory {

    /**
     * Собирает и возвращает read-{@link HikariDataSource} по свойствам маршрутизации и Hikari.
     */
    public HikariDataSource build(PostgresClusterDataSourceProperties props, HikariRoutingProperties hikari) {
        ReadPoolUrlSpec spec = ReadPoolUrlStrategy.resolve(props);
        Map<String, String> merged = ConnectParametersMerger.merge(props.getConnectionProperties(), spec.extraParams());

        String jdbcUrl = JdbcUrlBuilder.buildUrl(
                String.join(",", props.getNodes()),
                props.getDatabase(),
                spec.targetServerType(),
                spec.loadBalanced(),
                merged
        );
        log.debug("Creating read HikariCP pool: url={}", jdbcUrl);

        HikariConfig config = newBaseConfig(props, jdbcUrl);
        hikari.getRead().applyTo(config);

        PoolDefaults.applyPoolNameIfAbsent(config, RoutingMetricNames.DEFAULT_READ_POOL_NAME);

        return new HikariDataSource(config);
    }

    private static HikariConfig newBaseConfig(PostgresClusterDataSourceProperties props, String jdbcUrl) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(props.getCredentials().getUsername());
        config.setPassword(props.getCredentials().getPassword());
        // read-only enforced на уровне пула: предотвращает случайные DML через read-DataSource.
        config.setReadOnly(true);
        return config;
    }
}
