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
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DataSourceRoutingHealthIndicator: intent details + dynamic pgRole")
class DataSourceRoutingHealthIndicatorPgRoleTest {

    private static final RoutingHealthProperties DEFAULT_PROPERTIES = new RoutingHealthProperties(
            Duration.ofSeconds(5),
            Duration.ofSeconds(3)
    );

    @Test
    @DisplayName("UP: write primary, read standby")
    void healthUpShowsPrimaryAndStandby() {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = mockDataSourceReturning(true);

        Health health = indicator(write, read).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("readMode", ReadMode.SECONDARY_PREFERRED.name());
        assertWriteDetail(health, Status.UP.getCode(), "primary");
        assertReadDetail(health, Status.UP.getCode(), "standby");
    }

    @Test
    @DisplayName("LOAD_BALANCED: read connected to primary reflects actual role")
    void loadBalancedReadPoolConnectedToPrimaryReflectsActualRole() {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = mockDataSourceReturning(false);

        Health health = indicator(write, read, ReadMode.ANY_NODE_LOAD_BALANCED).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertReadDetail(health, Status.UP.getCode(), "primary");
    }

    @Test
    @DisplayName("DOWN: both pools unavailable")
    void healthDownWhenBothFail() throws Exception {
        DataSource write = failingDataSource();
        DataSource read = failingDataSource();

        Health health = indicator(write, read).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertWriteFailure(health, FailureType.CONNECTION_FAILURE);
        assertReadFailure(health, FailureType.CONNECTION_FAILURE);
    }

    @Test
    @DisplayName("STRICT_SECONDARY: write UP, read DOWN → DEGRADED")
    void strictSecondary_writeUpReadDown_isDegraded() throws Exception {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = failingDataSource();

        Health health = indicator(write, read, ReadMode.STRICT_SECONDARY).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertWriteDetail(health, Status.UP.getCode(), "primary");
        assertReadFailure(health, FailureType.CONNECTION_FAILURE);
    }

    @Test
    @DisplayName("STRICT_SECONDARY: both DOWN → DOWN")
    void strictSecondary_bothDown_isDown() throws Exception {
        DataSource write = failingDataSource();
        DataSource read = failingDataSource();

        Health health = indicator(write, read, ReadMode.STRICT_SECONDARY).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("SECONDARY_PREFERRED: write UP, read DOWN → UP")
    void secondaryPreferred_writeUpReadDown_isUp() throws Exception {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = failingDataSource();

        Health health = indicator(write, read, ReadMode.SECONDARY_PREFERRED).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertReadFailure(health, FailureType.CONNECTION_FAILURE);
    }

    @Test
    @DisplayName("SECONDARY_PREFERRED: write DOWN → DEGRADED")
    void secondaryPreferred_writeDownReadUp_isDegraded() throws Exception {
        DataSource write = failingDataSource();
        DataSource read = mockDataSourceReturning(true);

        Health health = indicator(write, read, ReadMode.SECONDARY_PREFERRED).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("setQueryTimeout uses rounded-up seconds from properties")
    void setQueryTimeoutUsesRoundedSeconds() throws Exception {
        DataSource write = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        when(write.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(false);

        DataSource read = failingDataSource();

        RoutingHealthProperties props = new RoutingHealthProperties(Duration.ofSeconds(5), Duration.ofMillis(1100));
        new DataSourceRoutingHealthIndicator(write, read, ReadMode.SECONDARY_PREFERRED, props).health();

        verify(stmt).setQueryTimeout(2);
    }

    @Test
    @DisplayName("details do not contain strictReplicaGuarantee or expectedRole")
    void detailsDoNotContainRemovedKeys() {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = mockDataSourceReturning(true);

        Health health = indicator(write, read, ReadMode.STRICT_SECONDARY).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> readDetails = (Map<String, Object>) health.getDetails().get("read");
        assertThat(readDetails).doesNotContainKeys("expectedRole", "strictReplicaGuarantee");
    }

    private static DataSourceRoutingHealthIndicator indicator(DataSource write, DataSource read) {
        return indicator(write, read, ReadMode.SECONDARY_PREFERRED);
    }

    private static DataSourceRoutingHealthIndicator indicator(DataSource write,
                                                              DataSource read,
                                                              ReadMode readMode) {
        return new DataSourceRoutingHealthIndicator(write, read, readMode, DEFAULT_PROPERTIES);
    }

    @SuppressWarnings("unchecked")
    private static void assertWriteDetail(Health health, String status, String pgRole) {
        Map<String, Object> details = (Map<String, Object>) health.getDetails().get("write");
        assertThat(details).containsEntry("status", status);
        assertThat(details).containsEntry("pgRole", pgRole);
    }

    @SuppressWarnings("unchecked")
    private static void assertReadDetail(Health health, String status, String pgRole) {
        Map<String, Object> details = (Map<String, Object>) health.getDetails().get("read");
        assertThat(details).containsEntry("status", status);
        assertThat(details).containsEntry("pgRole", pgRole);
    }

    @SuppressWarnings("unchecked")
    private static void assertWriteFailure(Health health, FailureType failureType) {
        Map<String, Object> details = (Map<String, Object>) health.getDetails().get("write");
        assertThat(details).containsEntry("status", Status.DOWN.getCode());
        assertThat(details).containsEntry("failureType", failureType.name());
        assertThat(details).doesNotContainKey("pgRole");
    }

    @SuppressWarnings("unchecked")
    private static void assertReadFailure(Health health, FailureType failureType) {
        Map<String, Object> details = (Map<String, Object>) health.getDetails().get("read");
        assertThat(details).containsEntry("status", Status.DOWN.getCode());
        assertThat(details).containsEntry("failureType", failureType.name());
        assertThat(details).doesNotContainKey("pgRole");
    }

    private static DataSource mockDataSourceReturning(boolean isInRecovery) {
        try {
            DataSource ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mock(ResultSet.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.executeQuery(anyString())).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getBoolean(1)).thenReturn(isInRecovery);
            when(rs.wasNull()).thenReturn(false);
            return ds;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static DataSource failingDataSource() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));
        return ds;
    }
}
