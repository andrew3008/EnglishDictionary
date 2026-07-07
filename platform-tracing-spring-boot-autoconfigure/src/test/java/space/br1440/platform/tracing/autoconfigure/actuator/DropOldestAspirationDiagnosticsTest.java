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
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Матрица WARN/INFO логики {@link DropOldestAspirationDiagnostics} (v1.x, default=DROP_OLDEST):
 * <ul>
 *   <li>Agent=unset (платформенный default DROP_OLDEST) → INFO aligned, без WARN;</li>
 *   <li>Agent=DROP_OLDEST (явный) → INFO aligned, без WARN;</li>
 *   <li>Agent=UPSTREAM (оператор явно задал) → WARN mismatch;</li>
 *   <li>Spring=DROP_NEWEST → вне scope, ничего не логируется.</li>
 * </ul>
 */
class DropOldestAspirationDiagnosticsTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger diagnosticsLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        diagnosticsLogger = (Logger) LoggerFactory.getLogger(DropOldestAspirationDiagnostics.class);
        diagnosticsLogger.addAppender(appender);
        diagnosticsLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        diagnosticsLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("Agent=unset (платформенный default DROP_OLDEST) → INFO aligned, без WARN")
    void agentUnsetMeansPlatformDefaultDropOldest_infoAligned() {
        // Когда Agent property не задан явно, PlatformTracingDefaultsProvider подставляет
        // DROP_OLDEST. С точки зрения диагностики (Spring-side) это «DROP_OLDEST».
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, noSysProps(), noEnv(), true, true);
        diag.afterSingletonsInstantiated();

        assertNoLevel(Level.WARN);
        assertHasLevelContaining(Level.INFO, "aligned");
    }

    @Test
    @DisplayName("Agent=DROP_OLDEST явный (env-var) → INFO aligned, без WARN")
    void agentExplicitDropOldestViaEnv_infoAligned() {
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        Map<String, String> envVars = new HashMap<>();
        envVars.put(DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_ENV, "DROP_OLDEST");
        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, noSysProps(), envVars::get, true, true);
        diag.afterSingletonsInstantiated();

        assertNoLevel(Level.WARN);
        assertHasLevelContaining(Level.INFO, "aligned");
    }

    @Test
    @DisplayName("Agent=DROP_OLDEST явный (System property) → INFO aligned, без WARN")
    void agentExplicitDropOldestViaSysProp_infoAligned() {
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        Map<String, String> sysProps = Map.of(
                DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_SYSPROP, "DROP_OLDEST");
        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, sysProps::get, noEnv(), true, true);
        diag.afterSingletonsInstantiated();

        assertNoLevel(Level.WARN);
        assertHasLevelContaining(Level.INFO, "aligned");
    }

    @Test
    @DisplayName("Agent=UPSTREAM (оператор явно задал) → WARN mismatch")
    void agentExplicitUpstreamEmitsWarn() {
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        Map<String, String> envVars = new HashMap<>();
        envVars.put(DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_ENV, "UPSTREAM");
        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, noSysProps(), envVars::get, true, true);
        diag.afterSingletonsInstantiated();

        assertHasLevelContaining(Level.WARN, "mismatch");
    }

    @Test
    @DisplayName("Agent=UPSTREAM через System property → WARN mismatch")
    void agentUpstreamViaSysPropEmitsWarn() {
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        Map<String, String> sysProps = Map.of(
                DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_SYSPROP, "UPSTREAM");
        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, sysProps::get, noEnv(), true, true);
        diag.afterSingletonsInstantiated();

        assertHasLevelContaining(Level.WARN, "mismatch");
    }

    @Test
    @DisplayName("Spring=DROP_NEWEST — диагностика молчит (out of scope)")
    void dropNewestSpringIsOutOfScope() {
        TracingProperties props = new TracingProperties();
        props.getQueue().setPolicy(TracingProperties.Queue.OverflowPolicy.DROP_NEWEST);
        MockEnvironment env = new MockEnvironment();

        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, noSysProps(), noEnv(), true, true);
        diag.afterSingletonsInstantiated();

        assertNoLevel(Level.WARN);
        assertNoLevel(Level.INFO);
    }

    @Test
    @DisplayName("Флаги warn=false/info=false полностью отключают логирование")
    void disabledFlagsSuppressAllOutput() {
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        // даже при Agent=UPSTREAM warn=false не должен выводить ничего
        Map<String, String> envVars = Map.of(
                DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_ENV, "UPSTREAM");
        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, noSysProps(), envVars::get, false, false);
        diag.afterSingletonsInstantiated();

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("snapshot() возвращает структурированный фрагмент для actuator")
    void snapshotExposesStructuredFields() {
        TracingProperties props = new TracingProperties();
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("application",
                Map.of(DropOldestAspirationDiagnostics.SPRING_POLICY_PROPERTY, "DROP_OLDEST")));
        Map<String, String> envVars = Map.of(
                DropOldestAspirationDiagnostics.AGENT_OVERFLOW_POLICY_ENV, "drop_oldest");
        DropOldestAspirationDiagnostics diag = DropOldestAspirationDiagnostics.forTest(
                props, env, noSysProps(), envVars::get, true, true);

        Map<String, Object> snap = diag.snapshot();
        assertThat(snap).containsEntry("springPolicy", "DROP_OLDEST");
        assertThat(snap).containsEntry("springPolicyExplicit", true);
        assertThat(snap).containsEntry("agentPolicy", "DROP_OLDEST");
        assertThat(snap).containsEntry("agentDropOldestActive", true);
        assertThat(snap).containsEntry("warnEnabled", true);
        assertThat(snap).containsEntry("infoEnabled", true);
    }

    // ---------------------------------------------------------------------------------------------

    private static Function<String, String> noSysProps() {
        return name -> null;
    }

    private static Function<String, String> noEnv() {
        return name -> null;
    }

    private void assertNoLevel(Level level) {
        List<ILoggingEvent> filtered = appender.list.stream()
                .filter(e -> e.getLevel().equals(level))
                .toList();
        assertThat(filtered)
                .as("сообщений уровня %s быть не должно", level)
                .isEmpty();
    }

    private void assertHasLevelContaining(Level level, String fragment) {
        boolean has = appender.list.stream()
                .filter(e -> e.getLevel().equals(level))
                .anyMatch(e -> e.getFormattedMessage().contains(fragment));
        assertThat(has)
                .as("ожидаемое сообщение уровня %s, содержащее '%s', не найдено", level, fragment)
                .isTrue();
    }
}
