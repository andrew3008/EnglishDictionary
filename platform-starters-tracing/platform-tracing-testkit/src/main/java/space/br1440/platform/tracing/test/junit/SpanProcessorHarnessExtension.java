package space.br1440.platform.tracing.test.junit;

import java.util.Objects;
import java.util.function.Supplier;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.sdk.trace.SpanProcessor;

public final class SpanProcessorHarnessExtension implements BeforeAllCallback, BeforeEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(SpanProcessorHarnessExtension.class);
    private static final String HARNESS_KEY = "harness";

    private final Supplier<SpanProcessor> processorFactory;

    private SpanProcessorHarnessExtension(Supplier<SpanProcessor> processorFactory) {
        this.processorFactory = processorFactory;
    }

    public static SpanProcessorHarnessExtension of(Supplier<SpanProcessor> processorFactory) {
        Objects.requireNonNull(processorFactory, "processorFactory must not be null");
        return new SpanProcessorHarnessExtension(processorFactory);
    }

    public static SpanProcessorHarnessExtension of(SpanProcessor processor) {
        Objects.requireNonNull(processor, "processor must not be null");
        return new SpanProcessorHarnessExtension(() -> processor);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = rootStore(context);
        if (store.get(HARNESS_KEY) == null) {
            SpanProcessorHarness harness = SpanProcessorHarness.of(processorFactory.get());
            store.put(HARNESS_KEY, new CloseableHarness(harness));
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        resetStoredExporter(context);
    }

    @SuppressWarnings("resource")
    private void resetStoredExporter(ExtensionContext context) {
        harness(context).exporter().reset();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return SpanProcessorHarness.class.equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        return harness(extensionContext);
    }

    private SpanProcessorHarness harness(ExtensionContext context) {
        CloseableHarness wrapper = rootStore(context).get(HARNESS_KEY, CloseableHarness.class);
        if (wrapper == null) {
            throw new IllegalStateException(
                    "SpanProcessorHarness not found in JUnit Store — " +
                    "ensure SpanProcessorHarnessExtension is declared as " +
                    "@RegisterExtension static.");
        }

        return wrapper.harness;
    }

    private ExtensionContext.Store rootStore(ExtensionContext context) {
        ExtensionContext walker = context;
        while (walker.getParent().isPresent()) {
            var parent = walker.getParent().get();
            if (parent.getStore(NS).get(HARNESS_KEY) != null) {
                return parent.getStore(NS);
            }

            walker = parent;
        }

        return context.getStore(NS);
    }

    private static final class CloseableHarness implements ExtensionContext.Store.CloseableResource {

        final SpanProcessorHarness harness;

        CloseableHarness(SpanProcessorHarness harness) {
            this.harness = harness;
        }

        @Override
        public void close() {
            harness.close();
        }
    }
}
