package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.distribution.probe.InvalidExtensionConfigurationProbe;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okhttp3.Protocol;
import okio.Buffer;

class OtlpProtocolExportTest {

    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlled_agent_uses_http_by_default_and_supports_explicit_grpc(boolean grpc) throws Exception {
        // given
        AgentTestFixture fixture = AgentTestFixture.load();
        String spanName = "packaged-protocol-" + (grpc ? "grpc" : "default-http");
        try (MockWebServer collector = new MockWebServer()) {
            collector.setProtocols(List.of(grpc ? Protocol.H2_PRIOR_KNOWLEDGE : Protocol.HTTP_1_1));
            collector.setDispatcher(new Dispatcher() {
                @Override
                public @NotNull MockResponse dispatch(@NotNull RecordedRequest request) {
                    if (!grpc) {
                        return new MockResponse.Builder()
                                .addHeader("Content-Type", "application/x-protobuf")
                                .build();
                    }

                    try (Buffer frame = new Buffer()) {
                        frame.write(new byte[5]);
                        return new MockResponse.Builder()
                                .addHeader("Content-Type", "application/grpc")
                                .body(frame)
                                .trailers(Headers.of("grpc-status", "0"))
                                .build();
                    }
                }
            });
            collector.start();
            String endpoint = collector.url("/").toString();
            List<String> command = ControlledAgentChild.javaCommand(List.of());
            ControlledAgentChild.appendOtlpExporter(command, endpoint);
            command.remove("-Dotel.exporter.otlp.traces.protocol=http/protobuf");
            if (grpc) {
                command.add("-Dotel.exporter.otlp.traces.protocol=grpc");
            }
            ControlledAgentChild.appendSamplingAlwaysOn(command);
            ControlledAgentChild.appendServiceIdentity(command);
            ControlledAgentChild.appendAgentLaunchWithApi(
                    command, fixture, InvalidExtensionConfigurationProbe.class, false, List.of(spanName));

            // when
            ChildProcessResult result = ControlledAgentChild.run(temporaryDirectory, spanName, command);
            RecordedRequest request = collector.takeRequest(5, TimeUnit.SECONDS);

            // then
            result.assertCompletedSuccessfully();
            assertThat(result.stdout()).contains("lifecycle=READY");
            assertThat(request).isNotNull();
            assertThat(request.getFailure()).isNull();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getVersion()).isEqualTo(grpc ? "HTTP/2" : "HTTP/1.1");
            assertThat(request.getUrl().host()).isEqualTo(collector.url("/").host());
            assertThat(request.getUrl().port()).isEqualTo(collector.getPort());
            assertThat(request.getTarget()).isEqualTo(grpc
                    ? "/opentelemetry.proto.collector.trace.v1.TraceService/Export" : "/v1/traces");
            assertThat(request.getHeaders().get("Content-Type"))
                    .isEqualTo(grpc ? "application/grpc" : "application/x-protobuf");
            assertThat(request.getBody()).isNotNull();
            byte[] body = request.getBody().toByteArray();
            byte[] protobuf = grpc ? Arrays.copyOfRange(body, 5, body.length) : body;
            ExportTraceServiceRequest exported = ExportTraceServiceRequest.parseFrom(protobuf);
            assertThat(exported.getResourceSpansList().stream()
                    .map(ResourceSpans::getScopeSpansList)
                    .flatMap(List::stream)
                    .map(ScopeSpans::getSpansList)
                    .flatMap(List::stream)
                    .map(Span::getName))
                    .contains(spanName);
        }
    }
}
