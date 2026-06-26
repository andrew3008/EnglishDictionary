package space.br1440.platform.datasource.postgres.internal.proxy.core;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import space.br1440.platform.datasource.postgres.api.exception.ReadDataSourceUnavailableException;
import space.br1440.platform.datasource.postgres.api.exception.WriteDataSourceUnavailableException;
import space.br1440.platform.datasource.support.RoutingContextExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("RoutingDataSource")
@ExtendWith(RoutingContextExtension.class)
class RoutingDataSourceTest {

    private final DataSource writeDs = mock(DataSource.class);
    private final DataSource readDs = mock(DataSource.class);

    @Nested
    @DisplayName("resolveKey")
    class ResolveKey {

        @Test
        void explicitOverride_takesPreference() {
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.READ);
            assertThat(rds.resolveKey()).isEqualTo(DataSourceType.READ);
        }

        @Test
        void noOverride_noTransaction_defaultsToWrite() {
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            assertThat(rds.resolveKey()).isEqualTo(DataSourceType.WRITE);
        }

        @Test
        void explicitWrite_returnsWrite() {
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.WRITE);
            assertThat(rds.resolveKey()).isEqualTo(DataSourceType.WRITE);
        }
    }

    @Test
    void determineTargetDataSource_read_returnsReadDataSource() {
        RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

        DataSourceContextHolder.set(DataSourceType.READ);
        DataSource target = rds.determineTargetDataSource();
        assertThat(target).isSameAs(readDs);
    }

    @Test
    void determineTargetDataSource_write_returnsWriteDataSource() {
        RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

        DataSourceContextHolder.set(DataSourceType.WRITE);
        DataSource target = rds.determineTargetDataSource();
        assertThat(target).isSameAs(writeDs);
    }

    @Nested
    @DisplayName("getConnection -- exception wrapping")
    class GetConnectionExceptions {

        @Test
        void write_sqlTransientException_throwsWriteDataSourceUnavailable() throws SQLException {
            when(writeDs.getConnection())
                    .thenThrow(new SQLTransientConnectionException("connection refused"));
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.WRITE);
            assertThatThrownBy(rds::getConnection)
                    .isInstanceOf(WriteDataSourceUnavailableException.class)
                    .hasMessageContaining("Write DataSource is unavailable");
        }

        @Test
        void read_sqlTransientException_throwsReadDataSourceUnavailable() throws SQLException {
            when(readDs.getConnection())
                    .thenThrow(new SQLTransientConnectionException("connection refused"));
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.READ);
            assertThatThrownBy(rds::getConnection)
                    .isInstanceOf(ReadDataSourceUnavailableException.class)
                    .hasMessageContaining("Read DataSource is unavailable");
        }

        @Test
        void success_returnsConnection() throws SQLException {
            Connection mockConn = mock(Connection.class);
            when(writeDs.getConnection()).thenReturn(mockConn);
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.WRITE);
            Connection result = rds.getConnection();
            assertThat(result).isSameAs(mockConn);
        }

        @Test
        void nonTransientSqlException_propagatesDirectly() throws SQLException {
            when(writeDs.getConnection())
                    .thenThrow(new SQLException("syntax error", "42601"));
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.WRITE);
            assertThatThrownBy(rds::getConnection)
                    .isInstanceOf(SQLException.class)
                    .isNotInstanceOf(WriteDataSourceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("getConnection -- MDC cleanup")
    class GetConnectionMdc {

        @Test
        void clearsMdc_afterSuccess() throws SQLException {
            Connection mockConn = mock(Connection.class);
            when(writeDs.getConnection()).thenReturn(mockConn);
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.WRITE);
            rds.getConnection();

            assertThat(MDC.get("db.target")).isNull();
            assertThat(MDC.get("db.pool")).isNull();
        }

        @Test
        void clearsMdc_afterException() throws SQLException {
            when(readDs.getConnection())
                    .thenThrow(new SQLTransientConnectionException("timeout"));
            RoutingDataSource rds = new RoutingDataSource(writeDs, readDs, null);

            DataSourceContextHolder.set(DataSourceType.READ);
            try {
                rds.getConnection();
            } catch (Exception ignored) {
            }

            assertThat(MDC.get("db.target")).isNull();
            assertThat(MDC.get("db.pool")).isNull();
        }
    }
}
