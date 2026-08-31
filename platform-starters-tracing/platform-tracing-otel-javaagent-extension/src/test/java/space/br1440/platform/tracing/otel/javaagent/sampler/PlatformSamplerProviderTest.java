package space.br1440.platform.tracing.otel.javaagent.sampler;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.otel.javaagent.configuration.SamplingExtensionConfig;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.factory.PlatformSamplerFactory;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultInboundTraceControlExtractor;
import space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;

@DisplayName("PlatformSamplerProvider + idempotency-guard")
class PlatformSamplerProviderTest {

    private static ConfigProperties config(Map<String, String> map) {
        return DefaultConfigProperties.createFromMap(map);
    }

    private static SamplingExtensionConfig sampling(Map<String, String> map) {
        return SamplingExtensionConfig.from(config(map));
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
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
        PlatformSamplerFactory factory = new PlatformSamplerFactory(registrar);

        Sampler platform = new PlatformSamplerProvider().createSampler(config(Map.of()));
        SamplingExtensionConfig sampling = sampling(Map.of());
        TracingSettingsSource compositionPolicy = TracingSettingsSource.fromSampling(sampling);
        Sampler result = factory.buildSampler(
                platform,
                sampling,
                compositionPolicy);

        assertThat(result).isSameAs(platform);
        assertThat(((PlatformManagedSampler) result).platformCompositeSampler().acceptedPolicy())
                .isSameAs(compositionPolicy);
    }

    @Test
    @DisplayName("absent force-record-values keeps default ['on'] for named platform sampler")
    void absent_force_record_values_uses_default_on() {
        Sampler sampler = new PlatformSamplerProvider().createSampler(
                config(Map.of("platform.tracing.sampling.ratio", "0")));
        Context forceContext = Context.root().with(
                PlatformTraceContextKeys.TRACE_CONTROL,
                new DefaultInboundTraceControlExtractor().fromHeaders("on", null, null));

        var result = sampler.shouldSample(
                forceContext, "00000000000000000000000000000001", "GET", SpanKind.SERVER,
                Attributes.empty(), List.<LinkData>of());

        assertThat(result.getDecision()).isEqualTo(SamplingDecision.RECORD_AND_SAMPLE);
        assertThat(result.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey(
                "platform.sampling.reason"))).isEqualTo(PlatformSamplingReasons.FORCE_HEADER);
    }

    @Test
    @DisplayName("регресс: повторная autoconfigure-сборка в одном JVM снова поднимает платформенный sampler")
    void idempotent_double_build_does_not_skip() {
        PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar();
        PlatformSamplerFactory factory = new PlatformSamplerFactory(registrar);

        // Дважды строим поверх не-платформенного existing — защита против статического флага «уже применён».
        SamplingExtensionConfig sampling = sampling(Map.of());
        Sampler first = factory.buildSampler(
                Sampler.alwaysOn(),
                sampling,
                TracingSettingsSource.fromSampling(sampling));
        Sampler second = factory.buildSampler(
                Sampler.alwaysOn(),
                sampling,
                TracingSettingsSource.fromSampling(sampling));

        assertThat(first).isInstanceOf(SafeSampler.class);
        assertThat(second).isInstanceOf(SafeSampler.class);
        assertThat(first.getDescription()).contains("PlatformRuleBasedSampler");
        assertThat(second.getDescription()).contains("PlatformRuleBasedSampler");
    }
}
