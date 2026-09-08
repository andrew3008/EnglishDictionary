package space.br1440.platform.tracing.otel.runtime.otel.scope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

class OwningSpanScopeTest {

    private static final AttributeKey<String> RESULT_KEY =
            AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);

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
        MDC.put(TracingMdcKeys.TRACE_ID, "previous-trace");
        Span span = tracerProvider.get("test").spanBuilder("activate").startSpan();

        OwningSpanScope owning = OwningSpanScope.activate(span, ExceptionRecorder.secureDefault());
        try {
            assertThat(Span.current().getSpanContext()).isEqualTo(span.getSpanContext());
            assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo(span.getSpanContext().getTraceId());
            assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isEqualTo(span.getSpanContext().getSpanId());
        } finally {
            owning.close();
        }

        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo("previous-trace");
        assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNull();
    }

    @Test
    void closeWithoutDeclaredResult_writesSuccess() {
        Span span = tracerProvider.get("test").spanBuilder("implicit-success").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());

        owning.close();
        owning.close();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.SUCCESS.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
    }

    @Test
    void closeAfterSetResultFailure_keepsFailure() {
        Span span = tracerProvider.get("test").spanBuilder("explicit-failure").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());

        owning.setResult(SpanResult.FAILURE);
        owning.close();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }

    @Test
    void closeAfterRecordException_keepsFailure() {
        Span span = tracerProvider.get("test").spanBuilder("recorded-failure").startSpan();
        Scope scope = span.makeCurrent();
        OwningSpanScope owning = new OwningSpanScope(span, scope, ExceptionRecorder.secureDefault());

        owning.recordException(new IllegalStateException("boom"));
        owning.close();

        SpanData data = exporter.getFinishedSpanItems().getFirst();
        assertThat(data.getAttributes().get(RESULT_KEY)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }
}
