package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundSampling;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.PlatformTracingMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class PlatformSpanProcessorFactoryWiringTest {

    private MBeanServer server;
    private PlatformTracingMBeanRegistrar registrar;
    private TracingSettingsSource samplerHolder;

    @BeforeEach
    void setUp() {
        server = MBeanServerFactory.createMBeanServer(
                "space.br1440.platform.tracing.test");

        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        samplerHolder = boundSampling(agentInstanceId, true, Collections.emptyMap(), 0.1d);
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void settingsStatusAndMutationMBeansAreRegisteredAfterActivation() {
        // given
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), samplerHolder);

        // when
        try (PlatformTracingMBeanRegistrar.RuntimeRegistration ignored =
                     registrar.registerRuntimeControls(samplerHolder)) {
            // then
            assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isTrue();
            assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isTrue();
        }
    }

    @Test
    void allDomainMBeansRegisteredByFactory() {
        // given
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(),
                minimalExtensionConfig(),
                samplerHolder
        );

        // when/then
        try (PlatformTracingMBeanRegistrar.RuntimeRegistration ignored = registrar.registerRuntimeControls(samplerHolder)) {
            for (javax.management.ObjectName name : List.of(
                    DomainObjectNames.SAMPLING,
                    DomainObjectNames.SCRUBBING,
                    DomainObjectNames.VALIDATION,
                    DomainObjectNames.EXPORT,
                    DomainObjectNames.PROCESSOR_METRICS,
                    DomainObjectNames.DIAGNOSTICS,
                    DomainObjectNames.SETTINGS_STATUS,
                    DomainObjectNames.SETTINGS_MUTATION)) {
                assertThat(server.isRegistered(name))
                        .as("Expected MBean to be registered: " + name)
                        .isTrue();
            }
        }
    }

    @Test
    void registeredDomainMBeansExposeNoMutatingOperations() throws Exception {
        // given
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), samplerHolder);

        // when/then
        try (PlatformTracingMBeanRegistrar.RuntimeRegistration ignored =
                     registrar.registerRuntimeControls(samplerHolder)) {
            for (javax.management.ObjectName name : List.of(
                    DomainObjectNames.SAMPLING,
                    DomainObjectNames.SCRUBBING,
                    DomainObjectNames.VALIDATION,
                    DomainObjectNames.EXPORT,
                    DomainObjectNames.PROCESSOR_METRICS,
                    DomainObjectNames.DIAGNOSTICS)) {
                MBeanInfo info = server.getMBeanInfo(name);
                assertThat(info.getOperations())
                        .allMatch(operation -> operation.getImpact() != MBeanOperationInfo.ACTION
                                && operation.getImpact() != MBeanOperationInfo.ACTION_INFO);
                assertThat(info.getAttributes())
                        .allMatch(attribute -> !attribute.isWritable());
            }
        }
    }

    @Test
    void samplingMBeanReflectsAcceptedPolicy() throws Exception {
        // given
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), samplerHolder);

        // when/then
        try (PlatformTracingMBeanRegistrar.RuntimeRegistration ignored =
                     registrar.registerRuntimeControls(samplerHolder)) {
            CompositeData observation = (CompositeData) server.getAttribute(
                    DomainObjectNames.SAMPLING, "SamplingObservation");
            double ratio = (double) observation.get(
                    JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO);
            assertThat(ratio).isEqualTo(0.1d);
        }
    }

    private ConfigProperties minimalConfig() {
        return new EmptyConfigProperties();
    }

    private space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig
            minimalExtensionConfig() {
        return new space.br1440.platform.tracing.otel.javaagent.configuration
                .ExtensionConfig(minimalConfig());
    }

    private static class EmptyConfigProperties implements ConfigProperties {

        @Override
        public String getString(String name) {
            return null;
        }

        @Override
        public Boolean getBoolean(String name) {
            return null;
        }

        @Override
        public Integer getInt(String name) {
            return null;
        }

        @Override
        public Long getLong(String name) {
            return null;
        }

        @Override
        public Double getDouble(String name) {
            return null;
        }

        @Override
        public Duration getDuration(String name) {
            return null;
        }

        @Override
        public List<String> getList(String name) {
            return List.of();
        }

        @Override
        public Map<String, String> getMap(String name) {
            return Map.of();
        }
    }
}
