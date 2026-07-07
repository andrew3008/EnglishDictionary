package space.br1440.platform.tracing.core.span;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanScope;
import space.br1440.platform.tracing.core.DefaultPlatformTracing;
import space.br1440.platform.tracing.core.exception.ExceptionRecorder;
import space.br1440.platform.tracing.core.impl.DefaultTracingImplementation;
import space.br1440.platform.tracing.core.semconv.AttributePolicy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class InternalSpanBuilderImplTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;
    private DefaultPlatformTracing tracing;
    private InternalSpanBuilderImpl internalSpan;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        tracing = new DefaultPlatformTracing(sdk);
        Tracer tracer = sdk.getTracer(DefaultTracingImplementation.INSTRUMENTATION_NAME);
        internalSpan = new InternalSpanBuilderImpl(tracer, new AttributePolicy(), ExceptionRecorder.secureDefault());
    }

    @AfterEach
    void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    void internalSpan_создаётSpanKindINTERNAL_сИменемИplatformType() {
        try (SpanScope scope = internalSpan.name("checkout").start()) {
            assertThat(scope).isNotNull();
        }

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getKind()).isEqualTo(SpanKind.INTERNAL);
        assertThat(span.getName()).isEqualTo("checkout");
        assertThat(span.getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE)))
                .isEqualTo("internal");
    }

    @Test
    void lazyAtribut_дляCreationTimeКлюча_бросаетIllegalArgumentException() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() ->
                internalSpan.lazyAttribute(SemconvKeys.PLATFORM_TRACE_PRIORITY, () -> "high"));
    }

    @Test
    void lazyAtribut_дляОбычногоКлюча_применяетсяНаSpan() {
        try (SpanScope scope = internalSpan
                .name("op")
                .lazyAttribute(SemconvKeys.PLATFORM_REQUEST_ID, () -> "req-77")
                .start()) {
            assertThat(scope).isNotNull();
        }

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(SemconvKeys.PLATFORM_REQUEST_ID)).isEqualTo("req-77");
    }

    @Test
    void reEntry_тойЖеКатегории_неСоздаётНовыйSpan_иНеЗавершаетРодителя() {
        try (SpanScope parent = internalSpan.name("parent").start()) {
            SpanScope reentry = internalSpan.name("child").start();
            reentry.close();

            assertThat(tracing.traceContext().spanId()).isPresent();
            assertThat(exporter.getFinishedSpanItems()).isEmpty();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("parent");
    }

    @Test
    void reEntry_сForceNewSpan_создаётОтдельныйSpan() {
        try (SpanScope parent = internalSpan.name("parent").start()) {
            try (SpanScope child = internalSpan.name("child").forceNewSpan().start()) {
                assertThat(child).isNotNull();
            }
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);
        assertThat(spans).extracting(SpanData::getName).contains("parent", "child");
    }

    @Test
    void manualOperation_andInternalBuilder_createDistinctSpans() {
        try (var outer = tracing.manual().operation("outer").start()) {
            SpanScope inner = internalSpan.name("inner").start();
            inner.close();
        }

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
    }
}
