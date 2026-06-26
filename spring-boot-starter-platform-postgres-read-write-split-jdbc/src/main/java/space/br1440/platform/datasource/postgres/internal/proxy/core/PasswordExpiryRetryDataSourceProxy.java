package space.br1440.platform.datasource.postgres.internal.proxy.core;

import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Декоратор {@link DataSource}, прозрачно повторяющий {@code getConnection()} один раз
 * при получении от PostgreSQL ошибки <b>SQLSTATE 28P01</b> (invalid_password).
 * <p>
 * После ротации пароля (например, через обновление Kubernetes Secret + перезапуск пода)
 * существует краткое окно, в котором HikariCP может держать соединения, аутентифицированные
 * по старому паролю. Когда такие соединения вытесняются и создаются новые, они завершаются ошибкой 28P01.
 * Этот прокси обнаруживает сбой, вызывает {@link PoolResetCallback#resetPools()} для принудительного soft-eviction пулов
 * и выполняет одну повторную попытку — давая пулу возможность установить соединения с обновлёнными учётными данными.
 * <p>
 *
 * @see <a href="https://www.postgresql.org/docs/current/errcodes-appendix.html">Коды ошибок PostgreSQL</a>
 */
@Slf4j
public class PasswordExpiryRetryDataSourceProxy implements DataSource {

    private static final String INVALID_PASSWORD_STATE = "28P01";

    private final DataSource delegate;
    private final PoolResetCallback poolResetCallback;

    @FunctionalInterface
    public interface PoolResetCallback {
        void resetPools();
    }

    public PasswordExpiryRetryDataSourceProxy(DataSource delegate,
                                              PoolResetCallback poolResetCallback) {
        this.delegate = delegate;
        this.poolResetCallback = poolResetCallback;
    }

    public PasswordExpiryRetryDataSourceProxy(DataSource delegate) {
        this(delegate, () -> { });
    }

    @Override
    public Connection getConnection() throws SQLException {
        return withPasswordRetry(delegate::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return withPasswordRetry(() -> delegate.getConnection(username, password));
    }

    private Connection withPasswordRetry(ConnectionSupplier supplier) throws SQLException {
        try {
            return supplier.get();
        } catch (SQLException ex) {
            if (INVALID_PASSWORD_STATE.equalsIgnoreCase(extractSqlState(ex))) {
                log.warn("Detected expired database password (SQLSTATE 28P01), triggering pool reset and retry");

                try {
                    poolResetCallback.resetPools();
                } catch (Exception resetEx) {
                    log.warn("Pool reset callback failed during password retry: {}", resetEx.getMessage());
                }

                return supplier.get();
            }

            throw ex;
        }
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private static String extractSqlState(Throwable throwable) {
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

    @Override
    public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException { delegate.setLogWriter(out); }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException { delegate.setLoginTimeout(seconds); }

    @Override
    public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return delegate.isWrapperFor(iface);
    }
}
