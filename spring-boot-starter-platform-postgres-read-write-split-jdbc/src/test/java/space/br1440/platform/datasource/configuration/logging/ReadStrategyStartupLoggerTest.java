package space.br1440.platform.datasource.postgres.internal.integration.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.properties.ReadReplicaProperties;
import space.br1440.platform.datasource.support.junit.CaptureLogs;
import space.br1440.platform.datasource.support.junit.CapturedLogs;
import space.br1440.platform.datasource.support.junit.LogCapturingExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReadStrategyStartupLogger — honest ReadMode logging")
@ExtendWith(LogCapturingExtension.class)
@CaptureLogs(value = "space.br1440.platform.datasource.postgres.internal.integration.logging.ReadStrategyStartupLogger",
             level = CaptureLogs.LogLevel.INFO)
class ReadStrategyStartupLoggerTest {

    private final ReadStrategyStartupLogger logger = new ReadStrategyStartupLogger();

    private static PgClusterProperties props(ReadMode mode, List<PgNode> nodes) {
        return new PgClusterProperties(
                DataSourceRoutingMode.CLIENT, nodes, "orders", null, null,
                new ReadReplicaProperties(mode), null, null);
    }

    @Test
    @DisplayName("SECONDARY_PREFERRED: INFO содержит имя режима и targetServerType=preferSecondary, без WARN")
    void secondaryPreferred_emitsInfo(CapturedLogs logs) {
        logger.logReadMode(props(ReadMode.SECONDARY_PREFERRED,
                List.of(new PgNode("10.0.0.1", 5432), new PgNode("10.0.0.2", 5432))));

        assertThat(events(logs, Level.INFO))
                .anySatisfy(msg -> assertThat(msg)
                        .contains("SECONDARY_PREFERRED")
                        .contains("preferSecondary"));
        assertThat(events(logs, Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("STRICT_SECONDARY: INFO про secondary без fallback, без WARN")
    void strictSecondary_emitsInfo(CapturedLogs logs) {
        logger.logReadMode(props(ReadMode.STRICT_SECONDARY,
                List.of(new PgNode("10.0.0.1", 5432), new PgNode("10.0.0.2", 5432))));

        assertThat(events(logs, Level.INFO))
                .anySatisfy(msg -> assertThat(msg)
                        .contains("STRICT_SECONDARY")
                        .contains("secondary"));
        assertThat(events(logs, Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("ANY_NODE_LOAD_BALANCED: WARN про возможное чтение с primary, с числом нод")
    void anyNodeLoadBalanced_emitsWarn(CapturedLogs logs) {
        logger.logReadMode(props(ReadMode.ANY_NODE_LOAD_BALANCED,
                List.of(new PgNode("10.0.0.1", 5432), new PgNode("10.0.0.2", 5432))));

        assertThat(events(logs, Level.WARN))
                .anySatisfy(msg -> assertThat(msg)
                        .contains("ANY_NODE_LOAD_BALANCED")
                        .contains("2"));
    }

    private static List<String> events(CapturedLogs logs, Level level) {
        return logs.events().stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
