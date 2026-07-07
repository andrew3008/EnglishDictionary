package space.br1440.platform.tracing.otel.extension.sampler;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig;
import space.br1440.platform.tracing.otel.extension.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.extension.factory.PlatformSamplerFactory;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingObjectNames;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-1 (Фаза 15): named {@code ConfigurableSamplerProvider} ({@code otel.traces.sampler=platform})
 * и idempotency-guard inline-customizer'а через маркер {@link PlatformManagedSampler}.
 */
@DisplayName("PlatformSamplerProvider + idempotency-guard")
class PlatformSamplerProviderTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanupMBean() throws Exception {
        for (ObjectName name : new ObjectName[]{
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS
        }) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    private static ConfigProperties config(Map<String, String> map) {
        return DefaultConfigProperties.createFromMap(map);
    }

    /**
     * PR-5: helper to build SamplingExtensionConfig from a map, mirroring the bootstrap chain.
     * Used instead of raw ConfigProperties when calling PlatformSamplerFactory.buildSampler.
     */
    private static SamplingExtensionConfig sampling(Map<String, String> map) {
        return new ExtensionConfig(config(map)).sampling();
    }

    @Test
    @DisplayName("createSampler строит платформенный CompositeSampler (тот же builder, что и inline)")
    void createSampler_builds_composite() {
        Sampler sampler = new PlatformSamplerProvider().createSampler(
                config(Map.of("platform.tracing.sampling.ratio", "0.42")));

        assertThat(sampler).isInstanceOf(SafeSampler.class);
        assertThat(sampler).isInstanceOf(PlatformManagedSampler.class);
        assertThat(sampler.getDescription())
                .contains("PlatformRuleBasedSampler")
                .contains("defaultRatio=0.42");
        // Маркер даёт доступ к внутреннему композиту (для JMX-перепривязки).
        assertThat(((PlatformManagedSampler) sampler).platformCompositeSampler()).isNotNull();
    }

    @Test
    @DisplayName("getName == platform → активирует otel.traces.sampler=platform")
    void getName_is_platform() {
        assertThat(new PlatformSamplerProvider().getName()).isEqualTo("platform");
    }

    @Test
    @DisplayName("inline-customizer идемпотентен: existing уже платформенный → нет повторной обёртки")
    void inline_customizer_idempotent_when_existing_is_platform() {
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        PlatformSamplerFactory factory = new PlatformSamplerFactory(registrar);

        // Эмулируем named-провайдер: SDK отдал платформенный sampler как existing.
        Sampler platform = new PlatformSamplerProvider().createSampler(config(Map.of()));
        Sampler result = factory.buildSampler(platform, sampling(Map.of()));

        // Idempotency-guard: возвращается тот же экземпляр без повторной SafeSampler-обёртки.
        assertThat(result).isSameAs(platform);
    }

    @Test
    @DisplayName("регресс: повторная autoconfigure-сборка в одном JVM снова поднимает платформенный sampler")
    void idempotent_double_build_does_not_skip() {
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();
        PlatformSamplerFactory factory = new PlatformSamplerFactory(registrar);

        // Дважды строим поверх не-платформенного existing — защита против статического флага «уже применён».
        Sampler first = factory.buildSampler(Sampler.alwaysOn(), sampling(Map.of()));
        Sampler second = factory.buildSampler(Sampler.alwaysOn(), sampling(Map.of()));

        assertThat(first).isInstanceOf(SafeSampler.class);
        assertThat(second).isInstanceOf(SafeSampler.class);
        assertThat(first.getDescription()).contains("PlatformRuleBasedSampler");
        assertThat(second.getDescription()).contains("PlatformRuleBasedSampler");
    }
}
