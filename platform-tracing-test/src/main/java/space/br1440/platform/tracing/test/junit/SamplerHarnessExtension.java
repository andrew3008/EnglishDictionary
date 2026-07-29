package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thin JUnit 5 Extension wrapping {@link SamplerHarness}.
 *
 * <p>Provides {@code @RegisterExtension} syntax with per-test harness isolation:
 * <ul>
 *   <li>{@code beforeEach} — creates a fresh {@link SamplerHarness} (and thus a fresh
 *       sampler instance) before every test method.</li>
 *   <li>{@code afterEach} — no-op; {@code SamplerHarness} holds no I/O resources.</li>
 * </ul>
 *
 * <p>If the sampler is stateless, the extension is optional — you can use
 * {@link SamplerHarness} directly:
 *
 * <pre>{@code
 * @Test
 * void drops_healthcheck() {
 *     new SamplerHarness(sampler)
 *             .putStringAttribute("url.path", "/actuator/health")
 *             .withKind(SpanKind.SERVER)
 *             .sample();
 * }
 * }</pre>
 *
 * <p>Use this extension when the sampler is stateful or when you want the harness
 * automatically reset between tests:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SamplerHarnessExtension ext =
 *         SamplerHarnessExtension.of(() -> buildCompositeSampler());
 *
 * @Test
 * void force_header_samples(SamplerHarness h) {
 *     h.putStringAttribute("http.request.header.x-trace-on", "on")
 *      .spanKind(SpanKind.SERVER)
 *      .sample();
 * }
 * }</pre>
 *
 * @since 5.7
 */
public final class SamplerHarnessExtension
        implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SamplerHarnessExtension.class);
    private static final String HARNESS_KEY = "harness";

    private final Supplier<Sampler> samplerFactory;

    private SamplerHarnessExtension(Supplier<Sampler> samplerFactory) {
        this.samplerFactory = samplerFactory;
    }

    /**
     * Creates an extension that supplies a fresh {@link SamplerHarness} before each test
     * by invoking {@code samplerFactory.get()}.
     *
     * <p>Use a constant supplier ({@code () -> SHARED_SAMPLER}) when the sampler is
     * stateless and you want to avoid reconstruction overhead.
     *
     * @param samplerFactory supplier invoked once per test; must not be {@code null}
     * @return a new extension instance
     */
    public static SamplerHarnessExtension of(Supplier<Sampler> samplerFactory) {
        Objects.requireNonNull(samplerFactory, "samplerFactory must not be null");
        return new SamplerHarnessExtension(samplerFactory);
    }

    // -------------------------------------------------------------------------
    // BeforeEachCallback — fresh harness per test
    // -------------------------------------------------------------------------

    @Override
    public void beforeEach(ExtensionContext context) {
        SamplerHarness harness = SamplerHarness.of(samplerFactory.get());
        context.getStore(NS).put(HARNESS_KEY, harness);
    }

    // -------------------------------------------------------------------------
    // AfterEachCallback — no resources to release
    // -------------------------------------------------------------------------

    @Override
    public void afterEach(ExtensionContext context) {
        context.getStore(NS).remove(HARNESS_KEY, SamplerHarness.class);
    }

    // -------------------------------------------------------------------------
    // ParameterResolver — inject SamplerHarness into test methods
    // -------------------------------------------------------------------------

    @Override
    public boolean supportsParameter(ParameterContext parameterContext,
                                     ExtensionContext extensionContext) {
        return SamplerHarness.class.equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext,
                                   ExtensionContext extensionContext) {
        return harness(extensionContext);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private SamplerHarness harness(ExtensionContext context) {
        SamplerHarness h = context.getStore(NS).get(HARNESS_KEY, SamplerHarness.class);
        if (h == null) {
            throw new IllegalStateException(
                    "SamplerHarness not found in JUnit Store — " +
                    "ensure SamplerHarnessExtension is declared as " +
                    "@RegisterExtension (static or instance).");
        }
        return h;
    }
}
