package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.distribution.probe.RequestIdPropagationProbe;

class RequestIdPropagationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void controlled_agent_copies_request_id_from_local_parent_onto_exported_child() throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();

        try (OtlpTraceCollector collector = OtlpTraceCollector.startAcceptingAllSpans()) {
            // when
            ChildProcessResult result = ControlledAgentChild.run(
                    temporaryDirectory,
                    "request-id-propagation-child",
                    command(fixture, collector.otlpEndpoint())
            );

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains(RequestIdPropagationProbe.OK_PREFIX);
            OtlpTraceCollector.ReceivedSpan child =
                    ExportedSpans.awaitByName(collector, RequestIdPropagationProbe.CHILD_SPAN_NAME);
            OtlpTraceCollector.ReceivedSpan parent =
                    ExportedSpans.awaitByName(collector, RequestIdPropagationProbe.PARENT_SPAN_NAME);
            assertThat(RequestIdPropagationProbe.REQUEST_ID_KEY)
                    .isEqualTo(PlatformAttributes.PLATFORM_REQUEST_ID);
            assertThat(child.stringAttributes())
                    .containsEntry(RequestIdPropagationProbe.REQUEST_ID_KEY, RequestIdPropagationProbe.REQUEST_ID);
            assertThat(parent.stringAttributes())
                    .containsEntry(RequestIdPropagationProbe.REQUEST_ID_KEY, RequestIdPropagationProbe.REQUEST_ID);
            assertThat(child.traceId()).isEqualTo(parent.traceId());
            assertThat(child.parentSpanId()).isEqualTo(parent.spanId());
        }
    }

    private static List<String> command(AgentTestFixture fixture, String otlpEndpoint) throws URISyntaxException {
        List<String> command = ControlledAgentChild.javaCommand(List.of());
        ControlledAgentChild.appendOtlpExporter(command, otlpEndpoint);
        ControlledAgentChild.appendSamplingAlwaysOn(command);
        ControlledAgentChild.appendServiceIdentity(command);
        ControlledAgentChild.appendAgentLaunchWithApi(
                command, fixture, RequestIdPropagationProbe.class, false, List.of()
        );
        return command;
    }
}
