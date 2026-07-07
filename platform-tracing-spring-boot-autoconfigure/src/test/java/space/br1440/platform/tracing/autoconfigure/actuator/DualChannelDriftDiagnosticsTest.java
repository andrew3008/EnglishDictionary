package space.br1440.platform.tracing.autoconfigure.actuator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-матрица 4 кейсов для {@link DualChannelDriftDiagnostics}:
 * <ol>
 *   <li>совпадает Spring=effective → no WARN;</li>
 *   <li>расходится при {@code source=env-var} → WARN;</li>
 *   <li>{@code source=default-platform} (override отсутствует) → no WARN, даже при formal mismatch;</li>
 *   <li>флаг выключен → no WARN независимо от расхождения.</li>
 * </ol>
 */
class DualChannelDriftDiagnosticsTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private TracingProperties properties;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(DualChannelDriftDiagnostics.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        properties = new TracingProperties();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("Совпадает Spring=effective env-var → no WARN")
    void noWarn_whenSpringValueEqualsEnvVar() {
        properties.getQueue().setExportTimeout(Duration.ofMillis(5_000));
        Map<String, String> envVars = new HashMap<>();
        envVars.put("OTEL_BSP_EXPORT_TIMEOUT", "5000");

        DualChannelDriftDiagnostics diag = DualChannelDriftDiagnostics.forTest(
                properties, key -> null, envVars::get, true);
        diag.afterSingletonsInstantiated();

        assertThat(warns()).isEmpty();
    }

    @Test
    @DisplayName("Расходится при source=env-var → ровно один WARN с упоминанием обоих значений")
    void warnOnce_whenSpringDiffersFromEnvVar() {
        properties.getQueue().setExportTimeout(Duration.ofMillis(100));
        Map<String, String> envVars = new HashMap<>();
        envVars.put("OTEL_BSP_EXPORT_TIMEOUT", "5000");

        DualChannelDriftDiagnostics diag = DualChannelDriftDiagnostics.forTest(
                properties, key -> null, envVars::get, true);
        diag.afterSingletonsInstantiated();

        List<String> warns = warns();
        assertThat(warns).hasSize(1);
        String warn = warns.get(0);
        assertThat(warn)
                .contains("platform.tracing.queue.export-timeout=100")
                .contains("otel.bsp.export.timeout=5000")
                .contains("source=env-var")
                .contains("OTEL_BSP_EXPORT_TIMEOUT=100")
                .contains("WARN does not mean application misconfiguration");
    }

    @Test
    @DisplayName("source=default-platform → no WARN, даже при formal mismatch с Spring-стороной")
    void noWarn_whenSourceIsDefaultPlatform() {
        // Spring задаёт 100ms, но никаких -D/env override нет → snapshot вернёт
        // source=default-platform с extension-defaults. Условие 1 не выполнено → no WARN.
        properties.getQueue().setExportTimeout(Duration.ofMillis(100));

        DualChannelDriftDiagnostics diag = DualChannelDriftDiagnostics.forTest(
                properties, key -> null, key -> null, true);
        diag.afterSingletonsInstantiated();

        assertThat(warns()).isEmpty();
    }

    @Test
    @DisplayName("Флаг dual-channel-warn=false → no WARN независимо от расхождения")
    void noWarn_whenDisabled() {
        properties.getQueue().setExportTimeout(Duration.ofMillis(100));
        Map<String, String> envVars = new HashMap<>();
        envVars.put("OTEL_BSP_EXPORT_TIMEOUT", "5000");

        DualChannelDriftDiagnostics diag = DualChannelDriftDiagnostics.forTest(
                properties, key -> null, envVars::get, false);
        diag.afterSingletonsInstantiated();

        assertThat(warns()).isEmpty();
    }

    private List<String> warns() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
