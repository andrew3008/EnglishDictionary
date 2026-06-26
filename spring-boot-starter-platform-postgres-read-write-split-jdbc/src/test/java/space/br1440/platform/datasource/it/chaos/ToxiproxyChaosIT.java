package space.br1440.platform.datasource.it.chaos;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Latency;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.ToxiproxyContainer;
import space.br1440.platform.datasource.support.junit.PgClusterScope;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;
import space.br1440.platform.datasource.support.junit.ToxiproxyExtension;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Хаос-сценарии для master-пула через Toxiproxy:
 * <ul>
 *   <li>Latency spike (искусственная сетевая задержка)</li>
 *   <li>Reset peer (обрыв соединения = "kill primary" с точки зрения клиента)</li>
 *   <li>Восстановление: после удаления toxics пул возвращается в работоспособное состояние.</li>
 * </ul>
 * <p>
 * Toxiproxy сидит между HikariCP и PostgreSQL primary. Реплика обращается напрямую (без проксирования).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@PgClusterScope(PgClusterScope.Mode.ISOLATED)
@ExtendWith({PgClusterExtension.class, ToxiproxyExtension.class})
@DisplayName("Chaos: Toxiproxy перед master-пулом")
class ToxiproxyChaosIT {

    /**
     * Порт, на котором Toxiproxy слушает proxy → primary внутри своего контейнера.
     * Должен лежать в диапазоне, экспонируемом ToxiproxyContainer (8666-8697 по умолчанию).
     */
    private static final int PROXY_LISTEN_PORT = 8666;

    private ToxiproxyContainer toxiContainer;
    private Proxy proxy;
    private HikariDataSource hds;

    @BeforeAll
    void setupProxyAndPool(PgCluster cluster, ToxiproxyClient client, ToxiproxyContainer container)
            throws Exception {
        this.toxiContainer = container;
        this.proxy = ToxiproxyExtension.createProxy(
                client, "primary-proxy", PROXY_LISTEN_PORT, cluster.primaryAlias(), 5432);

        String hostPort = ToxiproxyExtension.toxicJdbcHost(toxiContainer, proxy);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + hostPort + "/" + cluster.dbName()
                + "?targetServerType=primary");
        cfg.setUsername(cluster.username());
        cfg.setPassword(cluster.password());
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(0);
        // Latency toxic 1.5s × 2 directions + PG handshake — 2s мало для cold connect.
        cfg.setConnectionTimeout(10_000);
        cfg.setValidationTimeout(2_000);
        // sticky stale-connection eviction: при первом отказе
        cfg.setKeepaliveTime(0);
        cfg.setConnectionTestQuery("SELECT 1");
        this.hds = new HikariDataSource(cfg);
        try (Connection warm = hds.getConnection();
             var warmStmt = warm.createStatement()) {
            warmStmt.execute("SELECT 1");
        }
    }

    @AfterEach
    void resetProxyState() throws IOException {
        if (proxy == null) {
            return;
        }
        proxy.enable();
        for (String toxicName : new String[]{"lat-down", "lat-up", "bw-down", "bw-up"}) {
            try {
                proxy.toxics().get(toxicName).remove();
            } catch (Exception ignored) {
                // toxic may be absent after partial test failure
            }
        }
        if (hds != null && hds.getHikariPoolMXBean() != null) {
            hds.getHikariPoolMXBean().softEvictConnections();
        }
    }

    @AfterAll
    void teardown() throws IOException {
        if (hds != null) hds.close();
        if (proxy != null) proxy.delete();
    }

    @Test
    @DisplayName("Latency spike (1.5s) → пул может работать, но запросы заметно медленнее")
    void latencySpike_increasesQueryLatency() throws Exception {
        Latency latencyDown = proxy.toxics().latency("lat-down", ToxicDirection.DOWNSTREAM, 1500L);
        Latency latencyUp = proxy.toxics().latency("lat-up", ToxicDirection.UPSTREAM, 1500L);
        try {
            long start = System.nanoTime();
            try (Connection conn = hds.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
            }
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(durationMs)
                    .as("query under 3s combined latency must take >= ~1500ms (down) + handshake overhead")
                    .isGreaterThan(1_400);
        } finally {
            latencyDown.remove();
            latencyUp.remove();
        }
    }

    @Test
    @DisplayName("Reset peer / proxy disable → последующий getConnection() падает в разумный таймаут")
    void proxyDisabled_failsWithTimeout() throws Exception {
        // прогреваем пул
        try (Connection c = hds.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }

        // моделируем "kill primary": Toxiproxy режет любой трафик (proxy.disable())
        proxy.disable();
        try {
            // вытесняем существующие соединения, чтобы следующий запрос был заведомо новым
            hds.getHikariPoolMXBean().softEvictConnections();
            assertThatThrownBy(() -> {
                try (Connection conn = hds.getConnection()) {
                    conn.createStatement().execute("SELECT 1");
                }
            }).isInstanceOf(SQLException.class);
        } finally {
            proxy.enable();
        }

        // recovery: после восстановления proxy → новый getConnection() работает
        Awaitility.await()
                .pollInterval(Duration.ofMillis(200))
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> {
                    try (Connection conn = hds.getConnection()) {
                        assertThat(conn.isValid(2)).isTrue();
                    }
                });
    }

    @Test
    @DisplayName("Slicer toxic (рваные TCP-пакеты) → запросы всё равно проходят, но с jitter")
    void sliceJitter_stillWorks() throws Exception {
        // bandwidth-shaping: ограничиваем 8 KB/s — небольшие SELECT 1 проходят
        proxy.toxics().bandwidth("bw-down", ToxicDirection.DOWNSTREAM, 8L);
        proxy.toxics().bandwidth("bw-up", ToxicDirection.UPSTREAM, 8L);
        try {
            try (Connection conn = hds.getConnection();
                 Statement st = conn.createStatement();
                 var rs = st.executeQuery("SELECT 1")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        } finally {
            proxy.toxics().get("bw-down").remove();
            proxy.toxics().get("bw-up").remove();
        }
    }
}
