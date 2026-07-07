package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import space.br1440.platform.tracing.e2e.support.AgentJdbcSmokeProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Live smoke: OTel Java Agent 2.28.x + PostgreSQL (Testcontainers) → Jaeger OTLP.
 * <p>
 * Подтверждает spike {@code db.system} vs {@code db.system.name} из
 * {@code docs/decisions/ADR-db-semconv-detection.md} на реальной JDBC-инструментации Agent'а.
 * <p>
 * Agent запускается в <b>дочернем процессе</b> ({@link AgentJdbcSmokeMain}), т.к. premain
 * нельзя подключить к уже работающей JVM JUnit.
 *
 * <h2>Условия запуска</h2>
 * {@code ./gradlew :platform-tracing-e2e-tests:test -PrunE2e --tests "*DbSemconvAgentSmokeTest*"}
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class DbSemconvAgentSmokeTest {

    private static final String SERVICE_NAME = "db-semconv-agent-smoke";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private static Network network;
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
    private static String testRuntimeClasspath;
    private static String otelAgentJar;

    @BeforeAll
    static void setUpStack() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");
        assertThat(otelAgentJar)
                .as("Gradle должен передать -Dotel.javaagent.jar=... (см. build.gradle e2e-модуля)")
                .isNotBlank();
        assertThat(new File(otelAgentJar))
                .as("OTel Java Agent JAR")
                .exists()
                .isFile();
        assertThat(testRuntimeClasspath).isNotBlank();

        network = Network.newNetwork();

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withDatabaseName("tracing_smoke")
                .withUsername("smoke")
                .withPassword("smoke");
        postgres.start();

        jaeger = JaegerTestContainerSupport.newJaeger(network);
        jaeger.start();

        otlpEndpoint = JaegerTestContainerSupport.otlpHttpEndpoint(jaeger);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));
    }

    @AfterAll
    static void tearDownStack() {
        if (jaeger != null) {
            jaeger.stop();
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    /**
     * Production-default Agent 2.28.x без semconv opt-in: JDBC пишет legacy {@code db.system}.
     */
    @Test
    void agent_default_config_pишет_legacy_db_system() throws Exception {
        runAgentJdbcSmoke(Map.of());

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findJdbcSpanAttributes();
                    assertThat(attrs)
                            .as("JDBC span от Agent должен появиться в Jaeger")
                            .isPresent();
                    assertThat(attrs.get())
                            .as("legacy semconv (default без opt-in)")
                            .containsEntry("db.system", "postgresql")
                            .doesNotContainKey("db.system.name");
                });
    }

    /**
     * При {@code OTEL_SEMCONV_STABILITY_OPT_IN=database} Agent 2.28.x пишет stable {@code db.system.name}.
     */
    @Test
    void agent_stable_semconv_opt_in_pишет_db_system_name() throws Exception {
        runAgentJdbcSmoke(Map.of("OTEL_SEMCONV_STABILITY_OPT_IN", "database"));

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findJdbcSpanAttributes();
                    assertThat(attrs)
                            .as("JDBC span от Agent должен появиться в Jaeger")
                            .isPresent();
                    assertThat(attrs.get())
                            .as("stable semconv (opt-in=database)")
                            .containsEntry("db.system.name", "postgresql")
                            .doesNotContainKey("db.system");
                });
    }

    /**
     * Migration mode {@code database/dup}: Agent пишет оба semconv-атрибута одновременно.
     */
    @Test
    void agent_dup_semconv_opt_in_pишет_оба_атрибута() throws Exception {
        runAgentJdbcSmoke(Map.of("OTEL_SEMCONV_STABILITY_OPT_IN", "database/dup"));

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> attrs = findJdbcSpanAttributes();
                    assertThat(attrs)
                            .as("JDBC span от Agent должен появиться в Jaeger")
                            .isPresent();
                    assertThat(attrs.get())
                            .as("migration mode database/dup")
                            .containsEntry("db.system", "postgresql")
                            .containsEntry("db.system.name", "postgresql");
                });
    }

    private static Optional<Map<String, String>> findJdbcSpanAttributes() throws Exception {
        return jaegerClient.findFirstSpanAttributes(SERVICE_NAME, DbSemconvAgentSmokeTest::looksLikeJdbcSpan);
    }

    private static boolean looksLikeJdbcSpan(Map<String, String> attrs) {
        return attrs.containsKey("db.system") || attrs.containsKey("db.system.name");
    }

    private void runAgentJdbcSmoke(Map<String, String> extraEnv) throws Exception {
        AgentJdbcSmokeProcessRunner.run(
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword(),
                null,
                extraEnv,
                null,
                AGENT_PROCESS_TIMEOUT,
                4_000L);
    }
}
