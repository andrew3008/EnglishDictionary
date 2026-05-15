package space.br1440.platform.datasource.config.support;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PoolDefaults")
class PoolDefaultsTest {

    @Test
    @DisplayName("applyWriteDefaults: пустой connectionTestQuery заполняется каноническим значением")
    void applyWriteDefaults_setsConnectionTestQueryWhenAbsent() {
        HikariConfig config = new HikariConfig();

        PoolDefaults.applyWriteDefaults(config);

        assertThat(config.getConnectionTestQuery()).isEqualTo(PoolDefaults.WRITE_CONNECTION_TEST_QUERY);
    }

    @Test
    @DisplayName("applyWriteDefaults: пользовательский connectionTestQuery не затирается")
    void applyWriteDefaults_preservesUserConnectionTestQuery() {
        HikariConfig config = new HikariConfig();
        config.setConnectionTestQuery("SELECT 1");

        PoolDefaults.applyWriteDefaults(config);

        assertThat(config.getConnectionTestQuery()).isEqualTo("SELECT 1");
    }

    @Test
    @DisplayName("applyWriteDefaults: keepaliveTime не трогается — управляется на property-уровне")
    void applyWriteDefaults_doesNotTouchKeepaliveTime() {
        HikariConfig config = new HikariConfig();
        long beforeKeepalive = config.getKeepaliveTime();

        PoolDefaults.applyWriteDefaults(config);

        assertThat(config.getKeepaliveTime()).isEqualTo(beforeKeepalive);
    }

    @Test
    @DisplayName("applyPoolNameIfAbsent: ставит имя, только если poolName пустой/null")
    void applyPoolNameIfAbsent_onlyWhenAbsent() {
        HikariConfig empty = new HikariConfig();
        PoolDefaults.applyPoolNameIfAbsent(empty, "postgres-cluster-write-pool");
        assertThat(empty.getPoolName()).isEqualTo("postgres-cluster-write-pool");

        HikariConfig custom = new HikariConfig();
        custom.setPoolName("my-pool");
        PoolDefaults.applyPoolNameIfAbsent(custom, "postgres-cluster-write-pool");
        assertThat(custom.getPoolName()).isEqualTo("my-pool");
    }
}
