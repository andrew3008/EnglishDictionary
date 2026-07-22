package space.br1440.platform.tracing.core.runtime.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.api.span.spec.SpanSpec;
import space.br1440.platform.tracing.api.span.spec.SpanSpecReason;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OtelTracingRuntimeCreationAttributesTest {

    private static final AttributeKey<String> STRING_KEY = AttributeKey.stringKey("test.string");
    private static final AttributeKey<Long> LONG_KEY = AttributeKey.longKey("test.long");
    private static final AttributeKey<Double> DOUBLE_KEY = AttributeKey.doubleKey("test.double");
    private static final AttributeKey<Boolean> BOOLEAN_KEY = AttributeKey.booleanKey("test.boolean");
    private static final AttributeKey<List<String>> STRING_LIST_KEY = AttributeKey.stringArrayKey("test.strings");
    private static final AttributeKey<List<Long>> LONG_LIST_KEY = AttributeKey.longArrayKey("test.longs");
    private static final AttributeKey<List<Double>> DOUBLE_LIST_KEY = AttributeKey.doubleArrayKey("test.doubles");
    private static final AttributeKey<List<Boolean>> BOOLEAN_LIST_KEY = AttributeKey.booleanArrayKey("test.booleans");
    private static final AttributeKey<List<String>> EMPTY_STRING_LIST_KEY =
            AttributeKey.stringArrayKey("test.empty.strings");
    private static final AttributeKey<List<Long>> EMPTY_LONG_LIST_KEY =
            AttributeKey.longArrayKey("test.empty.longs");
    private static final AttributeKey<List<Double>> EMPTY_DOUBLE_LIST_KEY =
            AttributeKey.doubleArrayKey("test.empty.doubles");
    private static final AttributeKey<List<Boolean>> EMPTY_BOOLEAN_LIST_KEY =
            AttributeKey.booleanArrayKey("test.empty.booleans");

    @Test
    void samplerИExporterПолучаютОдинаковыеTypedАтрибутыДоСтартаSpan() {
        AtomicReference<Attributes> sampledAttributes = new AtomicReference<>();
        Sampler sampler = new CapturingSampler(sampledAttributes);
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        try {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
            var runtime = OtelTracingRuntimeFactory.create(sdk);
            SpanSpec spec = SpanSpec.builder("typed-attributes")
                    .category(SpanCategory.INTERNAL)
                    .attribute(STRING_KEY.getKey(), "value")
                    .attribute(LONG_KEY.getKey(), 42L)
                    .attribute(DOUBLE_KEY.getKey(), 1.5d)
                    .attribute(BOOLEAN_KEY.getKey(), true)
                    .stringListAttribute(STRING_LIST_KEY.getKey(), List.of("a", "b"))
                    .longListAttribute(LONG_LIST_KEY.getKey(), List.of(1L, 2L))
                    .doubleListAttribute(DOUBLE_LIST_KEY.getKey(), List.of(2.5d, 3.5d))
                    .booleanListAttribute(BOOLEAN_LIST_KEY.getKey(), List.of(true, false))
                    .stringListAttribute(EMPTY_STRING_LIST_KEY.getKey(), List.of())
                    .longListAttribute(EMPTY_LONG_LIST_KEY.getKey(), List.of())
                    .doubleListAttribute(EMPTY_DOUBLE_LIST_KEY.getKey(), List.of())
                    .booleanListAttribute(EMPTY_BOOLEAN_LIST_KEY.getKey(), List.of())
                    .reason(SpanSpecReason.PLATFORM_EDGE_CASE)
                    .build();

            runtime.startSpan(spec).close();

            Attributes samplerView = sampledAttributes.get();
            SpanData exported = exporter.getFinishedSpanItems().getFirst();
            assertTypedAttributes(samplerView);
            assertTypedAttributes(exported.getAttributes());
            assertThat(samplerView.get(AttributeKey.stringKey(PlatformAttributes.PLATFORM_TYPE)))
                    .isEqualTo(SpanCategory.INTERNAL.value());
        } finally {
            provider.shutdown();
        }
    }

    private static void assertTypedAttributes(Attributes attributes) {
        assertThat(attributes.get(STRING_KEY)).isEqualTo("value");
        assertThat(attributes.get(LONG_KEY)).isEqualTo(42L);
        assertThat(attributes.get(DOUBLE_KEY)).isEqualTo(1.5d);
        assertThat(attributes.get(BOOLEAN_KEY)).isTrue();
        assertThat(attributes.get(STRING_LIST_KEY)).containsExactly("a", "b");
        assertThat(attributes.get(LONG_LIST_KEY)).containsExactly(1L, 2L);
        assertThat(attributes.get(DOUBLE_LIST_KEY)).containsExactly(2.5d, 3.5d);
        assertThat(attributes.get(BOOLEAN_LIST_KEY)).containsExactly(true, false);
        assertThat(attributes.get(EMPTY_STRING_LIST_KEY)).isEmpty();
        assertThat(attributes.get(EMPTY_LONG_LIST_KEY)).isEmpty();
        assertThat(attributes.get(EMPTY_DOUBLE_LIST_KEY)).isEmpty();
        assertThat(attributes.get(EMPTY_BOOLEAN_LIST_KEY)).isEmpty();
    }

    private record CapturingSampler(AtomicReference<Attributes> sampledAttributes) implements Sampler {

        @Override
        public SamplingResult shouldSample(Context parentContext,
                                           String traceId,
                                           String name,
                                           SpanKind spanKind,
                                           Attributes attributes,
                                           List<LinkData> parentLinks) {
            sampledAttributes.set(attributes);
            return SamplingResult.recordAndSample();
        }

        @Override
        public String getDescription() {
            return "CapturingSampler";
        }
    }
}
