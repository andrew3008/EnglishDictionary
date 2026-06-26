package space.br1440.platform.datasource.postgres.internal.pool;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.JdbcProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.PoolGroupProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolProperties;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.properties.ReadReplicaProperties;
import space.br1440.platform.datasource.postgres.api.properties.RetryProperties;
import space.br1440.platform.datasource.postgres.api.properties.WriterValidationMode;
import space.br1440.platform.datasource.postgres.internal.integration.logging.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricNames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Write/ReadDataSourceFactory — JDBC URL и параметры пулов")
class DataSourceFactoryJdbcUrlTest {

    private final WriteDataSourceFactory writeFactory = new WriteDataSourceFactory(
            new PgClusterPropertiesValidator(),
            new ReadStrategyStartupLogger()
    );
    private final ReadDataSourceFactory readFactory = new ReadDataSourceFactory();

    private static PoolGroupProperties defaultPool() {
        return new PoolGroupProperties(lazyInitWritePool(), lazyInitReadPool());
    }

    /** Пул без fail-fast connect — только для unit-тестов URL/имени. */
    private static PoolProperties lazyInitWritePool() {
        return new PoolProperties(
                null, 10, null, 30_000, 300_000, 60_000, null, 0, -1,
                WriterValidationMode.PATRONI_PRIMARY_CHECK);
    }

    private static PoolProperties lazyInitReadPool() {
        return new PoolProperties(
                null, 10, null, 30_000, 300_000, 60_000, null, 0, -1,
                WriterValidationMode.JDBC4);
    }

    private static PgClusterProperties props(ReadMode mode) {
        return new PgClusterProperties(
                DataSourceRoutingMode.CLIENT,
                List.of(new PgNode("10.0.0.1", 5432), new PgNode("10.0.0.2", 5432)),
                "orders",
                null,
                null,
                new ReadReplicaProperties(mode),
                defaultPool(),
                new RetryProperties(true));
    }

    private static PgClusterProperties propsWithJdbc(ReadMode mode, Map<String, String> jdbcProps) {
        return new PgClusterProperties(
                DataSourceRoutingMode.CLIENT,
                List.of(new PgNode("host1", 5432)),
                "db",
                null,
                new JdbcProperties(jdbcProps),
                new ReadReplicaProperties(mode),
                defaultPool(),
                new RetryProperties(true));
    }

    @Test
    void write_compilesPrimaryTarget_noLoadBalance() {
        try (HikariDataSource ds = writeFactory.build(props(ReadMode.SECONDARY_PREFERRED), defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .isEqualTo("jdbc:postgresql://10.0.0.1:5432,10.0.0.2:5432/orders?targetServerType=primary");
            assertThat(ds.isReadOnly()).isFalse();
        }
    }

    @Test
    void read_secondaryPreferred_compilesPreferSecondary_noLoadBalance() {
        try (HikariDataSource ds = readFactory.build(props(ReadMode.SECONDARY_PREFERRED), defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .contains("targetServerType=preferSecondary")
                    .doesNotContain("loadBalanceHosts=true");
            assertThat(ds.isReadOnly()).isTrue();
        }
    }

    @Test
    void read_strictSecondary_compilesSecondary_noLoadBalance() {
        try (HikariDataSource ds = readFactory.build(props(ReadMode.STRICT_SECONDARY), defaultPool())) {
            assertThat(ds.getJdbcUrl()).contains("targetServerType=secondary");
        }
    }

    @Test
    void read_anyNodeLoadBalanced_compilesAny_withLoadBalance() {
        try (HikariDataSource ds = readFactory.build(props(ReadMode.ANY_NODE_LOAD_BALANCED), defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .contains("targetServerType=any")
                    .contains("loadBalanceHosts=true");
        }
    }

    @Test
    void serialize_driverProperties_orderPreserved() {
        Map<String, String> jdbcProps = new LinkedHashMap<>();
        jdbcProps.put("sslmode", "prefer");
        jdbcProps.put("connectTimeout", "5");

        try (HikariDataSource ds = writeFactory.build(
                propsWithJdbc(ReadMode.SECONDARY_PREFERRED, jdbcProps),
                defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .isEqualTo("jdbc:postgresql://host1:5432/db?targetServerType=primary&sslmode=prefer&connectTimeout=5");
        }
    }

    @Test
    void serialize_emptyNodes_throws() {
        PgClusterProperties emptyNodes = new PgClusterProperties(
                DataSourceRoutingMode.CLIENT,
                List.of(),
                "db",
                null,
                null,
                new ReadReplicaProperties(ReadMode.SECONDARY_PREFERRED),
                defaultPool(),
                new RetryProperties(true));

        assertThatThrownBy(() -> writeFactory.build(emptyNodes, defaultPool()))
                .isInstanceOf(PostgresClusterConfigurationException.class);
    }

    @Test
    void poolNaming_usesDefaultWhenAbsent() {
        try (HikariDataSource write = writeFactory.build(props(ReadMode.SECONDARY_PREFERRED), defaultPool());
             HikariDataSource read = readFactory.build(props(ReadMode.SECONDARY_PREFERRED), defaultPool())) {
            assertThat(write.getPoolName()).isEqualTo(RoutingMetricNames.DEFAULT_WRITE_POOL_NAME);
            assertThat(read.getPoolName()).isEqualTo(RoutingMetricNames.DEFAULT_READ_POOL_NAME);
        }
    }

    @Test
    void writerValidation_patroniPrimaryCheck_hasGuardAndSelect1() {
        PoolGroupProperties pool = new PoolGroupProperties(
                new PoolProperties(
                        null, 10, null, 30_000, 300_000, 60_000, null, 0, -1,
                        WriterValidationMode.PATRONI_PRIMARY_CHECK),
                lazyInitReadPool());

        try (HikariDataSource ds = writeFactory.build(props(ReadMode.SECONDARY_PREFERRED), pool)) {
            assertThat(ds.getConnectionInitSql()).contains("pg_is_in_recovery()");
            assertThat(ds.getConnectionTestQuery()).isEqualTo("SELECT 1");
        }
    }

    @Test
    void writerValidation_jdbc4_noGuardNoTestQuery() {
        PoolGroupProperties pool = new PoolGroupProperties(
                new PoolProperties(
                        null, 10, null, 30_000, 300_000, 60_000, null, 0, -1,
                        WriterValidationMode.JDBC4),
                lazyInitReadPool());

        try (HikariDataSource ds = writeFactory.build(props(ReadMode.SECONDARY_PREFERRED), pool)) {
            assertThat(ds.getConnectionInitSql()).isNull();
            assertThat(ds.getConnectionTestQuery()).isNull();
        }
    }

    @Test
    @DisplayName("currentSchema в jdbc.properties → добавляется в URL перед остальными props")
    void write_withCurrentSchema_inJdbcProperties() {
        Map<String, String> jdbcProps = Map.of("currentSchema", "ballistics");
        try (HikariDataSource ds = writeFactory.build(
                propsWithJdbc(ReadMode.SECONDARY_PREFERRED, jdbcProps), defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .contains("currentSchema=ballistics")
                    .doesNotContain("currentSchema=public");
        }
    }

    @Test
    @DisplayName("currentSchema в read pool — применяется так же, как в write pool")
    void read_withCurrentSchema_appliedToReadPool() {
        Map<String, String> jdbcProps = Map.of("currentSchema", "analytics");
        try (HikariDataSource ds = readFactory.build(
                propsWithJdbc(ReadMode.ANY_NODE_LOAD_BALANCED, jdbcProps), defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .contains("currentSchema=analytics")
                    .contains("loadBalanceHosts=true");
        }
    }

    @Test
    @DisplayName("без currentSchema в jdbc.properties — параметр отсутствует в URL (server default); null jdbc не бросает исключение")
    void write_withoutSchema_urlHasNoCurrentSchema() {
        // props() передаёт null для jdbc → JdbcProperties.properties() возвращает Map.of() (immutable).
        // Тест также является регрессионным на замену Map.of() → new LinkedHashMap<>():
        // если замена не сделана, buildUrl() бросит UnsupportedOperationException при props.remove().
        try (HikariDataSource ds = writeFactory.build(props(ReadMode.SECONDARY_PREFERRED), defaultPool())) {
            assertThat(ds.getJdbcUrl()).doesNotContain("currentSchema");
        }
    }

    @Test
    @DisplayName("пробел в значении schema → percent-encoding %20, не + (RFC 3986 vs HTML form)")
    void schemaWithSpace_isPercentEncoded() {
        Map<String, String> jdbcProps = new LinkedHashMap<>();
        jdbcProps.put("currentSchema", "my schema");
        try (HikariDataSource ds = writeFactory.build(
                propsWithJdbc(ReadMode.SECONDARY_PREFERRED, jdbcProps), defaultPool())) {
            assertThat(ds.getJdbcUrl())
                    .contains("currentSchema=my%20schema")
                    .doesNotContain("currentSchema=my+schema");
        }
    }

    @Test
    @DisplayName("currentSchema не дублируется в URL при наличии в jdbc.properties")
    void currentSchema_notDuplicatedInUrl() {
        Map<String, String> jdbcProps = Map.of("currentSchema", "orders");
        try (HikariDataSource ds = writeFactory.build(
                propsWithJdbc(ReadMode.SECONDARY_PREFERRED, jdbcProps), defaultPool())) {
            String url = ds.getJdbcUrl();
            // indexOf == lastIndexOf means exactly one occurrence
            assertThat(url.indexOf("currentSchema")).isEqualTo(url.lastIndexOf("currentSchema"));
        }
    }
}
