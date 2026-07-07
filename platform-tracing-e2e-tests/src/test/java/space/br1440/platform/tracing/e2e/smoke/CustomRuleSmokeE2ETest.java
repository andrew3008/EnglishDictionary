package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Production F3 proof: custom rule loading and application through the real agent path.
 * <p>
 * Uses {@code platform.tracing.scrubbing.rules.extensions} (production {@code ExtensionRuleLoader}
 * in {@code PlatformSpanProcessorFactory}) and verifies the custom rule masks span attributes
 * in Jaeger. Fails if production loader does not load/apply the custom rule.
 * <p>
 * Classloader F1 proof is separate: {@link space.br1440.platform.tracing.e2e.probe.ClassLoaderVisibilityE2ETest}.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class CustomRuleSmokeE2ETest {

    private static final String SERVICE_NAME = "custom-rule-smoke";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private static Network network;
    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
    private static String testRuntimeClasspath;
    private static String otelAgentJar;
    private static String extensionJar;
    private static String customRuleJar;

    @BeforeAll
    static void setUpStack() {
        otelAgentJar = System.getProperty("otel.javaagent.jar");
        extensionJar = System.getProperty("smoke.otel.extension.jar");
        customRuleJar = System.getProperty("smoke.custom.rule.jar");
        testRuntimeClasspath = System.getProperty("smoke.test.runtime.classpath");

        assertThat(otelAgentJar).isNotBlank();
        assertThat(extensionJar).isNotBlank();
        assertThat(customRuleJar).isNotBlank();
        assertThat(testRuntimeClasspath).isNotBlank();
        assertThat(new File(otelAgentJar)).exists().isFile();
        assertThat(new File(extensionJar)).exists().isFile();
        assertThat(new File(customRuleJar)).exists().isFile();

        network = Network.newNetwork();

        jaeger = JaegerTestContainerSupport.newJaeger(network);
        jaeger.start();

        otlpEndpoint = JaegerTestContainerSupport.otlpHttpEndpoint(jaeger);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));
    }

    @AfterAll
    static void tearDownStack() {
        if (jaeger != null) jaeger.stop();
        if (network != null) network.close();
    }

    @Test
    void custom_rule_loaded_by_agent_masks_attribute() throws Exception {
        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        // Новый контракт (ADR-classloader-visibility-spike): в otel.javaagent.extensions попадает
        // ТОЛЬКО платформенное расширение, а кастомные правила грузятся через выделенное свойство
        // platform.tracing.scrubbing.rules.extensions (отдельный URLClassLoader). Тот же JAR
        // запрещено указывать в обоих местах одновременно.
        Path extDir = Files.createTempDirectory("smoke-ext");
        Files.copy(Path.of(extensionJar), extDir.resolve("extension.jar"));
        String platformExtension = extDir.toAbsolutePath().toString();
        String customRulesProperty = Path.of(customRuleJar).toAbsolutePath().toString();

        String agentOutput = AgentHttpSpringSmokeProcessRunner.run(
                CustomRuleSmokeMain.class.getName(),
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                httpPort,
                platformExtension,
                "/probe",
                Map.of(),
                Map.of(),
                List.of(
                        "otel.traces.sampler=always_on",
                        "platform.tracing.sampling.ratio=1.0",
                        "platform.tracing.scrubbing.rules.extensions=" + customRulesProperty,
                        "otel.bsp.schedule.delay=200"),
                AGENT_PROCESS_TIMEOUT,
                8_000L);

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    java.util.List<String> names = jaegerClient.listSpanNames(SERVICE_NAME);
                    System.out.println("Span names: " + names);
                    Map<String, java.util.List<com.fasterxml.jackson.databind.JsonNode>> byScope = 
                            jaegerClient.findHttpSpansByScopeForRoute(SERVICE_NAME, "/probe");
                    System.out.println("Spans by scope: " + byScope);
                    Optional<Map<String, String>> httpSpan = jaegerClient.findFirstSpanAttributes(
                            SERVICE_NAME,
                            attrs -> attrs.containsKey("e2e.custom.marker"));
                    assertThat(httpSpan)
                            .as("Span с атрибутом e2e.custom.marker должен появиться в Jaeger; otlp=%s; services=%s; agentOutput:\n%s",
                                    otlpEndpoint, jaegerClient.listSpanNames(SERVICE_NAME), agentOutput)
                            .isPresent();

                    assertThat(httpSpan.get().get("e2e.custom.marker"))
                            .as("Значение должно быть замаскировано нашим кастомным правилом (mask(name())). agentOutput:\n%s", agentOutput)
                            .isEqualTo("***"); // ScrubbingDecision.mask() заменяет на *** в процессоре
                });
    }
}
