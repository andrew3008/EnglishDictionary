package space.br1440.platform.tracing.otel.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class ExceptionRecorderTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void record_secureDefault_redactsSensitiveMessageAndOmitsStacktrace() {
        // given
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();

        // when
        runInSpan(() -> recorder.record(new IllegalStateException("SELECT * FROM users WHERE ssn='123'")));

        // then
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("java.lang.IllegalStateException");
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("failure");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo("<redacted>");

        EventData event = span.getEvents().getFirst();
        assertThat(event.getName()).isEqualTo("exception");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE)).isEqualTo("java.lang.IllegalStateException");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("<redacted>");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void record_secureDefault_preservesOrdinaryMessageAndDescription() {
        // given
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();

        // when
        runInSpan(() -> recorder.record(new IllegalArgumentException("boom")));

        // then
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getDescription()).isEqualTo("boom");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("boom");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void record_masksUrlTextInEventStatusAndCauseHeaders() {
        // given
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        IllegalStateException cause = new IllegalStateException("Cause https://host/?opaque-canary");
        cause.setStackTrace(new StackTraceElement[0]);
        IllegalStateException failure = new IllegalStateException(
                "Failed HTTPS://user:password-canary@host/?token=query-canary#fragment-canary", cause);
        failure.setStackTrace(new StackTraceElement[0]);

        // when
        runInSpan(() -> recorder.record(failure));

        // then
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        String expected = "Failed HTTPS://***@host/?token=***#***";
        EventData event = span.getEvents().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo(expected);
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo(expected);
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                .contains(expected, "Cause https://host/?***")
                .doesNotContain("password-canary", "query-canary", "fragment-canary", "opaque-canary");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "https://h/#,https://example.com/SENTINEL | https://h/#***",
            "https://h/(#SENTINEL)) | https://h/(#***"
    })
    void record_masksOpaqueUrlFragmentsInEventStatusAndStack(String message, String expected) {
        // given
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        IllegalStateException failure = new IllegalStateException(message);
        failure.setStackTrace(new StackTraceElement[0]);

        // when
        runInSpan(() -> recorder.record(failure));

        // then
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        EventData event = span.getEvents().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEqualTo(expected);
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE)).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo(expected);
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                .contains(expected)
                .doesNotContain("SENTINEL");
    }

    @Test
    void record_dropsAmbiguousUrlTextAndKeepsExceptionIdentity() {
        // given
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        IllegalStateException failure = new IllegalStateException("Failed https://user%3Asecret-canary%40host/");
        failure.setStackTrace(new StackTraceElement[0]);

        // when
        runInSpan(() -> recorder.record(failure));

        // then
        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getName()).isEqualTo("op");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getStatus().getDescription()).isEmpty();
        assertThat(span.getEvents()).hasSize(1);
        EventData event = span.getEvents().getFirst();
        assertThat(event.getName()).isEqualTo("exception");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE)).isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                .isEqualTo("java.lang.IllegalStateException\n");
    }

    @Test
    void record_withoutActiveSpan_andNull_doNotThrowAndDoNotCreateSpan() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        assertThatCode(() -> recorder.record(new RuntimeException("no span"))).doesNotThrowAnyException();
        runInSpan(() -> recorder.record(null));

        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
    }

    @Test
    void record_withExplicitSpan_writesToPassedSpanNotCurrent() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        Span span = tracer.spanBuilder("explicit").startSpan();
        recorder.record(span, new IllegalStateException("boom"));
        span.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getName()).isEqualTo("explicit");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("java.lang.IllegalStateException");
        assertThat(data.getEvents().getFirst().getName()).isEqualTo("exception");
    }

    @Test
    void record_withNullSpan_andInvalidSpan_doNotThrow() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        assertThatCode(() -> recorder.record(null, new RuntimeException("x"))).doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(Span.getInvalid(), new RuntimeException("x"))).doesNotThrowAnyException();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void record_withErrorTypeOverride_keepsExceptionClassOnEvent() {
        // given
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        Span span = tracer.spanBuilder("explicit").startSpan();
        IllegalStateException failure = new IllegalStateException("SELECT * FROM users WHERE ssn='123'");

        // when
        recorder.record(span, failure, "23505");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNotEqualTo(failure.getMessage());
        assertThat(data.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(data.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("<redacted>");
        assertThat(data.getStatus().getDescription()).isEqualTo("<redacted>");
    }

    @Test
    void record_withBlankErrorType_usesExceptionClass() {
        // given
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        Span span = tracer.spanBuilder("explicit").startSpan();

        // when
        recorder.record(span, new IllegalStateException("boom"), "   ");
        span.end();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE))
                .isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void record_truncatedStack_keepsCauseWithinBudget() {
        // given
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        IllegalStateException cause = new IllegalStateException("root-marker");
        cause.setStackTrace(new StackTraceElement[0]);
        IllegalStateException outer = new IllegalStateException("outer", cause);
        StackTraceElement[] frames = new StackTraceElement[40];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement(
                    "com.example.VeryLongClassNameThatConsumesBudget" + index,
                    "method",
                    "Type.java",
                    index + 1
            );
        }
        outer.setStackTrace(frames);
        Span span = tracer.spanBuilder("bounded").startSpan();

        // when
        recorder.record(span, outer);
        span.end();

        // then
        String stack = exporter.getFinishedSpanItems().getFirst().getEvents().getFirst()
                .getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE);
        assertThat(stack).contains("root-marker");
        assertThat(stack).contains(BoundedExceptionStackRenderer.BUDGET_EXHAUSTED);
        assertThat(stack).hasSizeLessThanOrEqualTo(BoundedExceptionStackRenderer.DEFAULT_CHARACTER_BUDGET);
    }

    @Test
    void record_shortStack_recordsFrame() {
        // given
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, true));
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.example.A", "method", "A.java", 1)
        });
        Span span = tracer.spanBuilder("short").startSpan();

        // when
        recorder.record(span, exception);
        span.end();

        // then
        assertThat(exporter.getFinishedSpanItems().getFirst().getEvents().getFirst()
                .getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE))
                .contains("com.example.A.method");
    }

    private void runInSpan(Runnable body) {
        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            body.run();
        } finally {
            span.end();
        }
    }
}
