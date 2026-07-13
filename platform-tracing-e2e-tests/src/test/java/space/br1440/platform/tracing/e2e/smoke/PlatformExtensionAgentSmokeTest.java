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

import java.util.List;

import java.util.Map;

import java.util.Optional;



import static org.assertj.core.api.Assertions.assertThat;

import static org.awaitility.Awaitility.await;



/**

 * Live smoke: OTel Java Agent + {@code platform-tracing-otel-extension} → {@code EnrichingSpanProcessor}.

 * <p>

 * Assertion через Jaeger Query API (Option B): надёжнее file exporter на scratch-образе Collector

 * в remote Docker окружении. File exporter (Option C) — {@link OtelCollectorFileExporterSmokeTest}.

 */

@Testcontainers

@DisabledIfSystemProperty(named = "skipE2e", matches = "true",

        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")

class PlatformExtensionAgentSmokeTest {



    private static final String SERVICE_NAME = "platform-extension-agent-smoke";

    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(2);



    private static Network network;

    private static PostgreSQLContainer<?> postgres;

    private static GenericContainer<?> jaeger;

    private static JaegerV3QueryClient jaegerClient;

    private static String otlpEndpoint;

    private static String jaegerQueryBaseUrl;

    private static String testRuntimeClasspath;

    private static String otelAgentJar;

    private static String extensionJar;



    @BeforeAll

    static void setUpStack() {

        otelAgentJar = System.getProperty("otel.javaagent.jar");

        extensionJar = System.getProperty("smoke.otel.extension.jar");

        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");



        assertThat(otelAgentJar).isNotBlank();

        assertThat(extensionJar).isNotBlank();

        assertThat(testRuntimeClasspath).isNotBlank();

        assertThat(new File(otelAgentJar)).exists().isFile();

        assertThat(new File(extensionJar)).exists().isFile();



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

        jaegerQueryBaseUrl = JaegerTestContainerSupport.queryBaseUrl(jaeger);

        jaegerClient = new JaegerV3QueryClient(jaegerQueryBaseUrl);

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

     * Agent + extension JAR: JDBC span с {@code db.system} обогащается до {@code platform.trace.type=database}.

     */

    @Test

    void agent_with_extension_выставляет_platform_type_database() throws Exception {

        String agentOutput = AgentJdbcSmokeProcessRunner.run(

                otelAgentJar,

                testRuntimeClasspath,

                otlpEndpoint,

                SERVICE_NAME,

                postgres.getJdbcUrl(),

                postgres.getUsername(),

                postgres.getPassword(),

                extensionJar,

                Map.of("OTEL_TRACES_SAMPLER", "always_on"),

                List.of(
                        "otel.bsp.schedule.delay=200",
                        "platform.tracing.sampling.ratio=1.0"),

                AGENT_PROCESS_TIMEOUT,

                8_000L);



        await().atMost(TRACE_VISIBILITY_TIMEOUT)

                .pollInterval(Duration.ofSeconds(1))

                .untilAsserted(() -> {

                    Optional<Map<String, String>> jdbcSpan = jaegerClient.findFirstSpanAttributes(

                            SERVICE_NAME,

                            attrs -> attrs.containsKey("db.system") || attrs.containsKey("db.system.name"));

                    List<String> spanNames = jaegerClient.listSpanNames(SERVICE_NAME);

                    assertThat(jdbcSpan)

                            .as("JDBC span от Agent с extension должен появиться в Jaeger")

                            .as("JDBC span from Agent with extension must appear in Jaeger; "
                                            + "otlpEndpoint=%s; jaegerQuery=%s; extensionJar=%s; spanNames=%s; agentOutput:%n%s",
                                    otlpEndpoint, jaegerQueryBaseUrl, extensionJar, spanNames, agentOutput)

                            .isPresent();

                    Map<String, String> attrs = jdbcSpan.get();

                    assertThat(attrs)

                            .as("EnrichingSpanProcessor должен выставить platform.trace.type=database; attrs=%s", attrs)

                            .containsEntry("platform.trace.type", "database");

                });

    }

}
