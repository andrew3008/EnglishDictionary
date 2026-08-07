package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;

@UtilityClass
public final class PlatformSamplerBuilder {

    public static Sampler build(SamplingExtensionConfig sampling) {
        AcceptedPolicyAccess policyAccess = AcceptedPolicyAccess.fromSampling(sampling);
        CompositeSampler compositeSampler = new CompositeSampler(policyAccess);
        return new SafeSampler(compositeSampler);
    }
}
