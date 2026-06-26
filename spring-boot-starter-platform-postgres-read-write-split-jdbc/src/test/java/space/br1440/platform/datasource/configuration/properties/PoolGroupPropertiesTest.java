package space.br1440.platform.datasource.postgres.api.properties;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.datasource.postgres.api.properties.PoolGroupProperties;
import space.br1440.platform.datasource.postgres.api.properties.PoolProperties;
import space.br1440.platform.datasource.postgres.api.properties.WriterValidationMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты record-based {@link PoolGroupProperties} / {@link PoolProperties}.
 *
 * <p>Заменяет прежний {@code HikariRoutingPropertiesTest}. Имя пула и validation-SQL
 * больше не выставляются в {@code applyTo} — они резолвятся policy-слоем фабрик,
 * поэтому здесь проверяются только роль-независимые Hikari-настройки.
 */
@DisplayName("PoolGroupProperties / PoolProperties")
class PoolGroupPropertiesTest {

    @Test
    void poolPropertiesDefaults() {
        PoolProperties pool = PoolProperties.defaults();
        assertThat(pool.maximumPoolSize()).isEqualTo(10);
        // PR 4: "магический" дефолт minimumIdle=2 убран — null означает fixed-size.
        assertThat(pool.minimumIdle()).isNull();
        assertThat(pool.maxLifetime()).isEqualTo(300_000L);
        assertThat(pool.keepaliveTime()).isEqualTo(60_000L);
        assertThat(pool.connectionTimeout()).isEqualTo(30_000L);
        assertThat(pool.connectionTestQuery()).isNull();
        assertThat(pool.leakDetectionThreshold()).isEqualTo(0L);
        assertThat(pool.poolName()).isNull();
        assertThat(pool.writerValidationMode()).isEqualTo(WriterValidationMode.PATRONI_PRIMARY_CHECK);
    }

    @Test
    void poolProperties_applyTo_setsRoleIndependentProperties() {
        PoolProperties pool = new PoolProperties(
                "TestPool", 20, 5, 10_000L, 600_000L, 60_000L,
                "SELECT 42", 30_000L, 1L, WriterValidationMode.JDBC4);

        HikariConfig config = new HikariConfig();
        pool.applyTo(config);

        assertThat(config.getMaximumPoolSize()).isEqualTo(20);
        assertThat(config.getMinimumIdle()).isEqualTo(5);
        assertThat(config.getKeepaliveTime()).isEqualTo(60_000L);
        assertThat(config.getConnectionTimeout()).isEqualTo(10_000L);
        assertThat(config.getMaxLifetime()).isEqualTo(600_000L);
        assertThat(config.getConnectionTestQuery()).isEqualTo("SELECT 42");
        assertThat(config.getLeakDetectionThreshold()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("PR 4: minimumIdle=null → setMinimumIdle не вызывается (HikariConfig остаётся -1 = fixed-size при старте)")
    void poolProperties_applyTo_minimumIdleNull_leavesHikariDefault() {
        PoolProperties pool = PoolProperties.defaults();
        HikariConfig config = new HikariConfig();
        config.setMaximumPoolSize(10);

        pool.applyTo(config);

        // setMinimumIdle не вызван → HikariConfig хранит дефолт -1; HikariCP при старте
        // трактует -1 как «использовать maximumPoolSize» (fixed-size). Резолюция в значение
        // происходит в HikariDataSource, не в голом HikariConfig.
        assertThat(config.getMinimumIdle()).isEqualTo(-1);
    }

    @Test
    @DisplayName("applyTo НЕ выставляет poolName (резолвится policy-слоем фабрик)")
    void poolProperties_applyTo_doesNotSetPoolName() {
        PoolProperties pool = PoolProperties.defaults();
        HikariConfig config = new HikariConfig();
        config.setPoolName("preset-name");

        pool.applyTo(config);

        assertThat(config.getPoolName()).isEqualTo("preset-name");
    }

    @Test
    @DisplayName("write и read дефолтятся в отдельные инстансы PoolProperties")
    void writeAndRead_defaultToSeparateInstances() {
        PoolGroupProperties props = new PoolGroupProperties(null, null);
        assertThat(props.write()).isNotNull();
        assertThat(props.read()).isNotNull();
    }
}
