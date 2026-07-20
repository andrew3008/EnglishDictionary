package space.br1440.platform.tracing.e2e.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;

@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class AgentExtensionFailClosedSecurityE2ETest {

    private static final String SECRET = "Bearer e1-sensitive-value";
    private static final String HEADER_ATTRIBUTE = "http.request.header.authorization";
    private static final Duration TRACE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);

    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
    private static String runtimeClasspath;
    private static String agentJar;
    private static String controlledAgentJar;
    private static String failureFixtureAgentJar;

    @BeforeAll
    static void startCollector() {
        agentJar = System.getProperty("otel.javaagent.jar");
        controlledAgentJar = System.getProperty("smoke.controlled.agent.jar");
        failureFixtureAgentJar = System.getProperty("smoke.e2.failure.agent.jar");
        runtimeClasspath = System.getProperty("smoke.test.runtime.classpath");
        assertThat(new File(agentJar)).isFile();
        assertThat(new File(controlledAgentJar)).isFile();
        assertThat(new File(failureFixtureAgentJar)).isFile();
        jaeger = JaegerTestContainerSupport.newJaeger();
        jaeger.start();
        otlpEndpoint = JaegerTestContainerSupport.otlpHttpEndpoint(jaeger);
        jaegerClient = new JaegerV3QueryClient(JaegerTestContainerSupport.queryBaseUrl(jaeger));
    }

    @AfterAll
    static void stopCollector() {
        if (jaeger != null) {
            jaeger.stop();
        }
    }

    @Test
    void stockAgentExportsSensitiveHeaderWhilePlatformExtensionRemovesIt() throws Exception {
        String stockService = "e1-stock-agent-unprotected";
        String protectedService = "e1-platform-agent-protected";

        String stockOutput = runRequest(agentJar, stockService, null, List.of("otel.traces.sampler=always_on"));
        assertThat(stockOutput).contains("WEBMVC_E2:openTelemetryBeans=0");
        Optional<Map<String, String>> stockSpan = awaitSpan(stockService);
        assertThat(stockSpan).isPresent();
        assertThat(stockSpan.orElseThrow().get(HEADER_ATTRIBUTE))
                .as("Stock Agent без extension экспортирует захваченный sensitive header")
                .contains(SECRET);

        String protectedOutput = runRequest(controlledAgentJar, protectedService, null, List.of(
                "otel.traces.sampler=platform",
                "platform.tracing.sampling.ratio=1"));
        assertThat(protectedOutput).contains("WEBMVC_E2:openTelemetryBeans=0");
        Optional<Map<String, String>> protectedSpan = awaitSpan(protectedService);
        assertThat(protectedSpan).isPresent();
        assertThat(protectedSpan.orElseThrow())
                .as("Platform sanitizer удаляет значение Authorization до export")
                .doesNotContainValue(SECRET);
        assertThat(protectedSpan.orElseThrow().getOrDefault(HEADER_ATTRIBUTE, ""))
                .doesNotContain("e1-sensitive-value");
        assertThat(jaegerClient.serverTraceIdsForRoute(stockService, "/probe")).hasSize(1);
        assertThat(jaegerClient.serverTraceIdsForRoute(protectedService, "/probe")).hasSize(1);
    }

    @Test
    void mandatorySanitizerFailureLeavesAgentExportPathClosed() throws Exception {
        String serviceName = "e2-controlled-agent-sanitizer-failed";
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        AgentHttpSpringSmokeProcessRunner.RunResult result = AgentHttpSpringSmokeProcessRunner.runMeasured(
                AgentSpringForceSamplingSmokeMain.class.getName(),
                controlledAgentJar,
                runtimeClasspath,
                otlpEndpoint,
                serviceName,
                port,
                null,
                "/probe",
                Map.of("Authorization", SECRET),
                Map.of(),
                List.of(
                        "otel.traces.sampler=always_on",
                        "otel.instrumentation.http.server.capture-request-headers=authorization",
                        "platform.tracing.scrubbing.enabled=false",
                        "platform.tracing.suppression.suppress-micrometer-tracing=true"),
                PROCESS_TIMEOUT,
                2_000L,
                false,
                false);

        assertThat(result.output())
                .contains("scrubbing.enabled=false is forbidden by the secure Agent profile");
        await().during(Duration.ofSeconds(5))
                .atMost(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(jaegerClient.findFirstSpanAttributes(
                        serviceName, attributes -> true)).isEmpty());
    }

    @Test
    void everyMandatoryAutoconfigureCallbackFailureLeavesExportPathClosed() throws Exception {
        for (String stage : List.of(
                "extension-initialization",
                "configuration",
                "sanitizer",
                "sampler",
                "span-processor",
                "propagation",
                "exporter",
                "protected-export-path")) {
            String serviceName = "e2-failed-" + stage;
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }

            AgentHttpSpringSmokeProcessRunner.RunResult result = AgentHttpSpringSmokeProcessRunner.runMeasured(
                    AgentSpringForceSamplingSmokeMain.class.getName(),
                    failureFixtureAgentJar,
                    runtimeClasspath,
                    otlpEndpoint,
                    serviceName,
                    port,
                    null,
                    "/probe",
                    Map.of("Authorization", SECRET),
                    Map.of(),
                    List.of(
                            "platform.tracing.e2.failure-stage=" + stage,
                            "otel.traces.sampler=always_on",
                            "otel.instrumentation.http.server.capture-request-headers=authorization",
                            "platform.tracing.suppression.suppress-micrometer-tracing=true"),
                    PROCESS_TIMEOUT,
                    500L,
                    false,
                    false);

            assertThat(result.output()).contains("E2_TEST_ONLY_MANDATORY_PIPELINE_FAILURE:" + stage);
            await().during(Duration.ofSeconds(2))
                    .atMost(Duration.ofSeconds(4))
                    .pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertThat(jaegerClient.findFirstSpanAttributes(
                            serviceName, attributes -> true)).isEmpty());
        }
    }

    private static String runRequest(String selectedAgentJar, String serviceName, String extension,
                                     List<String> samplerProperties)
            throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        List<String> properties = new java.util.ArrayList<>(samplerProperties);
        if (selectedAgentJar.equals(agentJar)) {
            properties.add("e2.stock.agent.baseline=true");
        }
        properties.add("otel.instrumentation.http.server.capture-request-headers=authorization");
        properties.add("platform.tracing.suppression.suppress-micrometer-tracing=true");
        properties.add("otel.bsp.schedule.delay=200");

        return AgentHttpSpringSmokeProcessRunner.run(
                AgentSpringForceSamplingSmokeMain.class.getName(),
                selectedAgentJar,
                runtimeClasspath,
                otlpEndpoint,
                serviceName,
                port,
                extension,
                "/probe",
                Map.of("Authorization", SECRET),
                Map.of(),
                properties,
                PROCESS_TIMEOUT,
                5_000L);
    }

    private static Optional<Map<String, String>> awaitSpan(String serviceName) {
        AtomicReference<Optional<Map<String, String>>> result = new AtomicReference<>(Optional.empty());
        await().atMost(TRACE_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    result.set(jaegerClient.findFirstSpanAttributes(
                            serviceName,
                            attributes -> "/probe".equals(attributes.get("http.route"))
                                    || "/probe".equals(attributes.get("url.path"))));
                    assertThat(result.get()).isPresent();
                });
        return result.get();
    }
}
