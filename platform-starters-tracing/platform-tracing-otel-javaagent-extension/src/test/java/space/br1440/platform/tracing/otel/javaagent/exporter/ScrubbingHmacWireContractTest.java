package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.BoundaryTestSupport;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Headers;
import okhttp3.Protocol;
import okio.Buffer;

class ScrubbingHmacWireContractTest {

    private static final String HMAC_KEY = "wire-contract-hmac-key";
    private static final String CANARY = "raw-identity-wire-canary";
    private static final String SECOND_CANARY = "second-identity-wire-canary";
    private static final String PASSWORD = "password-wire-canary";
    private static final AttributeKey<String> ID = AttributeKey.stringKey("private.id");
    private static final AttributeKey<List<String>> IDS = AttributeKey.stringArrayKey("private.ids");
    private static final SpanContext LINK_CONTEXT = SpanContext.create(
            "0123456789abcdef0123456789abcdef", "000000000000abcd",
            TraceFlags.getSampled(), TraceState.getDefault()
    );

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void hashIsAppliedOnceAcrossSurfacesTransportRetriesAndOriginalInputExports(boolean grpc, boolean retry)
            throws Exception {
        // given
        KeyBasedHashRule hashRule = new KeyBasedHashRule();
        String expected = expectedHmac(CANARY);
        String secondExpected = expectedHmac(SECOND_CANARY);
        assertThat(LINK_CONTEXT.isValid()).isTrue();
        try (ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(List.of(
                hashRule, BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create()), HMAC_KEY);
             MockWebServer collector = new MockWebServer()) {
            collector.setProtocols(List.of(grpc ? Protocol.H2_PRIOR_KNOWLEDGE : Protocol.HTTP_1_1));
            if (retry) {
                collector.enqueue(response(grpc, true));
            }
            collector.enqueue(response(grpc, false));
            collector.enqueue(response(grpc, false));
            collector.start();
            SpanData original = recordSpan(runtime);
            assertThat(hashRule.evaluations).hasValue(2);
            assertThat(original.getAttributes().get(ID)).isEqualTo(expected);
            assertThat(original.getEvents()).hasSize(1);
            assertThat(original.getLinks()).hasSize(1);
            assertThat(original.getEvents().getFirst().getAttributes().get(ID)).isEqualTo(CANARY);
            assertThat(original.getLinks().getFirst().getAttributes().get(ID)).isEqualTo(CANARY);

            try (SpanExporter exporter = safeExporter(transport(collector, grpc), runtime)) {
                // when
                assertThat(exporter.export(List.of(original)).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();

                // then
                assertThat(hashRule.evaluations).hasValue(6);
                int firstAttemptCount = retry ? 2 : 1;
                ExportTraceServiceRequest first = null;
                for (int attempt = 0; attempt < firstAttemptCount; attempt++) {
                    ExportTraceServiceRequest actual = decode(collector.takeRequest(5, TimeUnit.SECONDS), grpc);
                    assertPayload(actual, original, expected, secondExpected);
                    if (first == null) {
                        first = actual;
                    } else {
                        assertThat(actual).isEqualTo(first);
                    }
                }

                // when
                assertThat(exporter.export(List.of(original)).join(10, TimeUnit.SECONDS).isSuccess()).isTrue();

                // then
                ExportTraceServiceRequest repeated = decode(collector.takeRequest(5, TimeUnit.SECONDS), grpc);
                assertPayload(repeated, original, expected, secondExpected);
                assertThat(repeated).isEqualTo(first);
                assertThat(hashRule.evaluations).hasValue(10);
                assertThat(collector.getRequestCount()).isEqualTo(firstAttemptCount + 1);
                assertThat(runtime.boundaryMetrics().inputSpans()).isEqualTo(2L);
                assertThat(original.getEvents().getFirst().getAttributes().get(ID)).isEqualTo(CANARY);
                assertThat(original.getLinks().getFirst().getAttributes().get(IDS))
                        .containsExactly(CANARY, SECOND_CANARY);
            }
        }
    }

    private static SpanData recordSpan(ScrubbingRuntime runtime) {
        InMemorySpanExporter capture = InMemorySpanExporter.create();
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                .addSpanProcessor(SimpleSpanProcessor.create(capture))
                .build()) {
            Attributes attributes = Attributes.builder()
                    .put(ID, CANARY)
                    .put(IDS, List.of(CANARY, SECOND_CANARY))
                    .put("password", PASSWORD)
                    .put("safe.count", 41L)
                    .put("safe.label", "checkout")
                    .build();
            Span span = provider.get("scrubbing-wire-contract")
                    .spanBuilder("checkout")
                    .setAllAttributes(attributes)
                    .addLink(LINK_CONTEXT, attributes)
                    .startSpan();
            span.addEvent("payment", attributes, 123L, TimeUnit.NANOSECONDS);
            span.end();
            assertThat(provider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(capture.getFinishedSpanItems()).hasSize(1);
            return capture.getFinishedSpanItems().getFirst();
        }
    }

    private static SpanExporter transport(MockWebServer collector, boolean grpc) {
        RetryPolicy retry = RetryPolicy.builder()
                .setMaxAttempts(2)
                .setInitialBackoff(Duration.ofMillis(1))
                .setMaxBackoff(Duration.ofMillis(1))
                .build();
        return grpc
                ? OtlpGrpcSpanExporter.builder()
                        .setEndpoint(collector.url("/").toString())
                        .setTimeout(Duration.ofSeconds(5))
                        .setRetryPolicy(retry)
                        .build()
                : OtlpHttpSpanExporter.builder()
                        .setEndpoint(collector.url("/v1/traces").toString())
                        .setTimeout(Duration.ofSeconds(5))
                        .setRetryPolicy(retry)
                        .build();
    }

    private static SpanExporter safeExporter(SpanExporter delegate, ScrubbingRuntime runtime) {
        return new SafeSpanExporter(
                new OtlpRequestSizeLimitingSpanExporter(delegate, Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);
    }

    private static MockResponse response(boolean grpc, boolean retryableFailure) {
        if (!grpc) {
            return new MockResponse.Builder()
                    .code(retryableFailure ? 503 : 200)
                    .addHeader("Content-Type", "application/x-protobuf")
                    .build();
        }
        try (Buffer frame = new Buffer()) {
            frame.write(new byte[5]);
            return new MockResponse.Builder()
                    .addHeader("Content-Type", "application/grpc")
                    .body(frame)
                    .trailers(Headers.of("grpc-status", retryableFailure ? "14" : "0"))
                    .build();
        }
    }

    private static ExportTraceServiceRequest decode(RecordedRequest request, boolean grpc) throws Exception {
        assertThat(request).isNotNull();
        assertThat(request.getFailure()).isNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getVersion()).isEqualTo(grpc ? "HTTP/2" : "HTTP/1.1");
        assertThat(request.getTarget()).isEqualTo(grpc
                ? "/opentelemetry.proto.collector.trace.v1.TraceService/Export" : "/v1/traces");
        assertThat(request.getHeaders().get("Content-Type"))
                .isEqualTo(grpc ? "application/grpc" : "application/x-protobuf");
        assertThat(request.getBody()).isNotNull();
        byte[] body = request.getBody().toByteArray();
        if (grpc) {
            assertThat(body.length).isGreaterThanOrEqualTo(5);
            assertThat(body[0]).isZero();
            assertThat(ByteBuffer.wrap(body, 1, 4).getInt()).isEqualTo(body.length - 5);
            body = Arrays.copyOfRange(body, 5, body.length);
        }
        ExportTraceServiceRequest payload = ExportTraceServiceRequest.parseFrom(body);
        assertThat(payload.toString()).doesNotContain(CANARY, SECOND_CANARY, PASSWORD);
        return payload;
    }

    private static void assertPayload(ExportTraceServiceRequest payload, SpanData original,
                                     String expected, String secondExpected) {
        List<io.opentelemetry.proto.trace.v1.Span> spans = payload.getResourceSpansList().stream()
                .flatMap(resource -> resource.getScopeSpansList().stream())
                .flatMap(scope -> scope.getSpansList().stream())
                .toList();
        assertThat(spans).hasSize(1);
        io.opentelemetry.proto.trace.v1.Span span = spans.getFirst();
        assertThat(span.getName()).isEqualTo("checkout");
        assertThat(HexFormat.of().formatHex(span.getTraceId().toByteArray())).isEqualTo(original.getTraceId());
        assertThat(HexFormat.of().formatHex(span.getSpanId().toByteArray())).isEqualTo(original.getSpanId());
        assertAttributes(span.getAttributesList(), expected, secondExpected);
        assertThat(attribute(span.getAttributesList(), "password").getStringValue()).isEmpty();
        assertThat(span.getEventsCount()).isEqualTo(1);
        assertThat(span.getEvents(0).getName()).isEqualTo("payment");
        assertThat(span.getEvents(0).getTimeUnixNano()).isEqualTo(123L);
        assertThat(span.getEvents(0).getDroppedAttributesCount()).isEqualTo(1);
        assertAttributes(span.getEvents(0).getAttributesList(), expected, secondExpected);
        assertThat(span.getEvents(0).getAttributesList()).extracting(KeyValue::getKey).doesNotContain("password");
        assertThat(span.getLinksCount()).isEqualTo(1);
        assertThat(span.getLinks(0).getDroppedAttributesCount()).isEqualTo(1);
        assertThat(HexFormat.of().formatHex(span.getLinks(0).getSpanId().toByteArray()))
                .isEqualTo(LINK_CONTEXT.getSpanId());
        assertAttributes(span.getLinks(0).getAttributesList(), expected, secondExpected);
        assertThat(span.getLinks(0).getAttributesList()).extracting(KeyValue::getKey).doesNotContain("password");
    }

    private static void assertAttributes(List<KeyValue> attributes, String expected, String secondExpected) {
        AnyValue scalar = attribute(attributes, ID.getKey());
        assertThat(scalar.getValueCase()).isEqualTo(AnyValue.ValueCase.STRING_VALUE);
        assertThat(scalar.getStringValue()).isEqualTo(expected);
        AnyValue array = attribute(attributes, IDS.getKey());
        assertThat(array.getValueCase()).isEqualTo(AnyValue.ValueCase.ARRAY_VALUE);
        assertThat(array.getArrayValue().getValuesList())
                .allSatisfy(value -> assertThat(value.getValueCase()).isEqualTo(AnyValue.ValueCase.STRING_VALUE))
                .extracting(AnyValue::getStringValue).containsExactly(expected, secondExpected);
        assertThat(attribute(attributes, "safe.count").getValueCase()).isEqualTo(AnyValue.ValueCase.INT_VALUE);
        assertThat(attribute(attributes, "safe.count").getIntValue()).isEqualTo(41L);
        assertThat(attribute(attributes, "safe.label").getStringValue()).isEqualTo("checkout");
    }

    private static AnyValue attribute(List<KeyValue> attributes, String key) {
        return attributes.stream().filter(attribute -> attribute.getKey().equals(key))
                .map(KeyValue::getValue).findFirst().orElseThrow();
    }

    private static String expectedHmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class KeyBasedHashRule implements SpanAttributeScrubbingRule {

        private final AtomicInteger evaluations = new AtomicInteger();

        @Nonnull
        @Override
        public String name() {
            return "wire-key-hash";
        }

        @Override
        public boolean isExcluded(@Nonnull String key) {
            return !ID.getKey().equals(key) && !IDS.getKey().equals(key);
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            evaluations.incrementAndGet();
            return ScrubbingDecision.hash(name());
        }
    }
}
