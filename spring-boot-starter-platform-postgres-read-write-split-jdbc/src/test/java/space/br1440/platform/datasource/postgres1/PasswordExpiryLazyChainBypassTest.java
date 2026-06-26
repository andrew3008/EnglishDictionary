package space.br1440.platform.datasource.postgres.internal.proxy.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.datasource.support.ProductionDataSourceChainSupport.extractSqlState;

/**
 * Поведенческое доказательство PR 4.1 без Docker: после переноса
 * {@link PasswordExpiryRetryDataSourceProxy} под {@link LazyConnectionDataSourceProxy}
 * (порядок {@code Lazy → Routing → PwdRetry → Hikari}) retry-callback срабатывает на
 * SQLSTATE 28P01 как в steady-state (statement-time), так и на cold-start, потому что
 * PwdRetry теперь оборачивает физический пул напрямую.
 *
 * <p>До PR 4.1 (порядок {@code PwdRetry(Lazy(...))}) этот же callback не вызывался — тесты
 * служат регрессионным детектором правильной компоновки.
 */
@DisplayName("PwdRetry под LazyConnectionDataSourceProxy — retry на routing-target (unit)")
class PasswordExpiryLazyChainBypassTest {

    @Test
    @DisplayName("steady-state: 28P01 на новом physical connect триггерит PwdRetry-callback")
    void steadyStateStatementTime28P01_triggersPwdRetryCallback() throws Exception {
        AtomicInteger physicalConnectAttempts = new AtomicInteger();
        AtomicInteger pwdRetryCallbacks = new AtomicInteger();

        // Первый acquire (warm-up) успешен, последующие падают с 28P01.
        DataSource physical = countingPhysicalPool(physicalConnectAttempts, 1);
        DataSource primary = buildProductionChain(physical, pwdRetryCallbacks);

        executeSelect1(primary);
        assertThat(physicalConnectAttempts.get())
                .as("warm-up opens at least one physical connection")
                .isGreaterThanOrEqualTo(1);

        assertThatThrownBy(() -> executeSelect1(primary))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(extractSqlState(ex)).isEqualToIgnoringCase("28P01"));

        assertThat(pwdRetryCallbacks.get())
                .as("PwdRetry под Lazy перехватывает statement-time 28P01 на routing-target")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("cold-start: 28P01 на первом physical connect тоже триггерит PwdRetry-callback")
    void coldStartDefaultProbe28P01_triggersPwdRetryCallback() throws Exception {
        AtomicInteger physicalConnectAttempts = new AtomicInteger();
        AtomicInteger pwdRetryCallbacks = new AtomicInteger();

        DataSource physical = alwaysFailPhysicalPool(physicalConnectAttempts);
        DataSource primary = buildProductionChain(physical, pwdRetryCallbacks);

        assertThatThrownBy(() -> executeSelect1(primary))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(extractSqlState(ex)).isEqualToIgnoringCase("28P01"));

        assertThat(physicalConnectAttempts.get()).isGreaterThanOrEqualTo(1);
        assertThat(pwdRetryCallbacks.get())
                .as("cold-start auth failure доходит до PwdRetry на routing-target")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("recovery: 28P01 на первой попытке, успех на retry → callback == 1, SQL проходит")
    void retryRecovers_whenReconnectSucceeds() throws Exception {
        AtomicInteger physicalConnectAttempts = new AtomicInteger();
        AtomicInteger pwdRetryCallbacks = new AtomicInteger();

        // Первая попытка acquire падает с 28P01, вторая (после soft-evict callback) успешна.
        DataSource physical = failFirstThenSucceedPool(physicalConnectAttempts);
        DataSource primary = buildProductionChain(physical, pwdRetryCallbacks);

        executeSelect1(primary);

        assertThat(pwdRetryCallbacks.get())
                .as("ровно один retry на восстановление")
                .isEqualTo(1);
        assertThat(physicalConnectAttempts.get())
                .as("первый acquire — fail, второй — success")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("контроль: direct PwdRetry без Lazy перехватывает 28P01 на getConnection()")
    void directProxy_intercepts28P01OnGetConnection() throws Exception {
        AtomicInteger physicalConnectAttempts = new AtomicInteger();
        AtomicInteger pwdRetryCallbacks = new AtomicInteger();

        DataSource physical = countingPhysicalPool(physicalConnectAttempts, 1);
        PasswordExpiryRetryDataSourceProxy direct = new PasswordExpiryRetryDataSourceProxy(
                physical, pwdRetryCallbacks::incrementAndGet);

        executeSelect1(direct);

        assertThatThrownBy(() -> executeSelect1(direct))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertThat(extractSqlState(ex)).isEqualToIgnoringCase("28P01"));

        assertThat(pwdRetryCallbacks.get()).isEqualTo(1);
        assertThat(physicalConnectAttempts.get()).isEqualTo(3);
    }

    /**
     * Собирает целевую цепочку PR 4.1: {@code Lazy → Routing → PwdRetry(physical)}.
     * Возвращает {@code @Primary}-вход (Lazy-прокси).
     */
    private static DataSource buildProductionChain(DataSource physical, AtomicInteger pwdRetryCallbacks) {
        PasswordExpiryRetryDataSourceProxy retryTarget = new PasswordExpiryRetryDataSourceProxy(
                physical, pwdRetryCallbacks::incrementAndGet);

        RoutingDataSource routing = new RoutingDataSource(retryTarget, retryTarget, null);
        routing.afterPropertiesSet();

        // Дефолты заданы явно, чтобы Lazy не делал startup-probe getConnection()
        // (иначе probe искажает счётчик физических acquire в unit-сценарии).
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy();
        lazy.setDefaultAutoCommit(true);
        lazy.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        lazy.setTargetDataSource(routing);
        lazy.afterPropertiesSet();
        return lazy;
    }

    private static DataSource countingPhysicalPool(AtomicInteger physicalConnectAttempts, int successfulAttempts) {
        Connection successfulConnection = mockSuccessfulConnection();

        return new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                if (physicalConnectAttempts.incrementAndGet() <= successfulAttempts) {
                    return successfulConnection;
                }
                throw new SQLException("password authentication failed", "28P01");
            }
        };
    }

    private static DataSource alwaysFailPhysicalPool(AtomicInteger physicalConnectAttempts) {
        return new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                physicalConnectAttempts.incrementAndGet();
                throw new SQLException("password authentication failed", "28P01");
            }
        };
    }

    private static DataSource failFirstThenSucceedPool(AtomicInteger physicalConnectAttempts) {
        Connection successfulConnection = mockSuccessfulConnection();

        return new StubDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                if (physicalConnectAttempts.incrementAndGet() == 1) {
                    throw new SQLException("password authentication failed", "28P01");
                }
                return successfulConnection;
            }
        };
    }

    private static Connection mockSuccessfulConnection() {
        try {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);

            when(connection.createStatement()).thenReturn(statement);
            when(connection.isValid(anyInt())).thenReturn(true);
            when(connection.getAutoCommit()).thenReturn(true);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getInt(1)).thenReturn(1);

            return connection;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static void executeSelect1(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            if (!resultSet.next() || resultSet.getInt(1) != 1) {
                throw new SQLException("Expected SELECT 1 to return 1");
            }
        }
    }

    /**
     * Базовая заглушка {@link DataSource}: переопределяется только {@code getConnection()},
     * остальные методы дают разумные no-op значения.
     */
    private abstract static class StubDataSource implements DataSource {

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
