package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.SpanProcessor;
import org.junit.jupiter.api.extension.AfterEachCallback;
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
 * <p>Provides {@code @RegisterExtension} syntax with automatic per-test lifecycle:
 * <ul>
 *   <li>{@code beforeEach} — creates a <em>fresh</em> {@link SpanProcessorHarness}
 *       (and therefore a fresh processor instance) before every test, guaranteeing
 *       full processor isolation.</li>
 *   <li>{@code afterEach} — closes the harness (flushes + shuts down the SDK).</li>
 * </ul>
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SpanProcessorHarnessExtension ext =
 *         SpanProcessorHarnessExtension.of(() -> new EnrichingSpanProcessor());
 *
 * @Test
 * void enriches_platform_type(SpanProcessorHarness h) {
 *     h.tracer().spanBuilder("server-op").setSpanKind(SpanKind.SERVER).startSpan().end();
 *     SpanAssertions.assertSpan(h.spans(), "server-op")
 *             .hasAttribute(stringKey("platform.type"), "http_server");
 * }
 * }</pre>
 *
 * <p>If the processor is stateless you may skip the extension and use
 * {@link SpanProcessorHarness} directly with {@code try-with-resources}.
 *
 * @since 5.4
 */
public final class SpanProcessorHarnessExtension
        implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SpanProcessorHarnessExtension.class);
    private static final String HARNESS_KEY = "harness";

    private final Supplier<SpanProcessor> processorFactory;

    private SpanProcessorHarnessExtension(Supplier<SpanProcessor> processorFactory) {
        this.processorFactory = processorFactory;
    }

    /**
     * Creates an extension that builds a fresh {@link SpanProcessorHarness} before
     * each test by invoking {@code processorFactory.get()}.
     *
     * <p>Use a constant supplier ({@code () -> SHARED_PROCESSOR}) when the processor
     * is stateless and reconstruction overhead matters.
     *
     * @param processorFactory supplier invoked once per test; must not be {@code null}
     * @return a new extension instance
     */
    public static SpanProcessorHarnessExtension of(Supplier<SpanProcessor> processorFactory) {
        Objects.requireNonNull(processorFactory, "processorFactory must not be null");
        return new SpanProcessorHarnessExtension(processorFactory);
    }

    // -------------------------------------------------------------------------
    // BeforeEachCallback
    // -------------------------------------------------------------------------

    @Override
    public void beforeEach(ExtensionContext context) {
        SpanProcessorHarness harness = SpanProcessorHarness.of(processorFactory.get());
        context.getStore(NS).put(HARNESS_KEY, harness);
    }

    // -------------------------------------------------------------------------
    // AfterEachCallback
    // -------------------------------------------------------------------------

    @Override
    public void afterEach(ExtensionContext context) {
        SpanProcessorHarness harness = context.getStore(NS)
                .remove(HARNESS_KEY, SpanProcessorHarness.class);
        if (harness != null) {
            harness.close();
        }
    }

    // -------------------------------------------------------------------------
    // ParameterResolver
    // -------------------------------------------------------------------------

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return SpanProcessorHarness.class
                .equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        return harness(extensionContext);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private SpanProcessorHarness harness(ExtensionContext context) {
        SpanProcessorHarness h = context.getStore(NS)
                .get(HARNESS_KEY, SpanProcessorHarness.class);
        if (h == null) {
            throw new IllegalStateException(
                    "SpanProcessorHarness not found in JUnit Store — " +
                    "ensure SpanProcessorHarnessExtension is declared as " +
                    "@RegisterExtension (static or instance).");
        }
        return h;
    }
}
