package space.br1440.platform.datasource.config.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy;
import space.br1440.platform.datasource.configuration.support.ReadStrategyStartupLogger;
import space.br1440.platform.datasource.support.junit.CaptureLogs;
import space.br1440.platform.datasource.support.junit.CapturedLogs;
import space.br1440.platform.datasource.support.junit.LogCapturingExtension;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReadStrategyStartupLogger")
@ExtendWith(LogCapturingExtension.class)
@CaptureLogs(value = "space.br1440.platform.datasource.configuration.support.ReadStrategyStartupLogger",
             level = CaptureLogs.LogLevel.INFO)
class ReadStrategyStartupLoggerTest {

    private final ReadStrategyStartupLogger logger = new ReadStrategyStartupLogger();

    @Test
    @DisplayName("SECONDARY_ONLY: INFO содержит имя стратегии и захардкоженное targetServerType=preferSecondary")
    void logStrategy_secondaryOnly_emitsInfo(CapturedLogs logs) {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setNodes(java.util.List.of("10.0.0.1:5432", "10.0.0.2:5432"));
        props.getReadReplica().setStrategy(Strategy.SECONDARY_ONLY);

        logger.logStrategy(props);

        assertThat(events(logs, Level.INFO))
                .anySatisfy(msg -> assertThat(msg)
                        .contains("SECONDARY_ONLY")
                        .contains("preferSecondary"));
        assertThat(events(logs, Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("LOAD_BALANCED: INFO про LOAD_BALANCED, без WARN при нормальной топологии")
    void logStrategy_loadBalanced_twoNodes_emitsInfoOnly(CapturedLogs logs) {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setNodes(java.util.List.of("10.0.0.1:5432", "10.0.0.2:5432"));
        props.getReadReplica().setStrategy(Strategy.LOAD_BALANCED);

        logger.logStrategy(props);

        assertThat(events(logs, Level.INFO))
                .anySatisfy(msg -> assertThat(msg).contains("LOAD_BALANCED"));
        assertThat(events(logs, Level.WARN)).isEmpty();
    }

    @Test
    @DisplayName("LOAD_BALANCED: WARN про single-node, если nodes содержит одну ноду")
    void logStrategy_loadBalanced_singleNode_emitsSingleNodeWarn(CapturedLogs logs) {
        PostgresClusterDataSourceProperties props = new PostgresClusterDataSourceProperties();
        props.setNodes(java.util.List.of("10.0.0.1:5432"));
        props.getReadReplica().setStrategy(Strategy.LOAD_BALANCED);

        logger.logStrategy(props);

        assertThat(events(logs, Level.WARN))
                .anySatisfy(msg -> assertThat(msg)
                        .contains("LOAD_BALANCED")
                        .contains("1 pg-node"));
    }

    private static java.util.List<String> events(CapturedLogs logs, Level level) {
        return logs.events().stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
