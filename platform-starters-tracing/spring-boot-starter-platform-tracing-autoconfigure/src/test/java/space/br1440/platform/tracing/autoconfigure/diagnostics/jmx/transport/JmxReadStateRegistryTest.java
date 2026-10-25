package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class JmxReadStateRegistryTest {

    private static final JmxContractPoint POINT = JmxContractPoint.EXPORT_OBSERVATION;
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final JmxReadStateRegistry registry =
            new JmxReadStateRegistry(Clock.fixed(BASE, ZoneOffset.UTC), Duration.ofMinutes(5));

    @Test
    void staleFailureDeliveredAfterNewerSuccessDoesNotReopenUnrecovered() {
        registry.recordPresent(POINT, 11L, BASE.plusSeconds(1));
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 10L, BASE);

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.unrecovered()).isFalse();
        assertThat(snapshot.lifetimeCount()).isEqualTo(1L);
        assertThat(registry.pointState(POINT).lastAppliedSequence()).isEqualTo(11L);
    }

    @Test
    void staleSuccessDeliveredAfterNewerFailureDoesNotClearUnrecovered() {
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 20L, BASE.plusSeconds(1));
        registry.recordPresent(POINT, 19L, BASE);

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.unrecovered()).isTrue();
        assertThat(registry.pointState(POINT).lastAppliedSequence()).isEqualTo(20L);
    }

    @Test
    void lifetimeCountsSurviveOutOfOrderDeliveryEvenWhenCurrentStateIsRejected() {
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 5L, BASE);
        registry.recordPresent(POINT, 6L, BASE.plusSeconds(1));
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 4L, BASE.minusSeconds(1));

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.lifetimeCount()).isEqualTo(2L);
        assertThat(registry.pointState(POINT).lastAppliedSequence()).isEqualTo(6L);
    }

    @Test
    void recoveryThenNewFailureOpensSecondEpisodeAndWarnsAgain() {
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 1L, BASE);
        registry.recordPresent(POINT, 2L, BASE.plusSeconds(1));
        registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 3L, BASE.plusSeconds(2));

        JmxDiagnosticSnapshot snapshot = onlySnapshot();
        assertThat(snapshot.unrecovered()).isTrue();
        assertThat(snapshot.lifetimeCount()).isEqualTo(2L);
    }

    @Test
    void firstFailureEmitsWarnDespiteUninitializedRateLimitSentinel() {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(JmxReadStateRegistry.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);

        try {
            // when
            registry.recordContractViolation(POINT, JmxDiagnosticKind.VALUE_TYPE_MISMATCH, 1L, BASE);

            // then
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "JMX contract: CONTRACT_VIOLATION VALUE_TYPE_MISMATCH at point "
                                    + "EXPORT_OBSERVATION (first occurrence or repeat after recovery)"
                    );
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void diagnosticSnapshotIsEmptyBeforeAnyFailure() {
        registry.recordPresent(POINT, 1L, BASE);

        assertThat(registry.diagnosticSnapshot()).isEmpty();
    }

    private JmxDiagnosticSnapshot onlySnapshot() {
        List<JmxDiagnosticSnapshot> snapshots = registry.diagnosticSnapshot();
        assertThat(snapshots).hasSize(1);
        return snapshots.getFirst();
    }
}
