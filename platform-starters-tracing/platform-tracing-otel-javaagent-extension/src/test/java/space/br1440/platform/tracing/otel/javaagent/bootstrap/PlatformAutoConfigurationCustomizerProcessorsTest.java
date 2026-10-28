package space.br1440.platform.tracing.otel.javaagent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.management.Attribute;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import space.br1440.platform.tracing.otel.javaagent.AutoConfiguredSdkIsolationExtension;
import space.br1440.platform.tracing.otel.javaagent.bootstrap.session.AutoconfigureSession;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionDefaults;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.sampler.SafeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;

@ExtendWith(AutoConfiguredSdkIsolationExtension.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class PlatformAutoConfigurationCustomizerProcessorsTest {

    private final MBeanServer server = MBeanServerFactory.newMBeanServer();
    private BootstrapCoordinator ownedCoordinator;

    @AfterEach
    void cleanup() {
        if (ownedCoordinator != null) {
            ownedCoordinator.close();
            ownedCoordinator = null;
        }
    }

    private BootstrapAutoConfigurationCustomizerProvider newProvider(BootstrapCoordinator coordinator) {
        ownedCoordinator = coordinator;
        return new BootstrapAutoConfigurationCustomizerProvider(coordinator);
    }

    private BootstrapCoordinator coordinator() {
        return RuntimeEvidenceTestFixtures.coordinator(server);
    }

    private static void satisfyReadinessBarrier(BootstrapCoordinator coordinator) {
        AutoconfigureSession session = coordinator.session();
        for (PlatformExtensionCapability capability : PlatformExtensionCapability.values()) {
            if (capability != PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED) {
                session.readiness().markInstalled(capability);
            }
        }
        session.scrubbingRuntime().acknowledgeProcessorInstalled();
        session.scrubbingRuntime().acknowledgeExportModeResolved();
        session.scrubbingRuntime().acknowledgeExporterWrapped();
        session.scrubbingRuntime().acknowledgeExportPathProtected();
    }

    private static SdkTracerProvider buildProvider(Recorder recorder,
                                                   BootstrapCoordinator coordinator,
                                                   ConfigProperties config) {
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();
        SdkTracerProviderBuilder customized = recorder.tracerProviderCustomizer.apply(builder, config);
        assertThat(customized).isSameAs(builder);

        SdkTracerProvider provider = builder.build();
        SafeSampler sessionSampler = coordinator.session().sampler();
        if (provider.getSampler() != sessionSampler) {
            provider.close();
            throw new AssertionError("Tracer-provider customizer did not install the exact session sampler");
        }
        return provider;
    }

    @Test
    void all_enabled_subsystems_are_added_to_tracer_provider_builder() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        ConfigProperties config = recorder.primeWithConfig(Map.of());
        SdkTracerProviderBuilder builder = SdkTracerProvider.builder();

        // when
        SdkTracerProviderBuilder result = recorder.tracerProviderCustomizer.apply(builder, config);

        // then
        assertThat(result).isSameAs(builder);

        try (SdkTracerProvider provider = builder.build()) {
            assertThat(provider.getSampler()).isSameAs(coordinator.session().sampler());
        }
    }

    @Test
    void prepared_session_owns_safe_platform_sampler() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        recorder.primeWithConfig(Map.of());

        // when
        SafeSampler produced = coordinator.session().sampler();

        // then
        assertThat(produced).isExactlyInstanceOf(SafeSampler.class);
    }

    @Test
    void prepared_session_sampler_uses_explicit_startup_ratio() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        recorder.primeWithConfig(Map.of("platform.tracing.sampling.ratio", "0.1"));

        // when
        double actualRatio = coordinator.session().policy().current().samplingRatio();

        // then
        assertThat(actualRatio).isEqualTo(0.1d);
    }

    @Test
    void MBean_registration_runs_after_activation() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        ConfigProperties config = recorder.primeWithConfig(Map.of());

        try (SdkTracerProvider provider = buildProvider(recorder, coordinator, config)) {
            // then
            assertThat(server.isRegistered(DomainObjectNames.SAMPLING))
                    .as("до activation() runtime SAMPLING MBean ещё не зарегистрирован")
                    .isFalse();

            // when
            satisfyReadinessBarrier(coordinator);
            assertThat(coordinator.session().readiness().snapshot().installedCapabilities())
                    .doesNotContain(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
            coordinator.activate(provider);

            // then
            assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
            assertThat(coordinator.currentWire().capabilities())
                    .contains(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED.name());
        }
    }

    @Test
    void custom_sampling_ratio_is_reflected_in_registered_MBean() throws Exception {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        ConfigProperties config = recorder.primeWithConfig(
                Map.of("platform.tracing.sampling.ratio", "0.42")
        );

        try (SdkTracerProvider provider = buildProvider(recorder, coordinator, config)) {
            satisfyReadinessBarrier(coordinator);
            coordinator.activate(provider);

            ObjectName objectName = DomainObjectNames.SAMPLING;

            // then
            assertThat(samplingRatioFromObservation(objectName)).isEqualTo(0.42d);

            // when/then
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> server.setAttribute(objectName,
                            new javax.management.Attribute("SamplingObservation", null)));
            assertThat(samplingRatioFromObservation(objectName)).isEqualTo(0.42d);
        }
    }

    private double samplingRatioFromObservation(ObjectName objectName) throws Exception {
        CompositeData observation = (CompositeData) server.getAttribute(objectName, "SamplingObservation");
        return (double) observation.get(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO);
    }

    @Test
    void extension_order_equals_100_priority_over_default() {
        // given/when/then
        assertThat(newProvider(coordinator()).order()).isEqualTo(100);
    }

    @Test
    void without_explicit_ratio_default_ratio_is_used() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        recorder.primeWithConfig(Map.of());

        // when
        double actualRatio = coordinator.session().policy().current().samplingRatio();

        // then
        assertThat(actualRatio).isEqualTo(ExtensionDefaults.DEFAULT_SAMPLING_RATIO);
    }

    @Test
    void unknown_primary_scrubbing_rule_name_fails_configuration() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        // when
        // then
        assertThatThrownBy(() -> recorder.primeWithConfig(Map.of(
                "platform.tracing.javaagent.scrubbing.built-in-rules",
                "unknown-rule-x,email"
        )))
                .isInstanceOf(BootstrapException.class)
                .hasRootCauseInstanceOf(SanitizerBootstrapException.class)
                .hasStackTraceContaining("platform.tracing.javaagent.scrubbing.built-in-rules");
    }

    @Test
    void unknown_additional_scrubbing_rule_name_fails_configuration() {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        // when
        // then
        assertThatThrownBy(() -> recorder.primeWithConfig(Map.of(
                "platform.tracing.scrubbing.rules-config",
                "classpath:tracing/scrubbing-rules-with-unknown.properties"
        )))
                .isInstanceOf(BootstrapException.class)
                .hasRootCauseInstanceOf(SanitizerBootstrapException.class)
                .hasStackTraceContaining("platform.tracing.scrubbing.rules-config");
    }

    @Test
    void disabling_all_subsystems_does_not_create_MBean_during_failed_preparation() {
        // given
        Recorder recorder = new Recorder();
        newProvider(coordinator()).customize(recorder.proxy());

        Map<String, String> disableAll = Map.of(
                "platform.tracing.javaagent.scrubbing.enabled", "false",
                "platform.tracing.javaagent.span-attributes.validation.level", "DISABLED",
                "platform.tracing.span-timeout.enabled", "false"
        );

        // when/then
        assertThatThrownBy(() -> recorder.primeWithConfig(disableAll))
                .isInstanceOf(BootstrapException.class)
                .satisfies(failure -> assertThat(failure.getCause())
                        .hasMessageContaining("scrubbing.enabled=false is forbidden"));

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
    }

    private static final class Recorder {
        BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder> tracerProviderCustomizer;
        BiFunction<Resource, ConfigProperties, Resource> resourceCustomizer;

        private final List<Function<ConfigProperties, Map<String, String>>> propertiesCustomizers = new ArrayList<>();
        private boolean resourceCustomized;

        ConfigProperties primeWithConfig(Map<String, String> initialProperties) {
            Map<String, String> effectiveProperties = new HashMap<>(initialProperties);
            ConfigProperties effective = DefaultConfigProperties.createFromMap(Map.copyOf(effectiveProperties));
            for (Function<ConfigProperties, Map<String, String>> pc : propertiesCustomizers) {
                effectiveProperties.putAll(pc.apply(effective));
                effective = DefaultConfigProperties.createFromMap(Map.copyOf(effectiveProperties));
            }

            if (resourceCustomizer == null) {
                throw new IllegalStateException("Resource customizer was not registered");
            }
            if (resourceCustomized) {
                throw new IllegalStateException("Resource customizer must be invoked exactly once");
            }
            resourceCustomized = true;
            resourceCustomizer.apply(Resource.empty(), effective);
            return effective;
        }

        @SuppressWarnings("unchecked")
        AutoConfigurationCustomizer proxy() {
            return (AutoConfigurationCustomizer) Proxy.newProxyInstance(
                    AutoConfigurationCustomizer.class.getClassLoader(),
                    new Class<?>[]{AutoConfigurationCustomizer.class},
                    (Object proxyObj, Method method, Object[] args) -> switch (method.getName()) {
                        case "addTracerProviderCustomizer" -> {
                            this.tracerProviderCustomizer =
                                    (BiFunction<SdkTracerProviderBuilder, ConfigProperties, SdkTracerProviderBuilder>) args[0];
                            yield proxyObj;
                        }

                        case "addResourceCustomizer" -> {
                            this.resourceCustomizer = (BiFunction<Resource, ConfigProperties, Resource>) args[0];
                            yield proxyObj;
                        }

                        case "addPropertiesCustomizer" -> {
                            propertiesCustomizers.add(
                                    (Function<ConfigProperties, Map<String, String>>) args[0]
                            );

                            yield proxyObj;
                        }

                        default -> proxyObj;
                    });
        }
    }

    @Test
    void object_name_sampling_MBean_matches_public_contract() {
        // given/when/then
        assertThat(JmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME_STR)
                .isEqualTo("space.br1440.platform.tracing:type=Sampling,name=PlatformSamplingControl");
    }

    @Test
    void setSamplingRatio_via_platform_JMX_is_rejected() throws Exception {
        // given
        BootstrapCoordinator coordinator = coordinator();
        Recorder recorder = new Recorder();
        newProvider(coordinator).customize(recorder.proxy());

        ConfigProperties config = recorder.primeWithConfig(
                Map.of("platform.tracing.sampling.ratio", "0.1")
        );

        try (SdkTracerProvider provider = buildProvider(recorder, coordinator, config)) {
            satisfyReadinessBarrier(coordinator);
            coordinator.activate(provider);

            ObjectName objectName = DomainObjectNames.SAMPLING;

            // when/then
            Assertions.assertThrows(
                    Exception.class,
                    () -> server.setAttribute(objectName, new Attribute("SamplingObservation", null))
            );

            // then
            assertThat(samplingRatioFromObservation(objectName)).isEqualTo(0.1d);
        }
    }
}
