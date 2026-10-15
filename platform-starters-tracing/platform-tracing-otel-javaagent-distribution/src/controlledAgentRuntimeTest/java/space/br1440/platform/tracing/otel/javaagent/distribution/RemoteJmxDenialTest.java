package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.ControlledAgentRemoteJmxDenialProbe;

class RemoteJmxDenialTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void management_agent_argument_fails_readiness_without_registering_mutation()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = ControlledAgentChild.run(
                temporaryDirectory,
                "remote-jmx-denial-child",
                command(fixture)
        );

        // then
        result.assertCompletedSuccessfully();
        String expectedLine = ControlledAgentRemoteJmxDenialProbe.OK_PREFIX
                + " failureCode=" + ControlledAgentRemoteJmxDenialProbe.EXPECTED_FAILURE_CODE
                + " mutationRegistered=false readinessRegistered=true";
        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(ControlledAgentRemoteJmxDenialProbe.OK_PREFIX)))
                .as("Remote JMX denial probe must report the terminal FAILED snapshot; stdout=%s, stderr=%s",
                        result.stdout(), result.stderr())
                .containsExactly(expectedLine);
    }

    private static List<String> command(AgentTestFixture fixture) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(
                List.of("-Dcom.sun.management.jmxremote.authenticate=false"));
        ControlledAgentChild.appendLoggingExporter(command);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunch(
                command, fixture, ControlledAgentRemoteJmxDenialProbe.class, List.of());
        return command;
    }
}
