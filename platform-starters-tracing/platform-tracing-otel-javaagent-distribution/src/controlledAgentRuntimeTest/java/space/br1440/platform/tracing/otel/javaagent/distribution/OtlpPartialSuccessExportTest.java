package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.OtlpPartialSuccessProbe;

class OtlpPartialSuccessExportTest {

    private static final String OK_PREFIX = "OTLP_PARTIAL_SUCCESS_PROBE_OK";
    private static final Pattern LINE_PATTERN = Pattern.compile(
            OK_PREFIX + " partialSuccessBatchesDelta=(\\d+) rejectedSpansDelta=(\\d+) exportedSpansDelta=(\\d+)"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_reports_otlp_partial_success_via_export_observation() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startRejectingOneSpanPerBatch()) {
            // when
            ChildProcessResult result = ControlledAgentChild.run(
                    temporaryDirectory,
                    "otlp-child",
                    command(fixture, collector.otlpEndpoint())
            );

            // then
            result.assertCompletedSuccessfully();
            assertPartialSuccessObserved(result);
            assertPartialSuccessRequests(collector);
        }
    }

    private static List<String> command(AgentTestFixture fixture, String otlpEndpoint) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendOtlpExporter(command, otlpEndpoint);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunchWithApi(
                command, fixture, OtlpPartialSuccessProbe.class, false, List.of());
        return command;
    }

    private static void assertPartialSuccessObserved(ChildProcessResult result) {
        List<String> matchingLines = result.stdout()
                .lines()
                .filter(line -> line.startsWith(OK_PREFIX))
                .toList();
        assertThat(matchingLines)
                .as("Child stdout must contain exactly one OTLP partial-success probe line: %s", result.stdout())
                .hasSize(1);

        Matcher matcher = LINE_PATTERN.matcher(matchingLines.getFirst());
        assertThat(matcher.matches())
                .as("Probe line does not match the expected shape: %s", matchingLines.getFirst())
                .isTrue();

        long partialSuccessBatchesDelta = Long.parseLong(matcher.group(1));
        long rejectedSpansDelta = Long.parseLong(matcher.group(2));
        long exportedSpansDelta = Long.parseLong(matcher.group(3));
        assertThat(partialSuccessBatchesDelta)
                .as("partial_success_batches must increase after the baseline snapshot")
                .isGreaterThanOrEqualTo(1L);
        assertThat(rejectedSpansDelta)
                .as("rejected_spans must increase after the stub collector's rejection")
                .isGreaterThanOrEqualTo(1L);
        assertThat(exportedSpansDelta)
                .as("exported_spans must increase for the accepted part of the partial batch")
                .isGreaterThanOrEqualTo(1L);
    }

    private static void assertPartialSuccessRequests(OtlpTraceCollector collector) {
        List<List<OtlpTraceCollector.ReceivedSpan>> batches = collector.receivedSpanBatches();
        assertThat(batches)
                .as("At least one OTLP request must contain accepted and rejected probe spans: %s", batches)
                .anySatisfy(batch -> assertThat(batch)
                        .filteredOn(span -> OtlpPartialSuccessProbe.PROBE_SPAN_NAME.equals(span.name()))
                        .hasSizeGreaterThanOrEqualTo(2));

        List<OtlpTraceCollector.ReceivedSpan> probeSpans = batches.stream()
                .flatMap(List::stream)
                .filter(span -> OtlpPartialSuccessProbe.PROBE_SPAN_NAME.equals(span.name()))
                .toList();
        assertThat(probeSpans)
                .as("Partial-success requests must not retry probe spans: %s", probeSpans)
                .extracting(OtlpTraceCollector.ReceivedSpan::spanId)
                .doesNotHaveDuplicates();
    }
}
