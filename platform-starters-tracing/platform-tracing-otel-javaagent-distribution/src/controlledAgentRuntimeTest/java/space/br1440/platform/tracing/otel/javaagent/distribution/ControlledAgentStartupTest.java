package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.distribution.probe.ControlledAgentRuntimeProbe;

class ControlledAgentStartupTest {

    private static final String EXPECTED_PROBE_CAPABILITIES = "7";
    private static final String PROBE_OK_PREFIX = "CONTROLLED_AGENT_PROBE_OK";
    private static final String SHUTDOWN_OK_MARKER = ControlledAgentRuntimeProbe.SHUTDOWN_OK_MARKER;
    private static final String BOOTSTRAP_POLICY_PROBE_OK_PREFIX = "CONTROLLED_AGENT_BOOTSTRAP_POLICY_OK";
    private static final String VERIFY_BOOTSTRAP_POLICY_ARGUMENT = "verify-bootstrap-policy";
    private static final String ANY_PTA_EVENT_CODE = "\"event_code\":\"PTA-";

    private static final Map<String, String> BOOTSTRAP_POLICY_ENVIRONMENT = Map.of(
            "PLATFORM_TRACING_SAMPLING_ENABLED", "true",
            "PLATFORM_TRACING_SAMPLING_RATIO", "1.0",
            "PLATFORM_TRACING_SAMPLING_ROUTE_RATIOS", "/health=0.0,/orders=0.25",
            "PLATFORM_TRACING_JAVAAGENT_SAMPLING_DROP_PATHS", "/bootstrap-policy-probe",
            "PLATFORM_TRACING_JAVAAGENT_SPAN_ATTRIBUTES_VALIDATION_LEVEL", "ERROR",
            "PLATFORM_TRACING_EXPORTER_ENABLED", "false",
            "PLATFORM_TRACING_PROPAGATION_TRACE_CONTROL_HEADERS_ENABLED", "false",
            "PLATFORM_TRACING_DIAGNOSTICS_LOG_LEVEL", "DEBUG"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_starts_with_attested_identity_and_ready_extension()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = ControlledAgentChild.run(
                temporaryDirectory,
                "child",
                command(fixture, false)
        );

        // then
        result.assertCompletedSuccessfully();
        assertProbeReportedExpectedIdentity(result, fixture.distributionVersion());
        assertThat(result.stdout()).doesNotContain(ANY_PTA_EVENT_CODE);
        assertStderrIsFreeOfLoggingAndExtensionFailures(result);
    }

    @Test
    void controlled_agent_reads_complete_bootstrap_policy_from_environment_before_spring()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        // when
        ChildProcessResult result = ControlledAgentChild.run(
                temporaryDirectory,
                "bootstrap-policy-child",
                command(fixture, true),
                BOOTSTRAP_POLICY_ENVIRONMENT
        );

        // then
        result.assertCompletedSuccessfully();
        assertProbeReportedExpectedIdentity(result, fixture.distributionVersion());
        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(BOOTSTRAP_POLICY_PROBE_OK_PREFIX)))
                .as("Child stdout must contain exactly one bootstrap policy probe line: %s", result.stdout())
                .containsExactly(BOOTSTRAP_POLICY_PROBE_OK_PREFIX + " sequence=0");
        assertStderrIsFreeOfLoggingAndExtensionFailures(result);
    }

    private static List<String> command(AgentTestFixture fixture, boolean verifyBootstrapPolicy) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendLoggingExporter(command);
        ControlledAgentChild.appendServiceIdentity(command);

        List<String> arguments = new ArrayList<>();
        arguments.add(fixture.distributionVersion());
        arguments.add(fixture.controlledAgent().toString());
        arguments.add(fixture.expectedOtelJavaAgentSha());
        arguments.add(fixture.expectedExtensionSha());
        if (verifyBootstrapPolicy) {
            arguments.add(VERIFY_BOOTSTRAP_POLICY_ARGUMENT);
            ControlledAgentChild.appendAgentLaunchWithApi(
                    command, fixture, ControlledAgentRuntimeProbe.class, true, arguments);
        } else {
            ControlledAgentChild.appendAgentLaunch(command, fixture, ControlledAgentRuntimeProbe.class, arguments);
        }
        return command;
    }

    private static void assertProbeReportedExpectedIdentity(ChildProcessResult result, String distributionVersion) {
        String expectedLine = PROBE_OK_PREFIX
                + " version=" + distributionVersion
                + " schema=" + JmxWireContract.SCHEMA_ID
                + " capabilities=" + EXPECTED_PROBE_CAPABILITIES;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(PROBE_OK_PREFIX)))
                .containsExactly(expectedLine);
        assertThat(result.stdout()
                .lines()
                .filter(SHUTDOWN_OK_MARKER::equals))
                .as("Child stdout must contain exactly one successful shutdown marker: %s", result.stdout())
                .containsExactly(SHUTDOWN_OK_MARKER);
    }

    private static void assertStderrIsFreeOfLoggingAndExtensionFailures(ChildProcessResult result) {
        assertThat(result.stderr())
                .doesNotContain("No SLF4J providers were found")
                .doesNotContain("Defaulting to no-operation (NOP) logger implementation")
                .doesNotContain("Failed to load extension")
                .doesNotContain("Unable to load extension")
                .doesNotContain("JMX_TRANSPORT_STALE")
                .doesNotContain("SHUTDOWN_CLEANUP_FAILED")
                .doesNotContain("PTA-BOOT-002")
                .doesNotContain("BOOTSTRAP_PHASE_FAILED")
                .doesNotContain("BOOTSTRAP_SHUTDOWN_CLEANUP_FAILED")
                .doesNotContain("BOOTSTRAP_SHUTDOWN_COMPLETION_FAILED")
                .doesNotContain("Exception in thread")
                .doesNotContain("\tat ")
                .doesNotContain("FATAL");
    }
}
