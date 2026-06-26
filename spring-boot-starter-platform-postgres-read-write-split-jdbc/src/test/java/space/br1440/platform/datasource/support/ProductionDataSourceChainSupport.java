package space.br1440.platform.datasource.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.internal.proxy.core.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Собирает prod-цепочку {@code Lazy → Routing → PwdRetry(Hikari)} так же,
 * как {@link PostgresDataSourceRoutingAutoConfiguration}
 * после PR 4.1: {@code @Primary dataSource} — это {@link LazyConnectionDataSourceProxy},
 * а {@link PasswordExpiryRetryDataSourceProxy} оборачивает каждый физический пул (per-pool
 * soft-evict callback).
 */
public final class ProductionDataSourceChainSupport {

    private ProductionDataSourceChainSupport() {
    }

    /**
     * @param primaryDataSource application-facing {@code @Primary} вход цепочки (Lazy-прокси)
     */
    public record ProductionChain(
            HikariDataSource writePool,
            HikariDataSource readPool,
            RoutingDataSource routingDataSource,
            LazyConnectionDataSourceProxy lazyDataSource,
            DataSource primaryDataSource,
            AtomicInteger callbackInvocations
    ) implements AutoCloseable {

        @Override
        public void close() {
            writePool.close();
            readPool.close();
        }
    }

    public static HikariDataSource createPool(String jdbcUrl, String username, String password, boolean readOnly) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(username);
        cfg.setPassword(password);
        cfg.setReadOnly(readOnly);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(5_000);
        return new HikariDataSource(cfg);
    }

    public static ProductionChain buildProductionChain(String jdbcUrl, String username, String password) {
        HikariDataSource writePool = createPool(jdbcUrl, username, password, false);
        HikariDataSource readPool = createPool(jdbcUrl, username, password, true);

        AtomicInteger callbackInvocations = new AtomicInteger();

        // PwdRetry оборачивает каждый физический пул (per-pool soft-evict) — целевой порядок PR 4.1.
        // Callback повторяет контракт стартера: только soft-evict своего пула, без refresh credentials.
        DataSource writeTarget = new PasswordExpiryRetryDataSourceProxy(writePool, () -> {
            callbackInvocations.incrementAndGet();
            writePool.getHikariPoolMXBean().softEvictConnections();
        });
        DataSource readTarget = new PasswordExpiryRetryDataSourceProxy(readPool, () -> {
            callbackInvocations.incrementAndGet();
            readPool.getHikariPoolMXBean().softEvictConnections();
        });

        RoutingDataSource routing = new RoutingDataSource(writeTarget, readTarget, null);
        routing.afterPropertiesSet();

        // Lazy — самый внешний прокси (@Primary dataSource).
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(routing);

        return new ProductionChain(writePool, readPool, routing, lazy, lazy, callbackInvocations);
    }

    public static void executeSelect1(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            if (!resultSet.next() || resultSet.getInt(1) != 1) {
                throw new SQLException("Expected SELECT 1 to return 1");
            }
        }
    }

    public static String extractSqlState(Throwable throwable) {
        Set<Throwable> visited = new HashSet<>();
        Throwable current = throwable;

        while (current != null && visited.add(current)) {
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && !state.isEmpty()) {
                    return state;
                }
            }
            current = current.getCause();
        }

        return "";
    }
}
