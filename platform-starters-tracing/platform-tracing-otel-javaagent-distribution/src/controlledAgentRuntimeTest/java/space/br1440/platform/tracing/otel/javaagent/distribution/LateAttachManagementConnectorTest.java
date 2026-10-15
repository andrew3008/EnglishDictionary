package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.awaitility.core.ConditionTimeoutException;
import org.awaitility.core.TerminalFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.distribution.probe.ControlledAgentLateAttachProbe;

class LateAttachManagementConnectorTest {

    private static final Duration CHILD_READY_POLL_INTERVAL = Duration.ofMillis(100);

    @TempDir
    Path temporaryDirectory;

    @Test
    void late_local_management_connector_can_discover_mutation_mbean()
            throws IOException, InterruptedException, URISyntaxException, AttachNotSupportedException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        ControlledAgentChild.PreparedChild prepared = ControlledAgentChild.prepare(
                temporaryDirectory,
                "late-attach-child",
                command(fixture)
        );

        // when
        Process process = prepared.builder().start();
        ChildProcessResult result;
        try {
            long childPid = awaitReadyPid(process, prepared.stdoutFile(), prepared.stderrFile());
            assertMutationMBeanVisible(childPid);
        } finally {
            process.getOutputStream().close();
            result = ControlledAgentChild.await(
                    process,
                    prepared.stdoutFile(),
                    prepared.stderrFile(),
                    ControlledAgentChild.KILL_TIMEOUT
            );
        }

        // then
        result.assertCompletedSuccessfully();
    }

    private static List<String> command(AgentTestFixture fixture) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of("-Djava.rmi.server.hostname=127.0.0.1"));
        ControlledAgentChild.appendLoggingExporter(command);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunch(command, fixture, ControlledAgentLateAttachProbe.class, List.of());
        return command;
    }

    private static long awaitReadyPid(Process process, Path stdoutFile, Path stderrFile) throws IOException {
        try {
            return await("late attach probe READY line")
                    .atMost(ControlledAgentChild.WAIT_TIMEOUT)
                    .pollInterval(CHILD_READY_POLL_INTERVAL)
                    .failFast("late attach probe exited before reporting READY", () -> !process.isAlive())
                    .until(() -> reportedPid(stdoutFile), Optional::isPresent)
                    .orElseThrow();
        } catch (ConditionTimeoutException | TerminalFailureException failure) {
            throw new AssertionError(
                    "Late attach probe never reported READY; stdout=%s, stderr=%s".formatted(
                            Files.readString(stdoutFile, StandardCharsets.UTF_8),
                            Files.readString(stderrFile, StandardCharsets.UTF_8)),
                    failure
            );
        }
    }

    private static Optional<Long> reportedPid(Path stdoutFile) throws IOException {
        String readyPrefix = ControlledAgentLateAttachProbe.READY_LINE_PREFIX;
        return Files.readString(stdoutFile, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> line.startsWith(readyPrefix))
                .findFirst()
                .map(line -> Long.parseLong(line.substring(readyPrefix.length()).trim()));
    }

    private static void assertMutationMBeanVisible(long childPid) throws AttachNotSupportedException, IOException {
        VirtualMachine virtualMachine = VirtualMachine.attach(Long.toString(childPid));
        String serviceAddress;
        try {
            serviceAddress = virtualMachine.startLocalManagementAgent();
        } finally {
            virtualMachine.detach();
        }

        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(serviceAddress))) {
            assertThat(connector.getMBeanServerConnection()
                    .isRegistered(JmxWireContract.SETTINGS_MUTATION_OBJECT_NAME))
                    .as("a management connector started after startup discovers the mutation MBean")
                    .isTrue();
        }
    }
}
