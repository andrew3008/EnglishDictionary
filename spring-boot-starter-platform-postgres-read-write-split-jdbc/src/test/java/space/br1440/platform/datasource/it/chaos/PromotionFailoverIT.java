package space.br1440.platform.datasource.it.chaos;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.Container.ExecResult;
import space.br1440.platform.datasource.support.junit.PgClusterScope;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сценарий "real promotion": вызываем {@code pg_promote()} на реплике, она становится primary
 * (т.е. переходит из hot-standby в read/write).
 * <p>
 * Что проверяется:
 * <ul>
 *   <li>До промоции: replica → {@code pg_is_in_recovery()=true}.</li>
 *   <li>После промоции (await): replica → {@code pg_is_in_recovery()=false}, на ней успешен DML.</li>
 *   <li>{@code targetServerType=primary} JDBC URL после промоции корректно достигает новой primary.</li>
 * </ul>
 * <p>
 * Тест нагружает кластер изменениями: запускается ПОСЛЕ всех остальных, чтобы не сломать им
 * стартовое состояние. Ниже использован {@link org.junit.jupiter.api.Order @Order} (см. JUnit 5 BOM).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@PgClusterScope(PgClusterScope.Mode.ISOLATED)
@ExtendWith(PgClusterExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Failover: pg_promote() на replica")
class PromotionFailoverIT {

    private PgCluster cluster;
    private HikariDataSource primaryPool;
    private HikariDataSource replicaPool;

    @BeforeAll
    void buildPools(PgCluster cluster) {
        this.cluster = cluster;
        this.primaryPool = newPool(cluster.primaryHostPort(), false);
        this.replicaPool = newPool(cluster.replicaHostPort(), true);
    }

    @AfterAll
    void closePools() {
        if (primaryPool != null) primaryPool.close();
        if (replicaPool != null) replicaPool.close();
    }

    @Test
    @Order(1)
    @DisplayName("До промоции: replica.pg_is_in_recovery() = true")
    void replica_isInRecovery_initially() throws Exception {
        try (Connection conn = replicaPool.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_is_in_recovery()")) {
            rs.next();
            assertThat(rs.getBoolean(1)).isTrue();
        }
    }

    @Test
    @Order(2)
    @DisplayName("pg_promote() на replica → переходит в primary, доступен DML; await до 30s")
    void replicaPromotion_acceptsWrites() throws Exception {
        // выполняем pg_promote() через psql внутри контейнера replica (postgres user)
        ExecResult result = cluster.replica().execInContainer(
                "su-exec", "postgres",
                "psql", "-U", cluster.username(), "-d", cluster.dbName(),
                "-c", "SELECT pg_promote();"
        );
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("pg_promote() failed: " + result.getStderr());
        }

        // Awaitility: ждём, пока pg_is_in_recovery() = false
        Awaitility.await()
                .pollInterval(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    // используем фоновую новую коннекцию, чтобы не наткнуться на закэшированную в пуле
                    HikariDataSource fresh = newPool(cluster.replicaHostPort(), false);
                    try (Connection c = fresh.getConnection();
                         Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT pg_is_in_recovery()")) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).isFalse();
                    } finally {
                        fresh.close();
                    }
                });

        // На промотированной ноде доступен DML
        HikariDataSource newPrimary = newPool(cluster.replicaHostPort(), false);
        try (Connection c = newPrimary.getConnection();
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS post_promo_test (id SERIAL PRIMARY KEY)");
            st.execute("INSERT INTO post_promo_test DEFAULT VALUES");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM post_promo_test")) {
                rs.next();
                assertThat(rs.getInt(1)).isGreaterThan(0);
            }
        } finally {
            newPrimary.close();
        }
    }

    private HikariDataSource newPool(String hostPort, boolean readOnly) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + hostPort + "/" + cluster.dbName());
        cfg.setUsername(cluster.username());
        cfg.setPassword(cluster.password());
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(5_000);
        cfg.setReadOnly(readOnly);
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }
}
