package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты HikariCP-специфичных свойств {@link HikariRoutingProperties}
 * (живут в hikaricp-jdbc-starter).
 *
 * <p>Ранее эти тесты были частью {@code PostgresClusterDataSourcePropertiesTest}, когда
 * Hikari-поля жили в core. После разделения модулей (core / hikaricp-jdbc-starter)
 * HikariCP-часть выделена в отдельный класс.
 */
@DisplayName("HikariRoutingProperties")
class HikariRoutingPropertiesTest {

    @Test
    void poolPropertiesDefaults() {
        HikariRoutingProperties.PoolProperties pool = new HikariRoutingProperties.PoolProperties();
        assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
        assertThat(pool.getMinimumIdle()).isEqualTo(2);
        assertThat(pool.getMaxLifetime()).isEqualTo(300_000L);
        assertThat(pool.getKeepaliveTime()).isEqualTo(60_000L);
        assertThat(pool.getConnectionTimeout()).isEqualTo(30_000L);
        assertThat(pool.getConnectionTestQuery()).isNull();
        assertThat(pool.getLeakDetectionThreshold()).isEqualTo(0L);
    }

    @Test
    void poolProperties_applyTo_setsAllProperties() {
        HikariRoutingProperties.PoolProperties pool = new HikariRoutingProperties.PoolProperties();
        pool.setMaximumPoolSize(20);
        pool.setMinimumIdle(5);
        pool.setPoolName("TestPool");
        pool.setKeepaliveTime(60_000);
        pool.setConnectionTimeout(10_000);
        pool.setMaxLifetime(600_000);
        pool.setConnectionTestQuery("SELECT 42");
        pool.setLeakDetectionThreshold(30_000);

        HikariConfig config = new HikariConfig();
        pool.applyTo(config);

        assertThat(config.getMaximumPoolSize()).isEqualTo(20);
        assertThat(config.getMinimumIdle()).isEqualTo(5);
        assertThat(config.getPoolName()).isEqualTo("TestPool");
        assertThat(config.getKeepaliveTime()).isEqualTo(60_000);
        assertThat(config.getConnectionTimeout()).isEqualTo(10_000);
        assertThat(config.getMaxLifetime()).isEqualTo(600_000);
        assertThat(config.getConnectionTestQuery()).isEqualTo("SELECT 42");
        assertThat(config.getLeakDetectionThreshold()).isEqualTo(30_000);
    }

    @Test
    void poolProperties_applyTo_nullPoolName_notSet() {
        HikariRoutingProperties.PoolProperties pool = new HikariRoutingProperties.PoolProperties();
        HikariConfig config = new HikariConfig();
        pool.applyTo(config);
        assertThat(config.getPoolName()).isNull();
    }

    @Test
    @DisplayName("write и read — отдельные инстансы PoolProperties")
    void writeAndRead_areSeparateInstances() {
        HikariRoutingProperties props = new HikariRoutingProperties();
        assertThat(props.getWrite()).isNotNull();
        assertThat(props.getRead()).isNotNull();
        assertThat(props.getWrite()).isNotSameAs(props.getRead());
    }
}
