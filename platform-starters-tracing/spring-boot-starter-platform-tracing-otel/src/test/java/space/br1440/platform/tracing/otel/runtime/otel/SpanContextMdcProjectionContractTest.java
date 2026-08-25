package space.br1440.platform.tracing.otel.runtime.otel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.mdc.TracingMdcKeys;
import space.br1440.platform.tracing.api.span.spec.SpanHandle;
import space.br1440.platform.tracing.otel.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

class SpanContextMdcProjectionContractTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private OpenTelemetrySdk openTelemetry;
    private TracingRuntime runtime;
    private TraceOperations operations;

    private static final String STRICT_CONTEXT = "io.opentelemetry.context.enableStrictContext";
    private static String previousStrictContext;

    @BeforeAll
    static void enableStrictContext() {
        previousStrictContext = System.getProperty(STRICT_CONTEXT);
        System.setProperty(STRICT_CONTEXT, "true");
    }

    @AfterAll
    static void restoreStrictContext() {
        if (previousStrictContext == null) {
            System.clearProperty(STRICT_CONTEXT);
        } else {
            System.setProperty(STRICT_CONTEXT, previousStrictContext);
        }
    }

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        runtime = OtelTracingRuntimeFactory.create(openTelemetry);
        operations = new DefaultTraceOperations(runtime);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        tracerProvider.shutdown();
    }

    @Test
    void requestScopeProjectsCurrentSpanAndRestoresPreviousMdc() {
        // given
        Span span = openTelemetry.getTracer("test").spanBuilder("server").startSpan();
        try (io.opentelemetry.context.Scope ignored = span.makeCurrent()) {
            // when
            try (CorrelationScope ignored1 = runtime.openRequestIdentityScope("req-1")) {
                // then
                assertMdc(span.getSpanContext());
                assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isEqualTo("req-1");
            }
        } finally {
            span.end();
        }

        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isNull();
        assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNull();
        assertThat(MDC.get(TracingMdcKeys.TRACE_FLAGS)).isNull();
        assertThat(MDC.get(TracingMdcKeys.REQUEST_ID)).isNull();
    }

    @Test
    void nestedManualSpanUpdatesSpanIdAndRestoresParent() {
        // given
        try (SpanHandle parent = operations.spans().operation("parent").start()) {
            String parentSpanId = MDC.get(TracingMdcKeys.SPAN_ID);
            assertThat(parentSpanId).isNotBlank();
            assertThat(MDC.get(TracingMdcKeys.TRACE_FLAGS)).isEqualTo(Span.current().getSpanContext().getTraceFlags().asHex());

            // when
            try (SpanHandle ignored = operations.spans().operation("child").start()) {
                // then
                assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isEqualTo(Span.current().getSpanContext().getSpanId());
                assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNotEqualTo(parentSpanId);
            }

            assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isEqualTo(parentSpanId);
        }

        assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNull();
    }

    @Test
    void invalidSpanRemovesStaleTraceMdc() {
        // given
        MDC.put(TracingMdcKeys.TRACE_ID, "stale");
        MDC.put(TracingMdcKeys.SPAN_ID, "stale-span");
        MDC.put(TracingMdcKeys.TRACE_FLAGS, "01");

        // when
        try (CorrelationScope ignored = runtime.openRequestIdentityScope("req-stale")) {
            // then
            assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isNull();
            assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNull();
            assertThat(MDC.get(TracingMdcKeys.TRACE_FLAGS)).isNull();
        }

        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo("stale");
    }

    @Test
    void exceptionPathRestoresMdc() {
        // given
        IllegalStateException failure = new IllegalStateException("expected");

        try {
            // when
            operations.spans().operation("failing").run(() -> {
                assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo(Span.current().getSpanContext().getTraceId());
                throw failure;
            });
        } catch (IllegalStateException thrown) {
            assertThat(thrown).isSameAs(failure);
        }

        // then
        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isNull();
        assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isNull();
    }

    private static void assertMdc(SpanContext spanContext) {
        assertThat(MDC.get(TracingMdcKeys.TRACE_ID)).isEqualTo(spanContext.getTraceId());
        assertThat(MDC.get(TracingMdcKeys.SPAN_ID)).isEqualTo(spanContext.getSpanId());
        assertThat(MDC.get(TracingMdcKeys.TRACE_FLAGS)).isEqualTo(spanContext.getTraceFlags().asHex());
    }
}
