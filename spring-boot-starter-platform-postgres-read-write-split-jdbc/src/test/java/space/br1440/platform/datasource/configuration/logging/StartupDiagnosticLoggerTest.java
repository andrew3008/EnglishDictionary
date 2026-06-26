package space.br1440.platform.datasource.postgres.internal.integration.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.JdbcProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.properties.ReadReplicaProperties;
import space.br1440.platform.datasource.support.junit.CaptureLogs;
import space.br1440.platform.datasource.support.junit.CapturedLogs;
import space.br1440.platform.datasource.support.junit.LogCapturingExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Закрепляет security-инвариант {@link StartupDiagnosticLogger}: non-sensitive dump
 * не содержит username/password/sslpassword/полного JDBC URL, но содержит resolved
 * routing-конфигурацию.
 */
@DisplayName("StartupDiagnosticLogger — non-sensitive resolved config dump")
@ExtendWith(LogCapturingExtension.class)
@CaptureLogs(value = "space.br1440.platform.datasource.postgres.internal.integration.logging.StartupDiagnosticLogger",
             level = CaptureLogs.LogLevel.INFO)
class StartupDiagnosticLoggerTest {

    private static PgClusterProperties props() {
        return new PgClusterProperties(
                DataSourceRoutingMode.CLIENT,
                List.of(new PgNode("10.0.0.1", 5432), new PgNode("10.0.0.2", 5432)),
                "orders",
                new PgClusterProperties.Credentials("svc_user", "super-secret-pwd"),
                new JdbcProperties(Map.of("sslmode", "require", "sslpassword", "key-secret")),
                new ReadReplicaProperties(ReadMode.SECONDARY_PREFERRED),
                null, null);
    }

    @Test
    @DisplayName("логирует routing-config, но НЕ логирует секреты")
    void logsResolvedConfig_withoutSecrets(CapturedLogs logs) {
        new StartupDiagnosticLogger(props(), true, true).logResolvedConfig();

        String all = String.join("\n", logs.events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList());

        assertThat(all)
                .contains("CLIENT")
                .contains("orders")
                .contains("SECONDARY_PREFERRED")
                .contains("preferSecondary");

        assertThat(all)
                .doesNotContain("svc_user")
                .doesNotContain("super-secret-pwd")
                .doesNotContain("key-secret");
    }
}
