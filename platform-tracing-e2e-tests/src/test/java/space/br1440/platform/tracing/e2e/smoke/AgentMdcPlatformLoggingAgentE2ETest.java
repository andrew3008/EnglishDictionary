package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import space.br1440.platform.tracing.e2e.support.AgentMdcLoggingProcessRunner;
import space.br1440.platform.tracing.e2e.support.JaegerTestContainerSupport;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-MDC-e2e: traceId в логах platform-logging при Agent + suppress-micrometer-tracing=true.
 * <p>
 * Stack: platform-tracing-servlet + spring-boot-starter-platform-logging + OpenTelemetryAppender
 * (camelCase keys). См. {@code docs/decisions/ADR-mdc-via-otel-agent-logback.md}.
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
@EnabledIf("space.br1440.platform.tracing.e2e.smoke.AgentMdcPlatformLoggingAgentE2ETest#platformLoggingStarterAvailable")
class AgentMdcPlatformLoggingAgentE2ETest {

    private static final String SERVICE_NAME = "mdc-platform-logging-agent-e2e";
    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);

    private static GenericContainer<?> jaeger;
    private static String otlpEndpoint;
    private static String testRuntimeClasspath;
    private static String otelAgentJar;
    private static String extensionJar;

    static boolean platformLoggingStarterAvailable() {
        try {
            Class.forName("space.br1440.platform.logging.mdc.TracingMdcLogKeys");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

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

        jaeger = JaegerTestContainerSupport.newJaeger();
        jaeger.start();

        otlpEndpoint = JaegerTestContainerSupport.otlpHttpEndpoint(jaeger);
    }

    @AfterAll
    static void tearDownStack() {
        if (jaeger != null) {
            jaeger.stop();
        }
    }

    @Test
    void agent_platform_logging_text_mdc_содержит_traceId() throws Exception {
        runAndAssertTextMdc("classpath:logback-spring-mdc-e2e.xml");
    }

    @Test
    void agent_platform_logging_json_mdc_содержит_traceId() throws Exception {
        runAndAssertJsonMdc("classpath:logback-spring-mdc-e2e-json.xml");
    }

    private void runAndAssertTextMdc(String loggingConfig) throws Exception {
        AgentMdcLoggingProcessRunner.ProcessResult result = runSmoke(loggingConfig);

        String traceId = result.httpTraceId();
        assertThat(traceId).isNotBlank().hasSize(32);

        assertThat(result.processOutput())
                .as("platform-logging text %maskedMDC должен содержать traceId active span")
                .contains(MdcLoggingSmokeController.LOG_MARKER)
                .contains("traceId=" + traceId);
    }

    private void runAndAssertJsonMdc(String loggingConfig) throws Exception {
        AgentMdcLoggingProcessRunner.ProcessResult result = runSmoke(loggingConfig);

        String traceId = result.httpTraceId();
        assertThat(traceId).isNotBlank().hasSize(32);

        assertThat(result.processOutput())
                .as("platform-logging JSON LogstashEncoder должен содержать traceId")
                .contains(MdcLoggingSmokeController.LOG_MARKER)
                .contains("\"traceId\":\"" + traceId + "\"");
    }

    private AgentMdcLoggingProcessRunner.ProcessResult runSmoke(String loggingConfig) throws Exception {
        int httpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            httpPort = socket.getLocalPort();
        }

        return AgentMdcLoggingProcessRunner.runMdcTest(
                otelAgentJar,
                testRuntimeClasspath,
                otlpEndpoint,
                SERVICE_NAME,
                httpPort,
                extensionJar,
                Map.of(),
                List.of(
                        "platform.tracing.suppression.suppress-micrometer-tracing=true",
                        "logging.config=" + loggingConfig,
                        "otel.instrumentation.logback-mdc.enabled=false",
                        "otel.bsp.schedule.delay=200"),
                PROCESS_TIMEOUT,
                8_000L);
    }
}
