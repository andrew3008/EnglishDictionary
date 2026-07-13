package space.br1440.platform.tracing.test;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.core.facade.DefaultTraceOperations;
import space.br1440.platform.tracing.core.runtime.otel.OtelTracingRuntimeFactory;

public class TraceOperationsTestExtension implements BeforeEachCallback, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(TraceOperationsTestExtension.class);

    private enum Key { SDK, EXPORTER }

    @Override
    public void beforeEach(ExtensionContext context) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();

        Store store = context.getStore(NAMESPACE);
        store.put(Key.SDK, (ExtensionContext.Store.CloseableResource) sdk::close);
        store.put(Key.EXPORTER, exporter);
        store.put(TraceOperations.class, new DefaultTraceOperations(OtelTracingRuntimeFactory.create(sdk)));
        store.put(OpenTelemetrySdk.class, sdk);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == TraceOperations.class
                || type == OpenTelemetry.class
                || type == OpenTelemetrySdk.class
                || type == InMemorySpanExporter.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        Store store = extensionContext.getStore(NAMESPACE);

        Class<?> type = parameterContext.getParameter().getType();
        if (type == TraceOperations.class) {
            return store.get(TraceOperations.class);
        }

        if (type == OpenTelemetry.class || type == OpenTelemetrySdk.class) {
            return store.get(OpenTelemetrySdk.class);
        }

        if (type == InMemorySpanExporter.class) {
            return store.get(Key.EXPORTER);
        }

        throw new ParameterResolutionException("Unsupported parameter type: " + type);
    }
}
