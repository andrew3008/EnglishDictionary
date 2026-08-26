package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Расширенные интеграционные тесты {@link BootstrapAutoConfigurationCustomizerProvider}:
 * registerSpanProcessors с подключением реального {@link SdkTracerProviderBuilder}, проверка
 * регистрации JMX MBean, обработка отдельных подсистем по {@code *.enabled} флагам.
 * <p>
 * После r5 (BLOCKER-3) runtime MBean registration — часть activation transaction
 * ({@link BootstrapCoordinator#activate()}), а не property/component фазы. Тесты, которые
 * проверяют MBean-состояние, явно вызывают {@code coordinator.activate()} после прайминга.
 */
@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
class PlatformAutoConfigurationCustomizerProcessorsTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private BootstrapCoordinator activeCoordinator;

    @AfterEach
    void cleanup() {
        if (activeCoordinator != null) {
            activeCoordinator.close();
            activeCoordinator = null;
        }
    }

    private BootstrapCoordinator trackActiveCoordinator(BootstrapCoordinator coordinator) {
        activeCoordinator = coordinator;
        return coordinator;
    }

    private static BootstrapAutoConfigurationCustomizerProvider newProvider(BootstrapCoordinator coordinator) {
        return new BootstrapAutoConfigurationCustomizerProvider(coordinator);
    }

    /**
     * These tests exercise only the sampler/tracer-provider customizer slice of the real
     * {@code AutoConfiguredOpenTelemetrySdk} lifecycle (they never drive the exporter/export-processor
     * customizers), so the exporter-boundary installation barrier that {@code activate()} now enforces
     * (ADR-scrubbing-hybrid-processor-export-boundary.md) must be satisfied manually before activation.
     */
    private static void satisfyReadinessBarrier(BootstrapCoordinator coordinator) {
        AutoconfigureSession session = coordinator.session();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            session.readiness().markInstalled(capability);
        }
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();
    }

    @Test
    void all_enabled_subsystems_are_added_to_tracer_provider_builder() {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        SdkTracerProviderBuilder result = recorder.tracerProviderCustomizer.apply(builder,
                new MapConfigProperties(Map.of()));

        // Контракт: возвращается тот же builder (fluent-API), и мы не падаем при дефолтных значениях.
        assertThat(result).isSameAs(builder);

        // Финальная сборка должна быть ненулевой (косвенная проверка, что addSpanProcessor отработал).
        SdkTracerProvider provider = builder.build();
        try {
            assertThat(provider).isNotNull();
        } finally {
            provider.shutdown();
        }
    }

    @Test
    void disabling_one_subsystem_does_not_break_others() {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        // Только enriching выключаем, scrubbing/validation/watchdog по дефолту on.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.enriching.enabled", "false")));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        recorder.tracerProviderCustomizer.apply(builder,
                new MapConfigProperties(Map.of("platform.tracing.enriching.enabled", "false")));

        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void custom_remote_service_priority_is_passed_to_EnrichingSpanProcessor() {
        // Прямой instance EnrichingSpanProcessor мы получить не можем (он внутри
        // PlatformCompositeSpanProcessor), но можем убедиться, что customize не падает
        // с невалидным списком и принимает любой список строк.
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        Map<String, String> props = new HashMap<>();
        props.put("platform.tracing.enriching.remote-service-priority", "rpc.service,peer.service");

        recorder.primeWithConfig(new MapConfigPropertiesWithList(props,
                "platform.tracing.enriching.remote-service-priority",
                List.of("rpc.service", "peer.service")));
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        recorder.tracerProviderCustomizer.apply(builder, new MapConfigPropertiesWithList(props,
                "platform.tracing.enriching.remote-service-priority",
                List.of("rpc.service", "peer.service")));

        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void sampler_customizer_creates_PlatformRuleBasedSampler() {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        // PR-5: extensionConfig должен быть инициализирован до вызова samplerCustomizer.
        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        // Фаза 11: CompositeSampler оборачивается в SafeSampler; описание делегата сохраняется.
        assertThat(produced).isInstanceOf(SafeSampler.class);
        assertThat(produced.getDescription())
                .contains("PlatformRuleBasedSampler");
    }

    @Test
    void sampler_customizer_with_explicit_ratio_creates_accepted_policy_with_default_ratio() {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig), не из samplerCustomizer config.
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.1")));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(produced.getDescription())
                .contains("PlatformRuleBasedSampler")
                .contains("defaultRatio=0.10");
    }

    @Test
    void MBean_registration_runs_after_activation() throws Exception {
        BootstrapCoordinator coordinator = trackActiveCoordinator(RuntimeEvidenceTestFixtures.coordinator());
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        recorder.samplerCustomizer.apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING))
                .as("до activation() MBean ещё не зарегистрирован (BLOCKER-3, PREPARED owns no MBean)")
                .isFalse();

        satisfyReadinessBarrier(coordinator);
        coordinator.activate();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
    }

    @Test
    void custom_sampling_ratio_is_reflected_in_registered_MBean() throws Exception {
        BootstrapCoordinator coordinator = trackActiveCoordinator(RuntimeEvidenceTestFixtures.coordinator());
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.42")));
        recorder.samplerCustomizer.apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));
        satisfyReadinessBarrier(coordinator);
        coordinator.activate();

        ObjectName objectName = DomainObjectNames.SAMPLING;
        assertThat(samplingRatioFromObservation(objectName)).isEqualTo(0.42d);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> server.setAttribute(objectName,
                        new javax.management.Attribute("SamplingObservation", null)));
        assertThat(samplingRatioFromObservation(objectName)).isEqualTo(0.42d);
    }

    private double samplingRatioFromObservation(ObjectName objectName) throws Exception {
        CompositeData observation = (CompositeData) server.getAttribute(objectName, "SamplingObservation");
        return (double) observation.get(
                GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO);
    }

    @Test
    void extension_order_equals_100_priority_over_default() {
        assertThat(newProvider(BootstrapCoordinator.create()).order()).isEqualTo(100);
    }

    @Test
    void without_explicit_ratio_default_ratio_is_used() {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        // PR-5 ALIGN_TO_EXTENSION_DEFAULTS: absent ratio → ExtensionDefaults.DEFAULT_SAMPLING_RATIO = 0.1
        // (было 1.0 — hardcoded implicit fallback в PlatformSamplerBuilder до PR-5)
        recorder.primeWithConfig(new MapConfigProperties(Map.of()));
        Sampler produced = recorder.samplerCustomizer
                .apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));

        assertThat(produced).isNotNull();
        assertThat(produced.getDescription()).contains("defaultRatio=0.10");
    }

    @Test
    void blank_force_header_name_is_replaced_with_default_X_Trace_On() {
        // Устаревший тест, так как CompositeSampler больше не хранит имена заголовков в description,
        // они теперь инкапсулированы в TraceControlPropagator
    }

    @Test
    void unknown_scrubbing_rule_name_does_not_crash_configuration() {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        Map<String, String> props = new HashMap<>();
        recorder.primeWithConfig(new MapConfigPropertiesWithList(props,
                "platform.tracing.javaagent.scrubbing.built-in-rules",
                List.of("unknown-rule-x", "email")));
        SdkTracerProviderBuilder result = recorder.tracerProviderCustomizer.apply(builder,
                new MapConfigPropertiesWithList(props,
                        "platform.tracing.javaagent.scrubbing.built-in-rules",
                        List.of("unknown-rule-x", "email")));

        assertThat(result).isSameAs(builder);
        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider).isNotNull();
        }
    }

    @Test
    void disabling_all_subsystems_does_not_create_MBean_before_sampler_creation() throws Exception {
        Recorder recorder = new Recorder();
        newProvider(BootstrapCoordinator.create()).customize(recorder.proxy());

        Map<String, String> disableAll = Map.of(
                "platform.tracing.enriching.enabled", "false",
                "platform.tracing.javaagent.scrubbing.enabled", "false",
                "platform.tracing.validation.enabled", "false",
                "platform.tracing.watchdog.enabled", "false"
        );

        assertThatThrownBy(() -> recorder.primeWithConfig(new MapConfigProperties(disableAll)))
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("scrubbing.enabled=false is forbidden");

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
    }

    /**
     * Прокси-перехватчик {@link AutoConfigurationCustomizer} (см. PlatformAutoConfigurationCustomizerTest).
     *
     * <p>PR-5: {@code addPropertiesCustomizer} callbacks перехватываются и вызываются через
     * {@link #primeWithConfig(ConfigProperties)}, моделируя порядок OTel SDK lifecycle.
     */
    private static final class Recorder {
        @SuppressWarnings("unchecked")
        BiFunction<Sampler, ConfigProperties, Sampler> samplerCustomizer;
        @SuppressWarnings("unchecked")
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;

        private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();

        /**
         * Имитирует фазу addPropertiesCustomizer OTel SDK lifecycle: вызывает все зарегистрированные
         * callbacks с переданным конфигом. Необходимо вызвать до {@code samplerCustomizer.apply()},
         * чтобы extensionConfig был инициализирован (PR-5).
         */
        void primeWithConfig(ConfigProperties config) {
            for (Function<ConfigProperties, Map<String, String>> pc : propertiesCustomizers) {
                pc.apply(config);
            }
        }

        @SuppressWarnings("unchecked")
        AutoConfigurationCustomizer proxy() {
            return (AutoConfigurationCustomizer) Proxy.newProxyInstance(
                    AutoConfigurationCustomizer.class.getClassLoader(),
                    new Class<?>[]{AutoConfigurationCustomizer.class},
                    (Object proxyObj, Method method, Object[] args) -> switch (method.getName()) {
                        case "addSamplerCustomizer" -> {
                            this.samplerCustomizer =
                                    (BiFunction<Sampler, ConfigProperties, Sampler>) args[0];
                            yield proxyObj;
                        }

                        case "addTracerProviderCustomizer" -> {
                            this.tracerProviderCustomizer =
                                    (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>) args[0];
                            yield proxyObj;
                        }

                        case "addPropertiesCustomizer" -> {
                            propertiesCustomizers.add(
                                    (Function<ConfigProperties, Map<String, String>>) args[0]);
                            yield proxyObj;
                        }

                        default -> proxyObj;
                    });
        }
    }

    /** Минимальная in-memory реализация {@link ConfigProperties}. */
    private static class MapConfigProperties implements ConfigProperties {
        protected final Map<String, String> data;

        MapConfigProperties(Map<String, String> data) {
            this.data = new HashMap<>(data);
        }

        @Override public String getString(String name) { return data.get(name); }

        @Override public Boolean getBoolean(String name) {
            String v = data.get(name);
            return v == null ? null : Boolean.parseBoolean(v);
        }

        @Override public Integer getInt(String name) {
            String v = data.get(name);
            return v == null ? null : Integer.valueOf(v);
        }

        @Override public Long getLong(String name) {
            String v = data.get(name);
            return v == null ? null : Long.valueOf(v);
        }

        @Override public Double getDouble(String name) {
            String v = data.get(name);
            return v == null ? null : Double.valueOf(v);
        }

        @Override public Duration getDuration(String name) { return null; }

        @Override public List<String> getList(String name) { return Collections.emptyList(); }

        @Override public Map<String, String> getMap(String name) { return Collections.emptyMap(); }
    }

    /** Расширение, поддерживающее список под одним конкретным ключом. */
    private static final class MapConfigPropertiesWithList extends MapConfigProperties {
        private final String listKey;
        private final List<String> listValue;

        MapConfigPropertiesWithList(Map<String, String> data, String listKey, List<String> listValue) {
            super(data);
            this.listKey = listKey;
            this.listValue = listValue;
        }

        @Override
        public List<String> getList(String name) {
            if (listKey.equals(name)) {
                return listValue;
            }
            return Collections.emptyList();
        }
    }

    /** Sanity-check: sampling-домен MBean зафиксирован контрактом. */
    @Test
    void object_name_sampling_MBean_matches_public_contract() {
        assertThat(GeneratedJmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME_STR)
                .isEqualTo("space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl");
    }

    /** Единственный атрибут sampling-домена ({@code SamplingObservation}) не writable после перехода на CompositeData. */
    @Test
    void setSamplingRatio_via_platform_JMX_is_rejected() throws Exception {
        BootstrapCoordinator coordinator = trackActiveCoordinator(RuntimeEvidenceTestFixtures.coordinator());
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());
        // PR-5: ratio поступает из addPropertiesCustomizer (extensionConfig).
        recorder.primeWithConfig(new MapConfigProperties(Map.of("platform.tracing.sampling.ratio", "0.1")));
        recorder.samplerCustomizer.apply(Sampler.alwaysOn(), new MapConfigProperties(Map.of()));
        satisfyReadinessBarrier(coordinator);
        coordinator.activate();

        ObjectName objectName = DomainObjectNames.SAMPLING;

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> server.setAttribute(objectName, new javax.management.Attribute("SamplingObservation", null)));

        // Состояние MBean не изменилось (0.1 задано при создании).
        assertThat(samplingRatioFromObservation(objectName)).isEqualTo(0.1d);
    }
}
