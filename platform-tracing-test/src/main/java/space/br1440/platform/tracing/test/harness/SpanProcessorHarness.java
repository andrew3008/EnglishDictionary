package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.annotation.Nullable;

import java.util.Objects;

public final class SpanProcessorHarness implements AutoCloseable {

    private final OpenTelemetrySdk sdk;
    private final InMemorySpanExporter exporter;

    private SpanProcessorHarness(OpenTelemetrySdk sdk, InMemorySpanExporter exporter) {
        this.sdk = sdk;
        this.exporter = exporter;
    }

    public static SpanProcessorHarness of(SpanProcessor processorUnderTest) {
        return of(processorUnderTest, null);
    }

    public static SpanProcessorHarness of(SpanProcessor processorUnderTest, @Nullable Resource resource) {
        Objects.requireNonNull(processorUnderTest, "processorUnderTest");

        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder()
                .addSpanProcessor(processorUnderTest)
                .addSpanProcessor(SimpleSpanProcessor.create(exporter));

        if (resource != null) {
            tracerProviderBuilder.setResource(resource);
        }

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProviderBuilder.build())
                .build();

        return new SpanProcessorHarness(sdk, exporter);
    }

    public OpenTelemetry openTelemetry() {
        return sdk;
    }

    public Tracer tracer(String instrumentationScopeName) {
        return sdk.getTracer(instrumentationScopeName);
    }

    public InMemorySpanExporter exporter() {
        return exporter;
    }

    @Override
    public void close() {
        sdk.close();
    }
}
