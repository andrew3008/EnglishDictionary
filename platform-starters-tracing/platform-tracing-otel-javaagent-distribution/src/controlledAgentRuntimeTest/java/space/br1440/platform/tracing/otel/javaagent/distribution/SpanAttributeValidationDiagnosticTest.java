package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.SpanAttributeValidationDiagnosticProbe;

class SpanAttributeValidationDiagnosticTest {

    private static final String MISSING_ATTRIBUTE_KEY = "platform.validation.missing";
    private static final String MISSING_ATTRIBUTE_NAME = "platform.trace.result";
    private static final String VALIDATION_ERROR_EVENT = "\"event_code\":\"PTA-VALID-003\"";
    private static final String ERROR_LEVEL = "\"level\":\"ERROR\"";

    @TempDir
    Path temporaryDirectory;

    @Test
    void errorLevelValidationStaysDiagnosticForInvalidSpan() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = ControlledAgentChild.run(
                    temporaryDirectory,
                    "validation-diagnostic-child",
                    command(fixture, collector.otlpEndpoint()));

            // then
            result.assertCompletedSuccessfully();

            ProbeReport report = ProbeReport.parse(result.stdout());
            assertThat(report.level()).isEqualTo("ERROR");
            assertThat(report.violationsAfter())
                    .as("validationViolationsTotal must grow after the invalid-span stimulus")
                    .isGreaterThan(report.violationsBefore());

            OtlpTraceCollector.ReceivedSpan exported =
                    ExportedSpans.awaitBySpanId(collector, report.spanId());
            assertThat(exported.traceId()).isEqualTo(report.traceId());
            assertThat(exported.name()).isEqualTo(SpanAttributeValidationDiagnosticProbe.INVALID_SPAN_NAME);
            assertThat(exported.stringAttributes())
                    .as("Exported invalid span must carry the validation marker: %s", exported)
                    .containsKey(MISSING_ATTRIBUTE_KEY);
            assertThat(exported.stringAttributes().get(MISSING_ATTRIBUTE_KEY))
                    .as("Marker must name the producer-owned attribute that was missing")
                    .contains(MISSING_ATTRIBUTE_NAME);

            assertThat(errorDiagnostics(result.stderr()))
                    .as("Agent ERROR level must emit exactly one PTA-VALID-003 diagnostic: %s", result.stderr())
                    .hasSize(1);
        }
    }

    private static List<String> command(AgentTestFixture fixture, String otlpEndpoint)
            throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendOtlpExporter(command, otlpEndpoint);
        ControlledAgentChild.appendSamplingAlwaysOn(command);
        ControlledAgentChild.appendErrorSpanValidation(command);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunchWithApi(
                command, fixture, SpanAttributeValidationDiagnosticProbe.class, false, List.of());
        return command;
    }

    private static List<String> errorDiagnostics(String stderr) {
        return stderr.lines()
                .filter(line -> line.contains(VALIDATION_ERROR_EVENT))
                .filter(line -> line.contains(ERROR_LEVEL))
                .toList();
    }

    private record ProbeReport(
            String level,
            long violationsBefore,
            long violationsAfter,
            String traceId,
            String spanId
    ) {

        private static final String OK_PREFIX = "SPAN_ATTRIBUTE_VALIDATION_DIAGNOSTIC_OK";
        private static final Pattern LINE = Pattern.compile(
                OK_PREFIX + " level=(\\w+) violationsBefore=(\\d+) violationsAfter=(\\d+) "
                        + "traceId=([0-9a-f]{32}) spanId=([0-9a-f]{16})");

        static ProbeReport parse(String stdout) {
            List<String> lines = stdout.lines().filter(line -> line.startsWith(OK_PREFIX)).toList();
            assertThat(lines)
                    .as("Child stdout must contain exactly one probe report line: %s", stdout)
                    .hasSize(1);

            Matcher matcher = LINE.matcher(lines.getFirst());
            assertThat(matcher.matches())
                    .as("Probe report line has unexpected shape: %s", lines.getFirst())
                    .isTrue();

            return new ProbeReport(
                    matcher.group(1),
                    Long.parseLong(matcher.group(2)),
                    Long.parseLong(matcher.group(3)),
                    matcher.group(4),
                    matcher.group(5));
        }
    }
}
