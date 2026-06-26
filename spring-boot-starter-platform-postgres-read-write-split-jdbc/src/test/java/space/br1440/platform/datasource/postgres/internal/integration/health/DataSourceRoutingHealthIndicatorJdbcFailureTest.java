package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DataSourceRoutingHealthIndicator: JDBC failure classification")
class DataSourceRoutingHealthIndicatorJdbcFailureTest {

    private static final RoutingHealthProperties PROPERTIES = new RoutingHealthProperties(
            Duration.ofSeconds(5),
            Duration.ofSeconds(3)
    );

    @Test
    void sqlTimeoutAfterAcquisition_isTimeout() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenThrow(new SQLTimeoutException("timed out"));

        Health health = indicator(ds).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> write = (Map<String, Object>) health.getDetails().get("write");
        assertThat(write).containsEntry("failureType", FailureType.TIMEOUT.name());
        assertThat(write).doesNotContainKey("message");
        assertThat(health.getDetails().toString()).doesNotContain("timed out");
    }

    @Test
    void sqlExceptionAfterAcquisition_isSqlException() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenThrow(new SQLException("syntax error"));

        Health health = indicator(ds).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> write = (Map<String, Object>) health.getDetails().get("write");
        assertThat(write).containsEntry("failureType", FailureType.SQL_EXCEPTION.name());
        assertThat(health.getDetails().toString()).doesNotContain("syntax error");
    }

    @Test
    void noRow_isInvalidResult() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Health health = indicator(ds).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> write = (Map<String, Object>) health.getDetails().get("write");
        assertThat(write).containsEntry("failureType", FailureType.INVALID_RESULT.name());
        assertThat(write).containsEntry("status", Status.DOWN.getCode());
    }

    @Test
    void sqlNull_isInvalidResult() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(false);
        when(rs.wasNull()).thenReturn(true);

        Health health = indicator(ds).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> write = (Map<String, Object>) health.getDetails().get("write");
        assertThat(write).containsEntry("failureType", FailureType.INVALID_RESULT.name());
    }

    private static DataSourceRoutingHealthIndicator indicator(DataSource dataSource) {
        return new DataSourceRoutingHealthIndicator(
                dataSource, dataSource, ReadMode.SECONDARY_PREFERRED, PROPERTIES);
    }
}
