package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.Container.ExecResult;
import space.br1440.platform.datasource.postgres.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет {@link PasswordExpiryRetryDataSourceProxy} на реальной ротации пароля в PostgreSQL.
 * <ol>
 *   <li>Создаём отдельного пользователя {@code rotuser/secret1}.</li>
 *   <li>HikariCP пул аутентифицируется как {@code rotuser}.</li>
 *   <li>{@code ALTER USER rotuser PASSWORD 'secret2'} (через psql от postgres).</li>
 *   <li>{@code REVOKE CONNECT} → активные соединения вытесняются (мы делаем soft-eviction вручную).</li>
 *   <li>В прокси вкладываем callback "обновить пароль и сбросить пул".</li>
 *   <li>Первая попытка падает с 28P01 → callback запускается → вторая попытка успешна.</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("PasswordExpiryRetryDataSourceProxy × живой PostgreSQL")
class PasswordRotationClusterIT {

    private static final String ROT_USER = "rotuser";
    private static final String INITIAL_PWD = "secret1";
    private static final String NEW_PWD = "secret2";

    private PgCluster cluster;
    private HikariDataSource hds;
    private final AtomicInteger callbackInvocations = new AtomicInteger();

    @BeforeAll
    void prepareUserAndPool(PgCluster cluster) throws Exception {
        this.cluster = cluster;
        execPsql("CREATE ROLE " + ROT_USER + " WITH LOGIN PASSWORD '" + INITIAL_PWD + "';");
        execPsql("GRANT ALL PRIVILEGES ON DATABASE " + cluster.dbName() + " TO " + ROT_USER + ";");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + cluster.primaryHostPort() + "/" + cluster.dbName()
                + "?targetServerType=primary");
        cfg.setUsername(ROT_USER);
        cfg.setPassword(INITIAL_PWD);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(5_000);
        this.hds = new HikariDataSource(cfg);
    }

    @AfterAll
    void cleanup() throws Exception {
        if (hds != null) hds.close();
        execPsql("DROP OWNED BY " + ROT_USER + " CASCADE;");
        execPsql("DROP ROLE IF EXISTS " + ROT_USER + ";");
    }

    @Test
    @DisplayName("28P01 после ротации пароля: прокси сбрасывает пул и переподключается")
    void rotation_triggersCallback_andRetrySucceeds() throws Exception {
        // baseline: можно подключиться
        try (Connection c = hds.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        }

        // 1) меняем пароль в PostgreSQL
        execPsql("ALTER ROLE " + ROT_USER + " WITH PASSWORD '" + NEW_PWD + "';");
        // 2) сбрасываем активные соединения принудительно (имитируем то, что может произойти
        //    при ALTER USER при активной auth-policy: pg_terminate_backend закрывает их).
        execPsql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                + "WHERE usename='" + ROT_USER + "' AND pid<>pg_backend_pid();");

        // прокси: при 28P01 → callback меняет hikari config и сбрасывает пулы
        PasswordExpiryRetryDataSourceProxy proxy = new PasswordExpiryRetryDataSourceProxy(hds, () -> {
            callbackInvocations.incrementAndGet();
            hds.getHikariConfigMXBean().setPassword(NEW_PWD);
            hds.getHikariPoolMXBean().softEvictConnections();
        });

        try (Connection c = proxy.getConnection()) {
            assertThat(c.isValid(2)).isTrue();
        } catch (SQLException e) {
            throw new AssertionError("Expected proxy to recover via password retry", e);
        }

        assertThat(callbackInvocations.get())
                .as("callback should be invoked at least once after 28P01")
                .isGreaterThanOrEqualTo(1);
    }

    private void execPsql(String sql) throws Exception {
        ExecResult r = cluster.primary().execInContainer(
                org.testcontainers.containers.ExecConfig.builder()
                        .user("postgres")
                        .command(new String[]{"psql", "-U", "postgres", "-d", cluster.dbName(), "-c", sql})
                        .build());
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + r.getStderr());
        }
    }
}
