package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.InvalidExtensionConfigurationProbe;

class InvalidExtensionConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void invalid_extension_configuration_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String controlSpanName = "extension-configuration-control-" + UUID.randomUUID();
        String invalidSpanName = "extension-configuration-invalid-" + UUID.randomUUID();
        ChildProcessResult control;
        ChildProcessResult invalid;
        OtlpTraceCollector controlCollector;
        OtlpTraceCollector invalidCollector;

        try (OtlpTraceCollector startedControlCollector = OtlpTraceCollector.startAcceptingAllSpans();
             OtlpTraceCollector startedInvalidCollector = OtlpTraceCollector.startAcceptingAllSpans()) {
            controlCollector = startedControlCollector;
            invalidCollector = startedInvalidCollector;

            // when
            control = run(
                    fixture, "extension-configuration-control", controlCollector.otlpEndpoint(), controlSpanName, false, Map.of()
            );
            invalid = run(
                    fixture, "extension-configuration-invalid", invalidCollector.otlpEndpoint(), invalidSpanName, true, Map.of()
            );
        }

        // then
        control.assertCompletedSuccessfully();
        assertThat(control.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
        assertReadiness(control, "READY", "", controlSpanName);
        assertThat(controlCollector.receivedSpans())
                .extracting(OtlpTraceCollector.ReceivedSpan::name)
                .contains(controlSpanName);

        invalid.assertCompletedSuccessfully();
        assertThat(invalid.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
        assertReadiness(invalid, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", invalidSpanName);
        assertThat(invalidCollector.receivedSpans()).isEmpty();
    }

    @Test
    void invalid_max_span_age_environment_fails_bootstrap_without_platform_export()
            throws IOException, InterruptedException, URISyntaxException {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "extension-configuration-max-span-age-" + UUID.randomUUID();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = run(
                    fixture,
                    "extension-configuration-max-span-age",
                    collector.otlpEndpoint(),
                    spanName,
                    false,
                    Map.of("PLATFORM_TRACING_SPAN_TIMEOUT_MAX_SPAN_AGE", "0")
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(InvalidExtensionConfigurationProbe.MAIN_REACHED_MARKER);
            assertReadiness(result, "FAILED", "CONFIGURATION_INITIALIZATION_FAILED", spanName);
            assertThat(collector.receivedSpans()).isEmpty();
        }
    }

    private ChildProcessResult run(AgentTestFixture fixture,
                                   String label,
                                   String otlpEndpoint,
                                   String spanName,
                                   boolean invalidScanInterval,
                                   Map<String, String> additionalEnvironment)
            throws IOException, InterruptedException, URISyntaxException {
        return ControlledAgentChild.run(
                temporaryDirectory,
                label,
                command(fixture, otlpEndpoint, spanName, invalidScanInterval),
                additionalEnvironment
        );
    }

    private static List<String> command(AgentTestFixture fixture,
                                        String otlpEndpoint,
                                        String spanName,
                                        boolean invalidScanInterval) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendOtlpExporter(command, otlpEndpoint);
        ControlledAgentChild.appendSamplingAlwaysOn(command);
        ControlledAgentChild.appendServiceIdentity(command);
        if (invalidScanInterval) {
            ControlledAgentChild.appendInvalidScanInterval(command);
        }
        ControlledAgentChild.appendAgentLaunchWithApi(
                command,
                fixture,
                InvalidExtensionConfigurationProbe.class,
                false,
                List.of(spanName)
        );
        return command;
    }

    private static void assertReadiness(ChildProcessResult result,
                                        String lifecycle,
                                        String failureCode,
                                        String spanName) {
        String expectedLine = InvalidExtensionConfigurationProbe.RESULT_PREFIX
                + " lifecycle=" + lifecycle
                + " failureCode=" + failureCode
                + " spanName=" + spanName;

        assertThat(result.stdout()
                .lines()
                .filter(line -> line.startsWith(InvalidExtensionConfigurationProbe.RESULT_PREFIX)))
                .as("Child stdout must contain exactly one extension configuration probe line: %s", result.stdout())
                .containsExactly(expectedLine);
    }
}
