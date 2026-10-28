package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.HostlessOtlpEndpointProbe;

class HostlessOtlpEndpointAbandonmentTest {

    // Намеренно невалидный endpoint без host: проверяется поведение агента, сетевое соединение не устанавливается.
    @SuppressWarnings("HttpUrlsUsage")
    private static final String HOSTLESS_OTLP_ENDPOINT = "http:///v1/traces";

    @TempDir
    Path temporaryDirectory;

    @Test
    void hostless_otlp_endpoint_leaves_diagnostic_readiness_initializing()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        command.add("-Dotel.traces.exporter=otlp");
        command.add("-Dotel.exporter.otlp.endpoint=" + HOSTLESS_OTLP_ENDPOINT);
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
                        + " lifecycle=INITIALIZING runtimeMBeans=absent platformThreads=[]");
        assertThat(result.stderr()).contains("ConfigurationException");
    }
}
