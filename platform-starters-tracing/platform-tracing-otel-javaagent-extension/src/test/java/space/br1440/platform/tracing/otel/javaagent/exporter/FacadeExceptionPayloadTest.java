package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

@ResourceLock("io.opentelemetry.exporter.logging.otlp.OtlpJsonLoggingSpanExporter")
class FacadeExceptionPayloadTest {

    @Test
    void defaultRecorderPublishesSafeMessageWithoutAgentSanitization() throws Exception {
        // given
        List<String> rendered = new CopyOnWriteArrayList<>();
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();

        // when
        try (ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .addSpanProcessor(SimpleSpanProcessor.create(OtlpJsonLoggingSpanExporter.create()))
                     .build()) {
            Span span = provider.get("facade-policy").spanBuilder("default-exception-policy").startSpan();
            recorder.record(span, new IllegalStateException("Connection refused"));
            span.end();
        }

        // then
        assertThat(rendered).hasSize(1);
        JsonNode span = new ObjectMapper().readTree(rendered.getFirst()).path("scopeSpans").path(0).path("spans").path(0);
        assertThat(span.path("status").path("message").asText()).isEqualTo("Connection refused");
        JsonNode event = span.path("events").path(0);
        assertThat(attribute(event, "exception.message")).isEqualTo("Connection refused");
        assertThat(attribute(event, "exception.stacktrace")).isNull();
    }

    @Test
    void facadeSanitizesWholeRootAndCauseMessagesBeforeTruncationAndSerialization() throws Exception {
        // given
        IllegalArgumentException cause = new IllegalArgumentException("Authorization: Bearer cause-credential-canary");
        cause.setStackTrace(new StackTraceElement[0]);
        IllegalStateException exception = new IllegalStateException(
                "x".repeat(510) + " password=root-credential-canary", cause);
        exception.setStackTrace(new StackTraceElement[0]);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        List<String> rendered = new CopyOnWriteArrayList<>();

        // when
        try (ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .addSpanProcessor(SimpleSpanProcessor.create(OtlpJsonLoggingSpanExporter.create()))
                     .build()) {
            Span span = provider.get("facade-policy").spanBuilder("sensitive-exception-policy").startSpan();
            recorder.record(span, exception);
            span.end();
        }

        // then
        assertThat(rendered).hasSize(1);
        assertThat(rendered.getFirst()).doesNotContain("root-credential-canary", "cause-credential-canary", "xxx");
        JsonNode span = new ObjectMapper().readTree(rendered.getFirst()).path("scopeSpans").path(0).path("spans").path(0);
        assertThat(span.path("status").path("message").asText()).isEqualTo("<redacted>");
        JsonNode event = span.path("events").path(0);
        assertThat(attribute(event, "exception.message")).isEqualTo("<redacted>");
        assertThat(attribute(event, "exception.stacktrace"))
                .contains("java.lang.IllegalStateException: <redacted>",
                        "Caused by: java.lang.IllegalArgumentException: <redacted>");
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void otlpPayloadRespectsFacadeTextPolicy(boolean includeMessage, boolean includeStack) throws Exception {
        // given
        IllegalArgumentException cause = new IllegalArgumentException(
                "cause https://user:cause-secret@service/?token=cause-query-secret");
        cause.setStackTrace(new StackTraceElement[0]);
        IllegalStateException exception = new IllegalStateException(
                "outer https://user:outer-secret@service/?token=outer-query-secret", cause);
        exception.setStackTrace(new StackTraceElement[0]);
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(includeMessage, includeStack));
        List<String> rendered = new CopyOnWriteArrayList<>();

        // when
        try (ExporterLogCapture ignored = new ExporterLogCapture(OtlpJsonLoggingSpanExporter.class, rendered);
             SdkTracerProvider provider = SdkTracerProvider.builder()
                     .addSpanProcessor(SimpleSpanProcessor.create(OtlpJsonLoggingSpanExporter.create()))
                     .build()) {
            Span span = provider.get("facade-policy").spanBuilder("exception-policy").startSpan();
            recorder.record(span, exception);
            span.end();
        }

        // then
        assertThat(rendered).hasSize(1);
        String payload = rendered.getFirst();
        assertThat(payload)
                .doesNotContain("outer-secret", "cause-secret", "outer-query-secret", "cause-query-secret");
        JsonNode span = new ObjectMapper().readTree(payload).path("scopeSpans").path(0).path("spans").path(0);
        assertThat(span.path("name").asText()).isEqualTo("exception-policy");
        assertThat(span.path("status").path("code").asInt()).isEqualTo(2);
        String expectedMessage = "outer https://***@service/?token=***";
        assertThat(span.path("status").path("message").asText())
                .isEqualTo(includeMessage ? expectedMessage : "");
        assertThat(span.path("events").size()).isEqualTo(1);
        JsonNode event = span.path("events").path(0);
        assertThat(event.path("name").asText()).isEqualTo("exception");
        assertThat(attribute(event, "exception.type")).isEqualTo(IllegalStateException.class.getName());
        assertThat(attribute(event, "exception.message")).isEqualTo(includeMessage ? expectedMessage : null);
        String stack = attribute(event, "exception.stacktrace");
        if (includeStack) {
            assertThat(stack).contains("java.lang.IllegalStateException", "Caused by: java.lang.IllegalArgumentException");
            if (includeMessage) {
                assertThat(stack).contains(expectedMessage, "cause https://***@service/?token=***");
            } else {
                assertThat(stack).doesNotContain("outer https://", "cause https://");
            }
        } else {
            assertThat(stack).isNull();
        }
    }

    private static String attribute(JsonNode event, String key) {
        for (JsonNode attribute : event.path("attributes")) {
            if (attribute.path("key").asText().equals(key)) {
                return attribute.path("value").path("stringValue").asText();
            }
        }
        return null;
    }
}
