package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.OversizedSpanIsolationProbe;

class OversizedSpanIsolationExportTest {

    private static final int MAX_REQUEST_BYTES = 15 * 1024 * 1024;

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlledAgentIsolatesOversizedSpanAndLimitsShapeWithMetricsDisabled() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = ControlledAgentChild.run(
                    temporaryDirectory,
                    "oversized-span-child",
                    command(fixture, collector.otlpEndpoint())
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout().lines()
                    .filter(line -> line.startsWith("OVERSIZED_SPAN_ISOLATION_PROBE_OK")))
                    .containsExactly("OVERSIZED_SPAN_ISOLATION_PROBE_OK oversized=1 shape=1 transportDropped=0 failures=0");
            assertThat(collector.receivedRequestSizes()).isNotEmpty()
                    .allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(MAX_REQUEST_BYTES));
            assertThat(collector.receivedSpans())
                    .extracting(OtlpTraceCollector.ReceivedSpan::name)
                    .contains(OversizedSpanIsolationProbe.LEFT_SPAN, OversizedSpanIsolationProbe.RIGHT_SPAN)
                    .doesNotContain(OversizedSpanIsolationProbe.OVERSIZED_SPAN);

            List<OtlpTraceCollector.ReceivedSpan> probeSpans = collector.receivedSpans().stream()
                    .filter(span -> span.name().startsWith(OversizedSpanIsolationProbe.NAME_PREFIX))
                    .toList();
            assertThat(probeSpans).hasSize(7)
                    .extracting(OtlpTraceCollector.ReceivedSpan::spanId).doesNotHaveDuplicates();
            assertThat(probeSpans).filteredOn(span -> span.name().equals(OversizedSpanIsolationProbe.TRUNCATED_NAME))
                    .singleElement().satisfies(span -> {
                        assertThat(span.statusCode()).isEqualTo(2);
                        assertThat(span.statusDescription()).isEqualTo("d".repeat(1024));
                    });
            assertThat(probeSpans).filteredOn(span -> span.name().startsWith(OversizedSpanIsolationProbe.FOLLOW_UP_PREFIX))
                    .hasSize(4);
        }
    }

    private static List<String> command(AgentTestFixture fixture, String endpoint) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of("-Xmx256m"));
        ControlledAgentChild.appendOtlpExporter(command, endpoint);
        ControlledAgentChild.appendSamplingAlwaysOn(command);
        ControlledAgentChild.appendServiceIdentity(command);
        command.add("-Dplatform.tracing.metrics.enabled=false");
        command.add("-Dotel.bsp.max.export.batch.size=4");
        command.add("-Dotel.bsp.schedule.delay=5000");
        ControlledAgentChild.appendAgentLaunchWithApi(
                command, fixture, OversizedSpanIsolationProbe.class, false, List.of());
        return command;
    }
}
