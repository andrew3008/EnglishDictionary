package space.br1440.platform.tracing.otel.runtime.otel.scope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.exception.ExceptionRecordingPolicy;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class OwningSpanScopeTest {

    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        tracerProvider.close();
    }

    @Test
    void activate_makesSpanCurrentAndProjectsMdc_thenCloseRestoresBoth() {
        // given
        MDC.put(TracingMdcKeys.TRACE_ID, "previous-trace");
        Span span = tracerProvider.get("test").spanBuilder("activate").startSpan();

        // when
        try (OwningSpanScope ignored = OwningSpanScope.activate(span, ExceptionRecorder.secureDefault())) {
            assertThat(Span.current().getSpanContext()).isEqualTo(span.getSpanContext());
            assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo(span.getSpanContext().getTraceId());
            assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isEqualTo(span.getSpanContext().getSpanId());
        }

        // then
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo("previous-trace");
        assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNull();
    }

    @Test
    void closeWithoutDeclaredResult_writesNoResult() {
        // given
        Span span = tracerProvider.get("test").spanBuilder("implicit-success").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());

        // when
        owning.close();
        owning.close();

        // then
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isNull();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(data.hasEnded()).isTrue();
    }

    @Test
    void nullException_doesNotDeclareFailure() {
        // given
        Span span = tracerProvider.get("test").spanBuilder("null-exception").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());

        // when
        owning.recordException(null);
        owning.close();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isNull();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
        assertThat(data.getEvents()).isEmpty();
    }

    @Test
    void closeAfterRecordException_keepsFailure() {
        // given
        Span span = tracerProvider.get("test").spanBuilder("recorded-failure").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());

        // when
        owning.recordException(new IllegalStateException("boom"));
        owning.close();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void nullExceptionAfterRecordedFailure_doesNotClearError() {
        // given
        Span span = tracerProvider.get("test").spanBuilder("latched-failure").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());
        owning.recordException(new IllegalStateException("boom"));

        // when
        owning.recordException(null);
        owning.close();

        // then
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getEvents()).hasSize(1);
    }

    @Test
    void fatalMessageFailure_keepsFailureAndAllowsLateResponseCode() {
        // given
        LinkageError fatal = new LinkageError("Message getter failed");
        RuntimeException failure = new IllegalStateException() {
            @Override
            public String getMessage() {
                throw fatal;
            }
        };
        Span span = tracerProvider.get("test").spanBuilder("fatal-message-getter").startSpan();
        OwningSpanScope owning = new OwningSpanScope(span, span.makeCurrent(),
                new ExceptionRecorder(new ExceptionRecordingPolicy(true, true, 1_000)));

        // when
        try (owning) {
            assertThatThrownBy(() -> owning.recordException(failure)).isSameAs(fatal);
            owning.responseStatusCode("23505");
        }

        // then
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getAttributes().get(SemconvKeys.DB_RESPONSE_STATUS_CODE)).isEqualTo("23505");
        assertThat(data.getAttributes().get(SemconvKeys.ERROR_TYPE)).isEqualTo("23505");
    }
}
