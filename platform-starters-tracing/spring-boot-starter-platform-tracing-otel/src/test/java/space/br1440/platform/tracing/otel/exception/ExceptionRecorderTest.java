package space.br1440.platform.tracing.otel.exception;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
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
        tracer = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build().getTracer("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void record_секьюрДефолт_неПишетMessageИStacktrace_ноСтавитErrorTypeИStatus() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        runInSpan(() -> recorder.record(new IllegalStateException("SELECT * FROM users WHERE ssn='123'")));

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(SemconvKeys.ERROR_TYPE))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("failure");
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        // Секьюр-дефолт: НЕ должно быть description и exception.message (защита от утечки PII/SQL).
        assertThat(span.getStatus().getDescription()).isEmpty();

        EventData event = span.getEvents().get(0);
        assertThat(event.getName()).isEqualTo("exception");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isNull();
        assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void record_сРазрешённымMessage_пишетУсечённыйMessageИDescription() {
        ExceptionRecorder recorder = new ExceptionRecorder(new ExceptionMessagePolicy(true, false));
        runInSpan(() -> recorder.record(new IllegalArgumentException("boom")));

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getDescription()).isEqualTo("boom");
        assertThat(span.getEvents().get(0).getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE))
                .isEqualTo("boom");
        // stacktrace всё равно off
        assertThat(span.getEvents().get(0).getAttributes().get(SemconvKeys.EXCEPTION_STACKTRACE)).isNull();
    }

    @Test
    void record_безАктивногоSpan_иNull_неБросаютИНеСоздаютSpan() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        assertThatCode(() -> recorder.record(new RuntimeException("no span"))).doesNotThrowAnyException();
        runInSpan(() -> recorder.record(null));

        // первый вызов вне span'а ничего не экспортировал; runInSpan(null) создал пустой span без error
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().get(0).getAttributes().get(SemconvKeys.PLATFORM_RESULT))
                .isNull();
    }

    @Test
    void record_сЯвнымSpan_пишетВПереданныйSpanАНеВCurrent() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        // span НЕ делаем current — overload record(Span, Throwable) должен писать именно в него.
        Span span = tracer.spanBuilder("explicit").startSpan();
        recorder.record(span, new IllegalStateException("boom"));
        span.end();

        SpanData data = exporter.getFinishedSpanItems().get(0);
        assertThat(data.getName()).isEqualTo("explicit");
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE))
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(data.getEvents().get(0).getName()).isEqualTo("exception");
    }

    @Test
    void record_сNullSpan_иInvalidSpan_неБросают() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        assertThatCode(() -> recorder.record((Span) null, new RuntimeException("x")))
                .doesNotThrowAnyException();
        assertThatCode(() -> recorder.record(Span.getInvalid(), new RuntimeException("x")))
                .doesNotThrowAnyException();
        // Невалидный span ничего не экспортирует.
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void markCurrentSpanAsError_ставитStatusErrorИResultFailure() {
        ExceptionRecorder recorder = ExceptionRecorder.secureDefault();
        runInSpan(recorder::markCurrentSpanAsError);

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isEqualTo("failure");
        assertThat(span.getEvents()).isEmpty();
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
