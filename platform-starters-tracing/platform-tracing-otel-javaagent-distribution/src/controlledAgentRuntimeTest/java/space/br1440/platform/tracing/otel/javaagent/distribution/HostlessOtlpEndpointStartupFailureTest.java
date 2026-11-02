package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.HostlessOtlpEndpointProbe;

class HostlessOtlpEndpointStartupFailureTest {

    // Намеренно невалидный endpoint без host: проверяется поведение агента, сетевое соединение не устанавливается.
    @SuppressWarnings("HttpUrlsUsage")
    private static final String HOSTLESS_OTLP_ENDPOINT = "http:///v1/traces";
    private static final String PATH_CANARY = "startup-path-secret-canary-7d84a2";
    private static final String QUERY_CANARY = "startup-query-secret-canary-2fb091";
    private static final String HOSTLESS_OTLP_ENDPOINT_WITH_CANARIES =
            HOSTLESS_OTLP_ENDPOINT + "/" + PATH_CANARY + "?api_key=" + QUERY_CANARY;

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(strings = {HOSTLESS_OTLP_ENDPOINT, HOSTLESS_OTLP_ENDPOINT_WITH_CANARIES})
    void hostless_otlp_endpoint_publishes_startup_failure_after_prepare_without_endpoint_secrets(String endpoint)
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.endpoint=" + endpoint);
        command.add("-Dotel.metrics.exporter=none");
        command.add("-Dotel.logs.exporter=none");
        ControlledAgentChild.appendSamplingAlwaysOn(command);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunch(
                command,
                fixture,
                HostlessOtlpEndpointProbe.class,
                List.of()
        );

        // when
        ChildProcessResult result = ControlledAgentChild.run(
                temporaryDirectory,
                "hostless-otlp-endpoint",
                command
        );

        // then
        result.assertCompletedSuccessfully();
        assertThat(result.stdout()).contains(HostlessOtlpEndpointProbe.MAIN_REACHED_MARKER);
        assertThat(result.stdout().lines()
                .filter(line -> line.startsWith(HostlessOtlpEndpointProbe.RESULT_PREFIX)))
                .containsExactly(HostlessOtlpEndpointProbe.RESULT_PREFIX
                        + " lifecycle=FAILED failureCode=AGENT_STARTUP_FAILED primaryFailureCode=AGENT_STARTUP_FAILED"
                        + " runtimeMBeans=absent platformThreads=[]");
        result.assertStartupFailureSummary();
        assertThat(result.stdout()).doesNotContain(PATH_CANARY, QUERY_CANARY);
        assertThat(result.stderr())
                .contains("\"error_type\":\"io.opentelemetry.sdk.autoconfigure.spi.ConfigurationException\"")
                .doesNotContain(PATH_CANARY, QUERY_CANARY)
                .doesNotContain("OpenTelemetry Javaagent failed to start")
                .doesNotContain("\tat ");
    }
}
