package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;
import space.br1440.platform.tracing.e2e.support.JaegerV3QueryClient;
import space.br1440.platform.tracing.e2e.support.ResourceIdentityAgentSmokeProcessRunner;

import java.io.File;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Live smoke resource-идентичности (Фаза 9): OTel Java Agent + {@code platform-tracing-otel-javaagent-extension}
 * → Resource в Jaeger.
 * <p>
 * Проверяет: identity собирается {@code PlatformResourceProvider} из {@code platform.tracing.service.*};
 * {@code environment} нормализуется (prod → production); resource-ключи не дублируются в span-атрибутах.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class ResourceIdentityAgentSmokeE2ETest {

    private static final String SERVICE_NAME = "resource-smoke-svc";
    private static final Duration TRACE_VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration AGENT_PROCESS_TIMEOUT = Duration.ofMinutes(2);

    private static Network network;
    private static GenericContainer<?> jaeger;
    private static JaegerV3QueryClient jaegerClient;
    private static String otlpEndpoint;
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
        if (network != null) {
            network.close();
        }
    }

    @Test
    void resource_identity_собирается_и_environment_нормализуется() throws Exception {
        ResourceIdentityAgentSmokeProcessRunner.run(
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                extensionJar,
                Map.of(
                        "platform.tracing.service.name", SERVICE_NAME,
                        "platform.tracing.service.version", "9.0.1",
                        "platform.tracing.service.environment", "prod",
                        "platform.tracing.service.c-group", "smoke-group"),
                AGENT_PROCESS_TIMEOUT,
                4_000L);

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> resource = jaegerClient.findResourceAttributes(SERVICE_NAME);
                    assertThat(resource)
                            .as("Resource '%s' должен появиться в Jaeger", SERVICE_NAME)
                            .isPresent();
                    Map<String, String> attrs = resource.get();
                    assertThat(attrs)
                            .as("identity из platform.tracing.service.*; env нормализован prod→production; attrs=%s", attrs)
                            .containsEntry("service.name", SERVICE_NAME)
                            .containsEntry("service.version", "9.0.1")
                            .containsEntry("deployment.environment.name", "production")
                            .containsEntry("platform.c_group", "smoke-group")
                            .containsEntry("platform.tracing.policy.version", "2026.06.08");
                });
    }

    @Test
    void resource_ключи_не_дублируются_в_span_атрибутах() throws Exception {
        ResourceIdentityAgentSmokeProcessRunner.run(
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                extensionJar,
                Map.of(
                        "platform.tracing.service.name", SERVICE_NAME,
                        "platform.tracing.service.version", "9.0.1",
                        "platform.tracing.service.environment", "production",
                        "platform.tracing.service.c-group", "smoke-group"),
                AGENT_PROCESS_TIMEOUT,
                4_000L);

        await().atMost(TRACE_VISIBILITY_TIMEOUT)
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Optional<Map<String, String>> span = jaegerClient.findSpanAttributesByName(
                            SERVICE_NAME, "resource-smoke-op");
                    assertThat(span)
                            .as("span 'resource-smoke-op' должен появиться")
                            .isPresent();
                    // service.name/version/env/c_group живут на Resource, не на span.
                    assertThat(span.get())
                            .doesNotContainKey("service.name")
                            .doesNotContainKey("service.version")
                            .doesNotContainKey("deployment.environment.name")
                            .doesNotContainKey("platform.c_group");
                });
    }
}
