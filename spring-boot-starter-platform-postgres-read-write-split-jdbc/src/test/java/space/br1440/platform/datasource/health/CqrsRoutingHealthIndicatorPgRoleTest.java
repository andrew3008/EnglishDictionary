package space.br1440.platform.datasource.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Закрепляет контракт {@link CqrsRoutingHealthIndicator}:
 * ключи intent-слоя {@code write} / {@code read} + динамически определяемое поле {@code pgRole}
 * на основе {@code SELECT pg_is_in_recovery()}.
 *
 * <p>Критичный кейс LOAD_BALANCED: read-пул в разные моменты может подключиться к Primary или Standby.
 * Тест подтверждает, что health отражает <b>фактическое</b> состояние соединения, а не конфигурацию
 * ReadStrategy — что соответствует назначению health indicator'а (отвечать «что сейчас»).
 */
@DisplayName("CqrsRoutingHealthIndicator: intent details + dynamic pgRole")
class CqrsRoutingHealthIndicatorPgRoleTest {

    @Test
    @DisplayName("UP: write-пул на Primary, read-пул на Secondary — классический SECONDARY_ONLY")
    void healthUpShowsPrimaryAndStandby() {
        DataSource write = mockDataSourceReturning(false); // pg_is_in_recovery() = false → primary
        DataSource read = mockDataSourceReturning(true);   // pg_is_in_recovery() = true  → standby

        Health health = new CqrsRoutingHealthIndicator(write, read).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertWriteDetail(health, Status.UP.getCode(), "primary");
        assertReadDetail(health, Status.UP.getCode(), "standby");
    }

    @Test
    @DisplayName("LOAD_BALANCED: read-пул подключился к Primary — pgRole отражает факт, не конфиг")
    void loadBalancedReadPoolConnectedToPrimaryReflectsActualRole() {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = mockDataSourceReturning(false); // LB направил на Primary прямо сейчас

        Health health = new CqrsRoutingHealthIndicator(write, read).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertReadDetail(health, Status.UP.getCode(), "primary");
    }

    @Test
    @DisplayName("DOWN: оба пула недоступны — pgRole=unknown для обоих")
    void healthDownWhenBothFail() throws Exception {
        DataSource write = failingDataSource();
        DataSource read = failingDataSource();

        Health health = new CqrsRoutingHealthIndicator(write, read).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertWriteDetail(health, Status.DOWN.getCode(), "unknown");
        assertReadDetail(health, Status.DOWN.getCode(), "unknown");
    }

    @Test
    @DisplayName("DEGRADED: write UP, read DOWN")
    void healthDegradedWhenReadFails() throws Exception {
        DataSource write = mockDataSourceReturning(false);
        DataSource read = failingDataSource();

        Health health = new CqrsRoutingHealthIndicator(write, read).health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        assertWriteDetail(health, Status.UP.getCode(), "primary");
        assertReadDetail(health, Status.DOWN.getCode(), "unknown");
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
            return ds;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static DataSource failingDataSource() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new RuntimeException("connection refused"));
        return ds;
    }
}
