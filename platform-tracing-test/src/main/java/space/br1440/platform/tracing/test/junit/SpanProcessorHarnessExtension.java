package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thin JUnit 5 Extension wrapping {@link SpanProcessorHarness}.
 *
 * <p>Lifecycle (per-class, mirrors plan v2 ┬º8.5):
 * <ul>
 *   <li>{@code beforeAll} ΓÇö creates one {@link SpanProcessorHarness} (and thus one
 *       {@code OpenTelemetrySdk}) per test class; registers it as
 *       {@link ExtensionContext.Store.CloseableResource} so JUnit calls
 *       {@link SpanProcessorHarness#close()} automatically when the class context closes.</li>
 *   <li>{@code beforeEach} ΓÇö calls {@link io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter#reset()}
 *       to isolate spans between tests without recreating the SDK.</li>
 * </ul>
 *
 * <p>Two factory methods:
 * <ul>
 *   <li>{@link #of(Supplier)} ΓÇö invokes the supplier once per class (use when the
 *       processor is stateful and needs fresh construction per class).</li>
 *   <li>{@link #of(SpanProcessor)} ΓÇö uses the same processor instance for every
 *       class that registers this extension (use for stateless processors).</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * @RegisterExtension
 * static final SpanProcessorHarnessExtension ext =
 *         SpanProcessorHarnessExtension.of(EnrichingSpanProcessor::new);
 *
 * @Test
 * void enriches_server_span(SpanProcessorHarness h) {
 *     h.tracer("test").spanBuilder("op").setSpanKind(SpanKind.SERVER).startSpan().end();
 *     assertThat(h.exporter().getFinishedSpanItems()).hasSize(1);
 * }
 * }</pre>
 *
 * @since 5.8
 */
public final class SpanProcessorHarnessExtension
        implements BeforeAllCallback, BeforeEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SpanProcessorHarnessExtension.class);
    private static final String HARNESS_KEY = "harness";

    private final Supplier<SpanProcessor> processorFactory;

    private SpanProcessorHarnessExtension(Supplier<SpanProcessor> processorFactory) {
        this.processorFactory = processorFactory;
    }

    /**
     * Creates an extension whose supplier is called once per test class to produce
     * a fresh {@link SpanProcessor}.
     *
     * @param processorFactory supplier; must not be {@code null}
     */
    public static SpanProcessorHarnessExtension of(Supplier<SpanProcessor> processorFactory) {
        Objects.requireNonNull(processorFactory, "processorFactory must not be null");
        return new SpanProcessorHarnessExtension(processorFactory);
    }

    /**
     * Creates an extension backed by a fixed, pre-built {@link SpanProcessor}.
     * Suitable for stateless processors.
     *
     * @param processor the processor to wrap; must not be {@code null}
     */
    public static SpanProcessorHarnessExtension of(SpanProcessor processor) {
        Objects.requireNonNull(processor, "processor must not be null");
        return new SpanProcessorHarnessExtension(() -> processor);
    }

    // -------------------------------------------------------------------------
    // BeforeAllCallback ΓÇö one SDK per class, auto-closed by JUnit via Store
    // -------------------------------------------------------------------------

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = rootStore(context);
        if (store.get(HARNESS_KEY) == null) {
            SpanProcessorHarness harness = SpanProcessorHarness.of(processorFactory.get());
            store.put(HARNESS_KEY, new CloseableHarness(harness));
        }
    }

    // -------------------------------------------------------------------------
    // BeforeEachCallback ΓÇö reset exporter between tests
    // -------------------------------------------------------------------------

    @Override
    public void beforeEach(ExtensionContext context) {
        harness(context).exporter().reset();
    }

    // -------------------------------------------------------------------------
    // ParameterResolver ΓÇö inject SpanProcessorHarness into test methods
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private SpanProcessorHarness harness(ExtensionContext context) {
        CloseableHarness wrapper = rootStore(context).get(HARNESS_KEY, CloseableHarness.class);
        if (wrapper == null) {
            throw new IllegalStateException(
                    "SpanProcessorHarness not found in JUnit Store ΓÇö " +
                    "ensure SpanProcessorHarnessExtension is declared as " +
                    "@RegisterExtension static.");
        }
        return wrapper.harness;
    }

    /**
     * Walks up the context tree to find the root non-nested context that owns the harness.
     * Mirrors the ancestor-lookup pattern in {@code OtelSdkExtension} (┬º8.4).
     */
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

    // -------------------------------------------------------------------------
    // CloseableResource wrapper ΓÇö JUnit calls close() when Store context closes
    // -------------------------------------------------------------------------

    private static final class CloseableHarness
            implements ExtensionContext.Store.CloseableResource {

        final SpanProcessorHarness harness;

        CloseableHarness(SpanProcessorHarness harness) {
            this.harness = harness;
        }

        @Override
        public void close() throws Exception {
            harness.close();
        }
    }
}
