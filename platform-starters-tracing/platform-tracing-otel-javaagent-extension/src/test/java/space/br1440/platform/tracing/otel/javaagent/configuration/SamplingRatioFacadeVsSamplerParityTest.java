package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSessionFactory;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionRuntimeEvidence;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly.PlatformScrubberFactory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;

class SamplingRatioFacadeVsSamplerParityTest {

    @Test
    void absentRatioResolvesToTheSameDefaultInFacadeAndSessionSampler() {
        // given
        ConfigProperties config = config(Map.of());

        // when
        double facadeRatio = new ExtensionConfig(config).sampling().ratio();
        double samplerRatio = sessionSamplerRatio(config);

        // then
        assertThat(facadeRatio).isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO);
        assertThat(samplerRatio).isEqualTo(facadeRatio);
    }

    @Test
    void emptyRatioResolvesToTheSameDefaultInFacadeAndSessionSampler() {
        // given
        ConfigProperties config = config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, ""));

        // when
        double facadeRatio = new ExtensionConfig(config).sampling().ratio();
        double samplerRatio = sessionSamplerRatio(config);

        // then
        assertThat(facadeRatio).isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO);
        assertThat(samplerRatio).isEqualTo(facadeRatio);
    }

    @Test
    void explicitRatioIsAdoptedByFacadeAndSessionSampler() {
        // given
        ConfigProperties config = config(Map.of(ExtensionPropertyNames.SAMPLING_RATIO, "0.5"));

        // when
        double facadeRatio = new ExtensionConfig(config).sampling().ratio();
        double samplerRatio = sessionSamplerRatio(config);

        // then
        assertThat(facadeRatio).isEqualTo(0.5d);
        assertThat(samplerRatio).isEqualTo(facadeRatio);
    }

    private static ConfigProperties config(Map<String, String> values) {
        Map<String, String> properties = new HashMap<>(values);
        properties.put("otel.traces.sampler", "always_off");
        return DefaultConfigProperties.createFromMap(properties);
    }

    private static double sessionSamplerRatio(ConfigProperties config) {
        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        AutoconfigureSession session = new AutoconfigureSessionFactory(
                new PlatformScrubberFactory(),
                AutoconfigureSessionFactory::currentProcessRemoteJmxAssessor
        ).create(
                config,
                new PlatformTracingMBeanRegistrar(agentInstanceId),
                agentInstanceId,
                evidence()
        );
        return delegateOf(session.sampler()).acceptedPolicy().current().samplingRatio();
    }

    private static CompositeSampler delegateOf(SafeSampler sampler) {
        try {
            Field delegate = SafeSampler.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            return (CompositeSampler) delegate.get(sampler);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("SafeSampler delegate is not inspectable", failure);
        }
    }

    private static ExtensionRuntimeEvidence evidence() {
        return new ExtensionRuntimeEvidence(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "2.32.0",
                "test-extension",
                "extension-loader",
                "sdk-loader"
        );
    }
}
