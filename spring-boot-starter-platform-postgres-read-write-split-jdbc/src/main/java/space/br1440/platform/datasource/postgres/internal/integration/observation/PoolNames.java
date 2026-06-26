package space.br1440.platform.datasource.postgres.internal.integration.observation;

import com.zaxxer.hikari.HikariDataSource;
import lombok.experimental.UtilityClass;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Утилита извлечения имени HikariCP-пула из {@link DataSource}.
 *
 * <p>Стартер целиком построен на HikariCP, поэтому имя пула берётся из {@link HikariDataSource}.
 * Целевой {@code DataSource} может быть обёрнут в {@code PasswordExpiryRetryDataSourceProxy}
 * (retry на routing-target), поэтому помимо прямой проверки типа выполняется JDBC-{@code unwrap}
 * до {@link HikariDataSource}.
 */
@UtilityClass
public final class PoolNames {

    public static String nameOf(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            return hikariDataSource.getPoolName();
        }

        if (dataSource != null) {
            try {
                if (dataSource.isWrapperFor(HikariDataSource.class)) {
                    return poolNameFromUnwrapped(dataSource);
                }
            } catch (SQLException ex) {
                // Имя пула — диагностический атрибут (MDC/observation); при ошибке unwrap
                // молча возвращаем null, чтобы не прерывать процесс создания соединения с pg_node.
                return null;
            }
        }

        return null;
    }

    @SuppressWarnings("resource")
    private static String poolNameFromUnwrapped(DataSource dataSource) throws SQLException {
        HikariDataSource hikariDataSource = dataSource.unwrap(HikariDataSource.class);
        return hikariDataSource.getPoolName();
    }
}
