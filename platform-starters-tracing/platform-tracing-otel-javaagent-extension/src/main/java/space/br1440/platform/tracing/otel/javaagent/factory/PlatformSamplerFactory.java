package space.br1440.platform.tracing.otel.javaagent.factory;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformManagedSamplers;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformSamplerBuilder;

import io.opentelemetry.sdk.trace.samplers.Sampler;

public final class PlatformSamplerFactory {

    private final DomainMBeanRegistrar jmxRegistrar;

    public PlatformSamplerFactory(DomainMBeanRegistrar jmxRegistrar) {
        this.jmxRegistrar = jmxRegistrar;
    }

    public Sampler buildSampler(Sampler existing,
                                SamplingExtensionConfig sampling,
                                TracingSettingsSource acceptedPolicy) {
        CompositeSampler existingComposite = PlatformManagedSamplers.findComposite(existing);
        if (existingComposite != null) {
            existingComposite.bindAcceptedPolicy(acceptedPolicy);
            registerForJmx(existingComposite);
            return existing;
        }

        if (PlatformManagedSamplers.isPlatformManaged(existing)) {
            return existing;
        }

        Sampler built = PlatformSamplerBuilder.build(sampling);
        CompositeSampler composite = PlatformManagedSamplers.findComposite(built);
        if (composite != null) {
            composite.bindAcceptedPolicy(acceptedPolicy);
            registerForJmx(composite);
        }

        return built;
    }

    private void registerForJmx(CompositeSampler composite) {
        jmxRegistrar.setAcceptedPolicy(composite.acceptedPolicy());
        jmxRegistrar.setCompositeSampler(composite);
    }
}
