package space.br1440.platform.tracing.otel.runtime.otel.scope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
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
        tracerProvider.close();
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
