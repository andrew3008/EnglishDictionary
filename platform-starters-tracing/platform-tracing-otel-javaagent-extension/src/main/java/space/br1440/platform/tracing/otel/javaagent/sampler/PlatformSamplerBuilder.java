package space.br1440.platform.tracing.otel.javaagent.sampler;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import java.util.HashSet;
import java.util.Map;

import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.experimental.UtilityClass;

import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.sampling.model.SamplingPolicySnapshot;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicyProperties;
import space.br1440.platform.tracing.otel.sampling.properties.SamplingPolicySnapshotFactory;

@UtilityClass
public final class PlatformSamplerBuilder {

    public static Sampler build(SamplingExtensionConfig sampling) {
        TracingSettingsSource policyAccess = TracingSettingsSource.fromSampling(sampling);
        CompositeSampler compositeSampler = new CompositeSampler(policyAccess, categoryBOverrides(sampling));
        return new SafeSampler(compositeSampler);
    }

    /**
     * Category B (drop-paths, force-record-values) больше не control-plane состояние: с S2 оно
     * фиксируется один раз из startup-конфигурации и передаётся в {@link CompositeSampler}
     * напрямую, минуя {@code control}.
     */
    private static SamplingPolicySnapshot categoryBOverrides(SamplingExtensionConfig sampling) {
        return SamplingPolicySnapshotFactory.create(new SamplingPolicyProperties(
                true,
                1.0d,
                sampling.dropPaths(),
                new HashSet<>(sampling.forceRecordValues()),
                Map.of()));
    }
}
