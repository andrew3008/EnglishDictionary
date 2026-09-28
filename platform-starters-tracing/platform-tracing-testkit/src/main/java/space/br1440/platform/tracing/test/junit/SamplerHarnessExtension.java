package space.br1440.platform.tracing.test.junit;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import space.br1440.platform.tracing.test.harness.SamplerHarness;

import java.util.Objects;
import java.util.function.Supplier;

public final class SamplerHarnessExtension implements BeforeEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(SamplerHarnessExtension.class);
    private static final String HARNESS_KEY = "harness";

    private final Supplier<Sampler> samplerFactory;

    private SamplerHarnessExtension(Supplier<Sampler> samplerFactory) {
        this.samplerFactory = samplerFactory;
    }

    public static SamplerHarnessExtension of(Supplier<Sampler> samplerFactory) {
        Objects.requireNonNull(samplerFactory, "samplerFactory");
        return new SamplerHarnessExtension(samplerFactory);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        harness(context).reset();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return SamplerHarness.class.equals(parameterContext.getParameter().getType());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return harness(extensionContext);
    }

    private SamplerHarness harness(ExtensionContext context) {
        return context.getStore(NS).getOrComputeIfAbsent(
                HARNESS_KEY,
                key -> new SamplerHarness(Objects.requireNonNull(samplerFactory.get(), "sampler")),
                SamplerHarness.class
        );
    }
}
