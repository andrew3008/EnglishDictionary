package space.br1440.platform.tracing.e2e.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

/**
 * Packaged gate для удаления платформенного {@code TaskDecorator}: проверяет перенос
 * agent-owned контекста через {@code @Async} без async-BPP платформы.
 */
@DisabledIfSystemProperty(named = "skipE2e", matches = "true")
class AsyncAgentPropagationControlledAgentE2ETest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(3);
    private static String classpath;
    private static String controlledAgent;

    @BeforeAll
    static void startInfrastructure() {
        controlledAgent = System.getProperty("smoke.controlled.agent.jar");
        classpath = System.getProperty("smoke.test.runtime.classpath");
        assertThat(new File(controlledAgent)).isFile();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("executorModes")
    void propagatesAgentContextAndProjectsIdentityMdcWithoutExecutorMutation(String mode) throws Exception {
        String normalizedMode = mode.toLowerCase();
        String serviceName = "async-agent-propagation-" + normalizedMode;
        String requestId = "request-async-" + normalizedMode;
        String correlationId = "correlation-async-" + normalizedMode;
        String output = run(serviceName, mode, requestId, correlationId);

        assertThat(marker(output, mode, "agentDetected")).isEqualTo("true");
        assertThat(marker(output, mode, "runtimeState")).isEqualTo("AGENT_READY");
        assertThat(marker(output, mode, "openTelemetryBeans")).isEqualTo("0");
        if ("CUSTOM".equals(mode)) {
            assertThat(marker(output, mode, "customTaskDecoratorConfigured")).isEqualTo("false");
        }

        String callerTraceId = marker(output, mode, "caller.traceId");
        assertThat(marker(output, mode, "caller.traceValid")).isEqualTo("true");
        assertThat(callerTraceId).matches("[0-9a-f]{32}");
        assertThat(marker(output, mode, "async.traceId")).isEqualTo(callerTraceId);
        assertThat(marker(output, mode, "async.requestId")).isEqualTo(requestId);
        assertThat(marker(output, mode, "async.correlationId")).isEqualTo(correlationId);
        assertThat(marker(output, mode, "caller.mdcRequestId")).isEqualTo(requestId);
        assertThat(marker(output, mode, "caller.mdcCorrelationId")).isEqualTo(correlationId);

        assertThat(marker(output, mode, "clean.sourceTraceValid")).isEqualTo("false");
        assertThat(marker(output, mode, "clean.traceValid")).isEqualTo("false");
        assertThat(marker(output, mode, "clean.requestId")).isEqualTo("empty");
        assertThat(marker(output, mode, "clean.correlationId")).isEqualTo("empty");
        assertThat(marker(output, mode, "clean.mdcRequestId")).isEqualTo("empty");
        assertThat(marker(output, mode, "clean.mdcCorrelationId")).isEqualTo("empty");
        assertThat(marker(output, mode, "async.thread"))
                .as("Обе задачи должны последовательно выполниться на одном worker-потоке")
                .isEqualTo(marker(output, mode, "clean.thread"));

        // Agent переносит OTel Context, а узкий Spring async-advisor проецирует request identity в MDC.
        assertThat(List.of(
                marker(output, mode, "async.mdcRequestId"),
                marker(output, mode, "async.mdcCorrelationId")))
                .containsExactly(requestId, correlationId);
    }

    private static Stream<Arguments> executorModes() {
        return Stream.of(
                Arguments.of(Named.of("Boot auto-configured executor", "AUTO")),
                Arguments.of(Named.of("Application custom executor", "CUSTOM")));
    }

    private static String run(String serviceName,
                              String mode,
                              String requestId,
                              String correlationId) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        return AgentHttpSpringSmokeProcessRunner.run(
                AsyncAgentPropagationSmokeMain.class.getName(),
                controlledAgent,
                classpath,
                "http://127.0.0.1:4318",
                serviceName,
                port,
                null,
                "/async-agent",
                Map.of("X-Request-Id", requestId),
                Map.of(),
                List.of(
                        "otel.traces.sampler=platform",
                        "platform.tracing.sampling.ratio=1",
                        "platform.tracing.test.startup-readiness-timeout-ms=10000",
                        "async.probe.executor-mode=" + mode,
                        "async.probe.correlation-id=" + correlationId),
                PROCESS_TIMEOUT,
                3_000L);
    }

    private static String marker(String output, String mode, String key) {
        String prefix = "ASYNC_AGENT:" + mode + ":" + key + "=";
        return output.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Маркер не найден: " + prefix + "\n" + output));
    }
}
