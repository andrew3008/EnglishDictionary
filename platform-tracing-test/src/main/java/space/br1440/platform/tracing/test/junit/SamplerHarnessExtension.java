package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.function.Supplier;

/**
 * Thin optional JUnit 5 Extension wrapping {@link SamplerHarness}.
 *
 * <p>Provides {@code @RegisterExtension} syntax and automatic per-test harness reset:
 *
 * <pre>{@code
 * @RegisterExtension
 * static final SamplerHarnessExtension harness =
 *         SamplerHarnessExtension.of(() -> buildCompositeSampler());
 *
 * @Test
 * void forceTrace(SamplerHarness h) {
 *     h.withHeader("X-Trace-On", "on").sample("test").assertSampled();
 * }
 * }</pre>
 *
 * <p>If the sampler is stateless this extension is optional; you can create
 * {@link SamplerHarness} directly with {@code new SamplerHarness(sampler)}.
 *
 * @since 5.3
 */
public final class SamplerHarnessExtension implements BeforeEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SamplerHarnessExtension.class);
    private static final String HARNESS_KEY = "harness";

    private final Supplier<Sampler> samplerFactory;

    private SamplerHarnessExtension(Supplier<Sampler> samplerFactory) {
        this.samplerFactory = samplerFactory;
    }

    /**
     * Creates an extension whose underlying {@link SamplerHarness} is built (once) from
     * the supplied factory on the first {@code beforeEach} call.
     *
     * @param samplerFactory supplier invoked once per test class to obtain the sampler
     * @return a new extension instance
     */
    public static SamplerHarnessExtension of(Supplier<Sampler> samplerFactory) {
        if (samplerFactory == null) throw new IllegalArgumentException("samplerFactory must not be null");
        return new SamplerHarnessExtension(samplerFactory);
    }

    // -------------------------------------------------------------------------
    // BeforeEachCallback
    // -------------------------------------------------------------------------

    @Override
    public void beforeEach(ExtensionContext context) {
        harness(context).reset();
    }

    // -------------------------------------------------------------------------
    // ParameterResolver
    // -------------------------------------------------------------------------

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return SamplerHarness.class.equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return harness(extensionContext);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private SamplerHarness harness(ExtensionContext context) {
        return context.getStore(NS).getOrComputeIfAbsent(
                HARNESS_KEY,
                key -> new SamplerHarness(samplerFactory.get()),
                SamplerHarness.class
        );
    }
}
