package space.br1440.platform.tracing.autoconfigure.integrations.errorhandling;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class ResolvedServerErrorEventHookTest {

    private static final String CORRELATION_ID = "correlation-1";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private ResolvedServerErrorEventHook hook;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        hook = new ResolvedServerErrorEventHook(ExceptionRecorder.secureDefault());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"INTERNAL_SERVER_ERROR", "BAD_GATEWAY", "SERVICE_UNAVAILABLE"})
    void serverErrorAddsSingleEventWithoutOutcomeWrites(HttpStatus status) {
        // given
        IllegalStateException failure = new IllegalStateException("boom");

        // when
        SpanData data = runInServerSpan(() -> hook.onException(failure, entry(status), CORRELATION_ID));

        // then
        assertThat(data.getEvents()).singleElement().satisfies(event -> {
            assertThat(event.getName()).isEqualTo("exception");
            assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                    .isEqualTo(IllegalStateException.class.getName());
            assertThat(event.getAttributes().get(SemconvKeys.EXCEPTION_MESSAGE)).isEqualTo("boom");
        });
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(data.getStatus().getDescription()).isEmpty();
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isNull();
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "NOT_FOUND", "CONFLICT"})
    void clientErrorAddsNoEvent(HttpStatus status) {
        // given
        IllegalArgumentException failure = new IllegalArgumentException("invalid");

        // when
        SpanData data = runInServerSpan(() -> hook.onException(failure, entry(status), CORRELATION_ID));

        // then
        assertThat(data.getEvents()).isEmpty();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(data.getAttributes().get(SemconvKeys.PLATFORM_RESULT)).isNull();
    }

    @Test
    void serverErrorKeepsAgentStatusAndErrorType() {
        // given
        IllegalStateException failure = new IllegalStateException("boom");

        // when
        SpanData data = runInServerSpan(() -> {
            Span.current().setStatus(StatusCode.ERROR);
            Span.current().setAttribute(SemconvKeys.ERROR_TYPE, "500");
            hook.onException(failure, entry(HttpStatus.INTERNAL_SERVER_ERROR), CORRELATION_ID);
        });

        // then
        assertThat(data.getEvents()).hasSize(1);
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getStatus().getDescription()).isEmpty();
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("500");
    }

    @Test
    void nonRecordingCurrentSpanDoesNotReadException() {
        // given
        CountingException failure = new CountingException("private-message");

        // when
        try (Scope ignored = Span.getInvalid().makeCurrent()) {
            hook.onException(failure, entry(HttpStatus.INTERNAL_SERVER_ERROR), CORRELATION_ID);
        }

        // then
        assertThat(failure.messageReads).isZero();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    private SpanData runInServerSpan(Runnable body) {
        Span span = tracerProvider.get("test").spanBuilder("GET /orders").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            body.run();
        } finally {
            span.end();
        }

        return exporter.getFinishedSpanItems().getFirst();
    }

    private static ErrorEntry entry(HttpStatus status) {
        return ErrorEntry.builder()
                .httpStatus(status)
                .errorCode("TEST_ERROR")
                .message("test error")
                .build();
    }

    private static final class CountingException extends RuntimeException {

        private int messageReads;

        private CountingException(String message) {
            super(message, null, false, false);
        }

        @Override
        public String getMessage() {
            messageReads++;
            return super.getMessage();
        }
    }
}
