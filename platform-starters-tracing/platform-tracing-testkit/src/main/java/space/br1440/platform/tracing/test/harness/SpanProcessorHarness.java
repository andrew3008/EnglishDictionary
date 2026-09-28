package space.br1440.platform.tracing.test.harness;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Nullable;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public final class SpanProcessorHarness implements AutoCloseable {

    private static final String DEFAULT_TRACER_NAME = "test";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 2;

    private final OpenTelemetrySdk sdk;
    private final InMemorySpanExporter exporter;
    private final Tracer defaultTracer;

    private SpanProcessorHarness(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        this.sdk = sdk;
        this.exporter = exporter;
        this.defaultTracer = sdk.getTracer(DEFAULT_TRACER_NAME);
    }

    public static SpanProcessorHarness of(SpanProcessor processorUnderTest) {
        return of(processorUnderTest, null);
    }

    public static SpanProcessorHarness of(SpanProcessor processorUnderTest,
                                          @Nullable Resource resource) {
        Objects.requireNonNull(processorUnderTest, "processorUnderTest");

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
                .addSpanProcessor(processorUnderTest)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));

        if (resource != null) {
            builder.setResource(resource);
        }

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(builder.build())
                .build();

        return new SpanProcessorHarness(sdk, exporter);
    }

    public Tracer tracer() {
        return defaultTracer;
    }

    public Tracer tracer(String instrumentationScopeName) {
        return sdk.getTracer(
                Objects.requireNonNull(instrumentationScopeName, "instrumentationScopeName")
        );
    }

    public List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    public void reset() {
        exporter.reset();
    }

    public InMemorySpanExporter exporter() {
        return exporter;
    }

    public OpenTelemetry openTelemetry() {
        return sdk;
    }

    @Override
    public void close() {
        sdk.getSdkTracerProvider()
                .forceFlush()
                .join(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        sdk.close();
    }
}
