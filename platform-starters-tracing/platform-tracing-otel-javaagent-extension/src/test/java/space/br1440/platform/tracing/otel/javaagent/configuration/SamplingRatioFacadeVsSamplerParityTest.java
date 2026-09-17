package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformManagedSampler;
import space.br1440.platform.tracing.otel.javaagent.sampler.PlatformSamplerBuilder;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.samplers.Sampler;

class SamplingRatioFacadeVsSamplerParityTest {

    @Test
    void absentRatioResolvesToTheSameDefaultInFacadeAndSampler() {
        // given/when
        double facadeRatio = new ExtensionConfig(emptyConfig()).sampling().ratio();
        double samplerRatio = samplerRatio(emptyConfig());

        // then
        assertThat(facadeRatio).isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO);
        assertThat(samplerRatio).isEqualTo(facadeRatio);
    }

    @Test
    void blankRatioResolvesToTheExtensionDefaultInSampler() {
        // given/when
        double samplerRatio = samplerRatio(stringConfig(ExtensionPropertyNames.SAMPLING_RATIO, "  "));

        // then
        assertThat(samplerRatio).isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO);
    }

    @Test
    void explicitRatioIsAdoptedByFacadeAndSampler() {
        // given/when
        double facadeRatio = new ExtensionConfig(doubleConfig(ExtensionPropertyNames.SAMPLING_RATIO, 0.5d))
                .sampling().ratio();
        double samplerRatio = samplerRatio(doubleConfig(ExtensionPropertyNames.SAMPLING_RATIO, 0.5d));

        // then
        assertThat(facadeRatio).isEqualTo(0.5d);
        assertThat(samplerRatio).isEqualTo(0.5d);
    }

    private static double samplerRatio(ConfigProperties config) {
        Sampler sampler = PlatformSamplerBuilder.build(new ExtensionConfig(config).sampling());
        CompositeSampler composite = ((PlatformManagedSampler) sampler).platformCompositeSampler();
        return composite.acceptedPolicy().current().samplingRatio();
    }

    private static ConfigProperties emptyConfig() {
        return new ConfigProperties() {
            @Override public String getString(String name) { return null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties stringConfig(String key, String value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return key.equals(name) ? value : null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }

    private static ConfigProperties doubleConfig(String key, double value) {
        return new ConfigProperties() {
            @Override public String getString(String name) { return key.equals(name) ? String.valueOf(value) : null; }
            @Override public Boolean getBoolean(String name) { return null; }
            @Override public Integer getInt(String name) { return null; }
            @Override public Long getLong(String name) { return null; }
            @Override public Double getDouble(String name) { return key.equals(name) ? value : null; }
            @Override public Duration getDuration(String name) { return null; }
            @Override public List<String> getList(String name) { return null; }
            @Override public Map<String, String> getMap(String name) { return null; }
        };
    }
}
