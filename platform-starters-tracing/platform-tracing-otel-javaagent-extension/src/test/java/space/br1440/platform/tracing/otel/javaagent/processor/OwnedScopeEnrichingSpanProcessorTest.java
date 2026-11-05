package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanResult;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.exception.ExceptionRecorder;
import space.br1440.platform.tracing.otel.runtime.SpanHandleImpl;
import space.br1440.platform.tracing.otel.runtime.otel.scope.OwningSpanScope;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class OwnedScopeEnrichingSpanProcessorTest {

    private static final AttributeKey<String> RESULT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_RESULT);

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        // given
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(new EnrichingSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void normalCloseWithoutResult_exportsSuccessAndUnset() {
        // given
        Span span = start("normal");
        SpanHandle handle = handle(span);

        // when
        assertThat(((ReadableSpan) span).getAttribute(RESULT)).isNull();
        handle.close();

        // then
        SpanData data = finished();
        assertThat(data.getAttributes().get(RESULT)).isEqualTo(SpanResult.SUCCESS.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void explicitRejected_survivesNormalClose() {
        // given
        Span span = start("rejected");
        SpanHandle handle = handle(span);
        span.setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.REJECTED.value());

        // when
        handle.close();

        // then
        SpanData data = finished();
        assertThat(data.getAttributes().get(RESULT)).isEqualTo(SpanResult.REJECTED.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);
    }

    @Test
    void ownedRecordException_exportsFailureAndError() {
        // given
        IllegalStateException failure = new IllegalStateException("owned failure");
        Span span = start("failed");
        SpanHandle handle = handle(span);

        // when
        handle.recordException(failure);
        handle.close();

        // then
        SpanData data = finished();
        assertThat(data.getAttributes().get(RESULT)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(failure.getClass().getName());
    }

    @Test
    void rejectedThenLifecycleException_keepsFailureAndOriginalThrowable() {
        // given
        IllegalStateException failure = new IllegalStateException("after rejected");
        Span span = start("rejected-then-failed");
        SpanHandle handle = handle(span);
        span.setAttribute(PlatformAttributes.PLATFORM_RESULT, SpanResult.REJECTED.value());

        // when
        handle.recordException(failure);
        handle.close();

        // then
        SpanData data = finished();
        assertThat(data.getAttributes().get(RESULT)).isEqualTo(SpanResult.FAILURE.value());
        assertThat(data.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(data.getEvents().getFirst().getAttributes().get(SemconvKeys.EXCEPTION_TYPE))
                .isEqualTo(failure.getClass().getName());
    }

    private Span start(String name) {
        return tracerProvider.get("owned-scope").spanBuilder(name).startSpan();
    }

    private static SpanHandle handle(Span span) {
        return SpanHandleImpl.wrap(OwningSpanScope.activate(span, ExceptionRecorder.secureDefault()));
    }

    private SpanData finished() {
        return exporter.getFinishedSpanItems().getFirst();
    }
}
