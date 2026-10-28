package space.br1440.platform.tracing.otel.exception;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
    void record_secureDefault_doesNotWriteMessageAndStacktrace_butSetsErrorTypeAndStatus() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        runInSpan(() -> recorder.record(new IllegalStateException("SELECT * FROM users WHERE ssn='123'")));

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("failure");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        // Секьюр-дефолт: НЕ должно быть description и exception.message (защита от утечки PII/SQL).
        assertThat(span.getStatus().getDescription()).isEmpty();

        EventData event = span.getEvents().getFirst();
        assertThat(event.getName()).isEqualTo("exception");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void record_withAllowedMessage_writesTruncatedMessageAndDescription() {
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionRecordingPolicy(true, false));
        runInSpan(() -> recorder.record(new IllegalArgumentException("boom")));

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getDescription()).isEqualTo("boom");
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE))
                .isEqualTo("boom");
        // stacktrace всё равно off
        assertThat(span.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void record_withoutActiveSpan_andNull_doNotThrowAndDoNotCreateSpan() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        assertThatCode(() -> recorder.record(new RuntimeException("no span"))).doesNotThrowAnyException();
        runInSpan(() -> recorder.record(null));

        // первый вызов вне span'а ничего не экспортировал; runInSpan(null) создал пустой span без error
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().getFirst().getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
    }

    @Test
    void record_withExplicitSpan_writesToPassedSpanNotCurrent() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        // span НЕ делаем current — overload record(Span, Throwable) должен писать именно в него.
        Span span = tracer.spanBuilder("explicit").startSpan();
        recorder.record(span, new IllegalStateException("boom"));
        span.end();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getName()).isEqualTo("explicit");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(data.getEvents().getFirst().getName()).isEqualTo("exception");
    }

    @Test
    void record_withNullSpan_andInvalidSpan_doNotThrow() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        assertThatCode(() -> recorder.record(null, new RuntimeException("x")))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(Span.getInvalid(), new RuntimeException("x")))
                .doesNotThrowAnyException();
        // Невалидный span ничего не экспортирует.
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void markCurrentSpanAsError_setsStatusErrorAndResultFailure() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        runInSpan(recorder::markCurrentSpanAsError);

        SpanData span = exporter.getFinishedSpanItems().getFirst();
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("failure");
        assertThat(span.getEvents()).isEmpty();
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
        assertThat(data.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
        assertThat(data.getStatus().getDescription()).isEmpty();
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

    private void runInSpan(Runnable body) {
        Span span = tracer.spanBuilder("op").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            body.run();
        } finally {
            span.end();
        }
    }
}
