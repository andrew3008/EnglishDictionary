package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.BaggageBridgeProbe;

class BaggageBridgeTest {

    private static final String CLIENT_KIND = "SPAN_KIND_CLIENT";
    private static final String URL_FULL = "url.full";
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path temporaryDirectory;

    @Test
    void application_baggage_reaches_agent_client_span_and_reserved_keys_stay_inside() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = ControlledAgentChild.run(
                    temporaryDirectory,
                    "baggage-bridge-child",
                    command(fixture, collector.otlpEndpoint())
            );

            // then
            result.assertCompletedSuccessfully();
            String header = baggageHeader(result.stdout());
            assertThat(header).contains("traffic_source=probe").doesNotContain("platform.");
            OtlpTraceCollector.ReceivedSpan client = awaitClient(collector);
            assertThat(client.stringAttributes())
                    .containsEntry("platform.correlation_id", BaggageBridgeProbe.CORRELATION_ID);
            assertThat(client.stringAttributes().keySet())
                    .noneMatch(key -> key.startsWith("baggage.platform."));
        }
    }

    private static List<String> command(AgentTestFixture fixture, String otlpEndpoint) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendOtlpExporter(command, otlpEndpoint);
        ControlledAgentChild.appendSamplingAlwaysOn(command);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunchWithApi(
                command, fixture, BaggageBridgeProbe.class, false, List.of()
        );
        return command;
    }

    private static String baggageHeader(String stdout) {
        String line = stdout.lines()
                .filter(value -> value.startsWith(BaggageBridgeProbe.OK_PREFIX))
                .findFirst()
                .orElseThrow(() -> new AssertionError("probe marker is missing: " + stdout));
        String payload = line.substring(BaggageBridgeProbe.OK_PREFIX.length()).trim();
        assertThat(payload).startsWith(BaggageBridgeProbe.BAGGAGE_HEADER_MARKER);
        return payload.substring(BaggageBridgeProbe.BAGGAGE_HEADER_MARKER.length());
    }

    private static OtlpTraceCollector.ReceivedSpan awaitClient(OtlpTraceCollector collector) {
        try {
            List<OtlpTraceCollector.ReceivedSpan> clients = await("export of baggage bridge client span")
                    .atMost(EXPORT_TIMEOUT)
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> collector.receivedSpans().stream()
                    .filter(span -> CLIENT_KIND.equals(span.kind()))
                    .filter(span -> {
                        String url = span.stringAttributes().get(URL_FULL);
                        return url != null && url.endsWith(BaggageBridgeProbe.PATH);
                    })
                                    .toList(),
                            spans -> spans.size() == 1);
            return clients.getFirst();
        } catch (ConditionTimeoutException timeout) {
            throw new AssertionError(
                    "Expected one CLIENT span for " + BaggageBridgeProbe.PATH + ", received " + collector.receivedSpans(),
                    timeout
            );
        }
    }
}
