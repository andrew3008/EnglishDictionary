package space.br1440.platform.tracing.test.junit.internal;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SdkResource implements ExtensionContext.Store.CloseableResource {

    private final OpenTelemetrySdk sdk;
    private final InMemorySpanExporter exporter;
    private final ScopeMode scope;

    private SdkResource(OpenTelemetrySdk sdk, InMemorySpanExporter exporter, ScopeMode scope) {
        this.sdk = sdk;
        this.exporter = exporter;
        this.scope = scope;
    }

    public static SdkResource build(ScopeMode scope,
                                    @Nullable Sampler sampler,
                                    List<SpanProcessor> extraProcessors) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(extraProcessors, "extraProcessors");

        InMemorySpanExporter exporter = InMemorySpanExporter.create();

        SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder();
        if (sampler != null) {
            tracerProviderBuilder.setSampler(sampler);
        }

        for (SpanProcessor extra : extraProcessors) {
            if (extra == null) {
                throw new IllegalArgumentException("extraProcessors не должен содержать null");
            }

            tracerProviderBuilder.addSpanProcessor(extra);
        }

        tracerProviderBuilder.addSpanProcessor(SimpleSpanProcessor.create(exporter));

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProviderBuilder.build())
                .build();

        return new SdkResource(sdk, exporter, scope);
    }

    public OpenTelemetrySdk sdk() {
        return sdk;
    }

    public InMemorySpanExporter exporter() {
        return exporter;
    }

    public ScopeMode scope() {
        return scope;
    }

    public void resetBetweenTestsIfNeeded() {
        if (scope.resetsBetweenTests()) {
            exporter.reset();
        }
    }

    public static List<SpanProcessor> defensiveCopy(List<SpanProcessor> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    @Override
    public void close() {
        sdk.close();
    }
}
