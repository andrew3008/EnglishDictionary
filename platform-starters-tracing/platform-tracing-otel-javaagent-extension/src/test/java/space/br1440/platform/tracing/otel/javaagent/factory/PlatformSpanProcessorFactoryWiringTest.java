package space.br1440.platform.tracing.otel.javaagent.factory;

import static org.assertj.core.api.Assertions.assertThat;

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
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.registration.DomainMBeanRegistrar;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntimeTestFixtures;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

class PlatformSpanProcessorFactoryWiringTest {

    private MBeanServer privateMBeanServer;
    private DomainMBeanRegistrar registrar;
    private TracingSettingsSource samplerHolder;

    @BeforeEach
    void setUp() {
        privateMBeanServer = MBeanServerFactory.createMBeanServer(
                "space.br1440.platform.tracing.test");

        samplerHolder = TracingSettingsSource.isolatedSampling(
                true, Collections.emptyMap(), 0.1d);

        registrar = new DomainMBeanRegistrar(privateMBeanServer);
        registrar.setAcceptedPolicy(samplerHolder);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(privateMBeanServer);
    }

    @Test
    void controlProtocolMBeanNotRegisteredAfterRegisterSpanProcessors() throws Exception {
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());

        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), minimalConfig(), samplerHolder);
        registrar.registerRuntimeControls(samplerHolder);

        assertThat(privateMBeanServer.isRegistered(
                DomainObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    @Test
    void allSixDomainMBeansRegisteredByFactory() throws Exception {
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), minimalConfig(), samplerHolder);
        // MBean registration is deferred to bootstrap activation (§4 S4); the factory only wires
        // component evidence, so the activation step is simulated explicitly here.
        registrar.registerRuntimeControls(samplerHolder);

        for (javax.management.ObjectName name : List.of(
                DomainObjectNames.SAMPLING,
                DomainObjectNames.SCRUBBING,
                DomainObjectNames.VALIDATION,
                DomainObjectNames.EXPORT,
                DomainObjectNames.PROCESSOR_METRICS,
                DomainObjectNames.DIAGNOSTICS)) {
            assertThat(privateMBeanServer.isRegistered(name))
                    .as("Expected MBean to be registered: " + name)
                    .isTrue();
        }
    }

    @Test
    void registeredDomainMBeansExposeNoMutatingOperations() throws Exception {
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), minimalConfig(), samplerHolder);
        registrar.registerRuntimeControls(samplerHolder);

        for (javax.management.ObjectName name : List.of(
                DomainObjectNames.SAMPLING,
                DomainObjectNames.SCRUBBING,
                DomainObjectNames.VALIDATION,
                DomainObjectNames.EXPORT,
                DomainObjectNames.PROCESSOR_METRICS,
                DomainObjectNames.DIAGNOSTICS)) {
            MBeanInfo info = privateMBeanServer.getMBeanInfo(name);
            assertThat(info.getOperations())
                    .allMatch(operation -> operation.getImpact() != MBeanOperationInfo.ACTION
                            && operation.getImpact() != MBeanOperationInfo.ACTION_INFO);
            assertThat(info.getAttributes())
                    .allMatch(attribute -> !attribute.isWritable());
        }
    }

    @Test
    void samplingMBeanReflectsAcceptedPolicy() throws Exception {
        PlatformSpanProcessorFactory factory = new PlatformSpanProcessorFactory(
                registrar, ScrubbingRuntimeTestFixtures.disabled());
        factory.registerSpanProcessors(
                SdkTracerProvider.builder(), minimalExtensionConfig(), minimalConfig(), samplerHolder);
        registrar.registerRuntimeControls(samplerHolder);

        CompositeData observation = (CompositeData) privateMBeanServer.getAttribute(
                DomainObjectNames.SAMPLING, "SamplingObservation");
        double ratio = (double) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO);
        assertThat(ratio).isEqualTo(0.1d);
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
