package space.br1440.platform.tracing.otel.javaagent.sampler;

import java.util.HashSet;
import java.util.Map;

import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.runtime.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.runtime.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.runtime.sampling.properties.SamplingPolicySnapshotFactory;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PlatformSamplerBuilder {

    public static Sampler build(SamplingExtensionConfig sampling) {
        TracingSettingsSource policyAccess = TracingSettingsSource.isolatedSampling(sampling);
        CompositeSampler compositeSampler = new CompositeSampler(policyAccess, categoryBOverrides(sampling));
        return new SafeSampler(compositeSampler);
    }

    private static SamplingPolicySnapshot categoryBOverrides(SamplingExtensionConfig sampling) {
        return SamplingPolicySnapshotFactory.create(new SamplingPolicyProperties(
                true,
                1.0d,
                sampling.dropPaths(),
                new HashSet<>(sampling.forceRecordValues()),
                Map.of())
        );
    }
}
