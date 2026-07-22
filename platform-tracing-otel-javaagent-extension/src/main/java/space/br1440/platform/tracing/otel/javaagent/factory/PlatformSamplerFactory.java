package space.br1440.platform.tracing.otel.javaagent.factory;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformManagedSamplers;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformSamplerBuilder;

public final class PlatformSamplerFactory {

    private final PlatformTracingJmxRegistrar jmxRegistrar;

    public PlatformSamplerFactory(PlatformTracingJmxRegistrar jmxRegistrar) {
        this.jmxRegistrar = jmxRegistrar;
    }

    public Sampler buildSampler(Sampler existing, SamplingExtensionConfig sampling) {
        CompositeSampler existingComposite = PlatformManagedSamplers.findComposite(existing);
        if (existingComposite != null) {
            registerForJmx(existingComposite);
            return existing;
        }

        if (PlatformManagedSamplers.isPlatformManaged(existing)) {
            return existing;
        }

        Sampler built = PlatformSamplerBuilder.build(sampling);
        CompositeSampler composite = PlatformManagedSamplers.findComposite(built);
        if (composite != null) {
            registerForJmx(composite);
        }

        return built;
    }

    private void registerForJmx(CompositeSampler composite) {
        jmxRegistrar.setConfigHolder(composite.stateHolder());
        jmxRegistrar.setCompositeSampler(composite);
    }
}
