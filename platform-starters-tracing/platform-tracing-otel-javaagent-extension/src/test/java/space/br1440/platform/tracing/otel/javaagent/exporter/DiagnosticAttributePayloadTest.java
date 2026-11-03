package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.configuration.OtelSdkDefaults;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.BoundaryTestSupport;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanLimits;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

@ResourceLock("io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter")
class DiagnosticAttributePayloadTest {

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("messages")
    void realSdkTruncationCannotPublishAnUnverifiedDiagnosticPrefix(String message, int limit,
                                                                   @Nullable String expectedAttribute,
                                                                   String expectedStatus) throws Exception {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        Attributes attributes = Attributes.of(AttributeKey.stringKey("exception.message"), message);
        ScrubbingRuntime runtime = BoundaryTestSupport.runtimeOf(
                List.of(BuiltInSpanAttributeScrubbingRules.DIAGNOSTIC_TEXT.create()), List.of(), null, limit);
        SpanExporter exporter = new SafeSpanExporter(new OtlpRequestSizeLimitingSpanExporter(
                OtlpJsonLoggingSpanExporter.create(), Math.toIntExact(OtelSdkDefaults.MAX_OTLP_REQUEST_SIZE_BYTES)),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()), runtime);
        String traceId;
        String spanId;

        // when
        try (runtime;
             ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .setSpanLimits(SpanLimits.builder().setMaxAttributeValueLength(limit).build())
                     .addSpanProcessor(new ScrubbingSpanProcessor(runtime))
                     .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                     .build()) {
            assertThat(provider.getSpanLimits().getMaxAttributeValueLength()).isEqualTo(limit);
            Span span = provider.get("diagnostic-boundary").spanBuilder("diagnostic-completeness")
                    .addLink(ExporterTestSpans.LINK_TARGET, attributes).startSpan();
            traceId = span.getSpanContext().getTraceId();
            spanId = span.getSpanContext().getSpanId();
            span.setAllAttributes(attributes);
            span.addEvent("exception", attributes.toBuilder()
                    .put("exception.type", IllegalStateException.class.getName()).build());
            span.setStatus(StatusCode.ERROR, message);
            span.end();
        }

        // then
        assertThat(rendered).hasSize(1);
        JsonNode span = new ObjectMapper().readTree(rendered.getFirst()).path("scopeSpans").path(0).path("spans").path(0);
        assertThat(span.path("traceId").asText()).isEqualTo(traceId);
        assertThat(span.path("spanId").asText()).isEqualTo(spanId);
        assertThat(span.path("status").path("code").asInt()).isEqualTo(2);
        assertThat(span.path("status").path("message").asText()).isEqualTo(expectedStatus);
        assertThat(span.path("events").size()).isEqualTo(1);
        assertThat(span.path("links").size()).isEqualTo(1);
        for (JsonNode owner : List.of(span, span.path("events").path(0), span.path("links").path(0))) {
            assertThat(attribute(owner, "exception.message")).isEqualTo(expectedAttribute);
        }
        assertThat(attribute(span.path("events").path(0), "exception.type"))
                .isEqualTo(IllegalStateException.class.getName().substring(0,
                        Math.min(limit, IllegalStateException.class.getName().length())));
    }

    private static Stream<Arguments> messages() {
        String url = "http://h/?a=x";
        return Stream.of(
                Arguments.of("x".repeat(63), 64, "x".repeat(63), "x".repeat(63)),
                Arguments.of("x".repeat(64), 64, null, "x".repeat(64)),
                Arguments.of("x".repeat(65), 64, null, "x".repeat(65)),
                Arguments.of("x".repeat(63) + " email=boundary-canary@example.com", 64, null, "<redacted>"),
                Arguments.of("x".repeat(63) + "x", 64, null, "x".repeat(63) + "x"),
                Arguments.of("password=boundary-canary", 64, "<redacted>", "<redacted>"),
                Arguments.of("pwd=s", 9, null, "<redacted>"),
                Arguments.of(url, url.length() + 1, null, "http://h/?a=***"),
                Arguments.of("Connection refused", Integer.MAX_VALUE, "Connection refused", "Connection refused"),
                Arguments.of("x".repeat(4097), Integer.MAX_VALUE, null, ""));
    }

    @Nullable
    private static String attribute(JsonNode owner, String key) {
        for (JsonNode attribute : owner.path("attributes")) {
            if (attribute.path("key").asText().equals(key)) {
                return attribute.path("value").path("stringValue").asText();
            }
        }
        return null;
    }
}
