package space.br1440.platform.tracing.otel.extension.factory;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar;
import space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link PlatformSpanProcessorFactory#registerSpanProcessors}
 * correctly wires the {@code RuntimePolicyControlHandler} into the
 * registrar and that the {@code PlatformControlProtocolMBean} is
 * registered and operational after the factory call.
 *
 * <p>An isolated (non-platform) {@link MBeanServer} is used so these
 * tests never pollute or conflict with other tests or the platform server.
 *
 * <p>The test uses a minimal {@link ConfigProperties} implementation that
 * returns default/empty values for all lookups so we avoid loading real
 * scrubbing rules.
 */
class PlatformSpanProcessorFactoryWiringTest {

    private MBeanServer              privateMBeanServer;
    private PlatformTracingJmxRegistrar registrar;
    private SamplerStateHolder       samplerHolder;

    @BeforeEach
    void setUp() {
        // Isolated MBeanServer: never touches ManagementFactory.getPlatformMBeanServer()
        privateMBeanServer = MBeanServerFactory.createMBeanServer(
                "space.br1440.platform.tracing.test");

        samplerHolder = new SamplerStateHolder(
                true, List.of(), List.of(), Collections.emptyMap(), 0.1d);

        registrar = new PlatformTracingJmxRegistrar(privateMBeanServer);

        // Simulate what PlatformSamplerFactory does before factory is called
        registrar.setConfigHolder(samplerHolder);
    }

    @AfterEach
    void tearDown() {
        MBeanServerFactory.releaseMBeanServer(privateMBeanServer);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ConfigProperties minimalConfig() {
        return new EmptyConfigProperties();
    }

    private space.br1440.platform.tracing.otel.extension.configuration.ExtensionConfig
            minimalExtensionConfig() {
        // Build an ExtensionConfig with all features disabled except
        // a no-op stub that lets the factory run to completion.
        // We use the test-friendly overload that accepts a ConfigProperties.
        return new space.br1440.platform.tracing.otel.extension.configuration
                .ExtensionConfig(minimalConfig());
    }

    private SdkTracerProviderBuilder stubBuilder() {
        // Real builder; we don't care about its output in these tests.
        return io.opentelemetry.sdk.trace.SdkTracerProvider.builder();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    void controlProtocolMBeanRegisteredAfterRegisterSpanProcessors() throws Exception {
        PlatformSpanProcessorFactory factory =
                new PlatformSpanProcessorFactory(registrar);

        factory.registerSpanProcessors(
                stubBuilder(), minimalExtensionConfig(), minimalConfig());

        assertThat(privateMBeanServer.isRegistered(
                PlatformTracingObjectNames.CONTROL_PROTOCOL)).isTrue();
    }

    @Test
    void controlProtocolMBeanRespondsToApplyPolicyViaServer() throws Exception {
        PlatformSpanProcessorFactory factory =
                new PlatformSpanProcessorFactory(registrar);
        factory.registerSpanProcessors(
                stubBuilder(), minimalExtensionConfig(), minimalConfig());

        // Build a minimal APPLY_RUNTIME_POLICY CompositeData
        String[]      keys  = {
                "contractVersion",
                "operation",
                "sampling.ratio"};
        String[]      descs = keys;
        javax.management.openmbean.OpenType<?>[] types = {
                javax.management.openmbean.SimpleType.INTEGER,
                javax.management.openmbean.SimpleType.STRING,
                javax.management.openmbean.SimpleType.DOUBLE};
        Object[] vals = {1, "APPLY_RUNTIME_POLICY", 0.42d};
        javax.management.openmbean.CompositeType ct =
                new javax.management.openmbean.CompositeType(
                        "P", "p", keys, descs, types);
        CompositeData payload =
                new javax.management.openmbean.CompositeDataSupport(ct, keys, vals);

        String result = (String) privateMBeanServer.invoke(
                PlatformTracingObjectNames.CONTROL_PROTOCOL,
                "applyPolicy",
                new Object[]{payload},
                new String[]{CompositeData.class.getName()});

        assertThat(result).startsWith("SUCCESS");
        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.42d);
    }

    @Test
    void controlProtocolMBeanReadAppliedStateAvailable() throws Exception {
        PlatformSpanProcessorFactory factory =
                new PlatformSpanProcessorFactory(registrar);
        factory.registerSpanProcessors(
                stubBuilder(), minimalExtensionConfig(), minimalConfig());

        CompositeData state = (CompositeData) privateMBeanServer.invoke(
                PlatformTracingObjectNames.CONTROL_PROTOCOL,
                "readAppliedState",
                new Object[]{},
                new String[]{});

        assertThat(state).isNotNull();
        assertThat(state.getCompositeType().keySet())
                .contains("sampling.ratio");
    }

    @Test
    void allSevenMBeansRegisteredByFactory() throws Exception {
        PlatformSpanProcessorFactory factory =
                new PlatformSpanProcessorFactory(registrar);
        factory.registerSpanProcessors(
                stubBuilder(), minimalExtensionConfig(), minimalConfig());

        for (ObjectName name : List.of(
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS,
                PlatformTracingObjectNames.CONTROL_PROTOCOL)) {
            assertThat(privateMBeanServer.isRegistered(name))
                    .as("Expected MBean to be registered: " + name)
                    .isTrue();
        }
    }

    /**
     * Минимальная конфигурация для wiring-тестов: все значения отсутствуют,
     * default-методы интерфейса возвращают переданные fallback'и.
     */
    private static final class EmptyConfigProperties implements ConfigProperties {

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
