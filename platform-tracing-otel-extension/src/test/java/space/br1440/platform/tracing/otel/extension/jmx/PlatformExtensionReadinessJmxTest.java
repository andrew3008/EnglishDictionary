package space.br1440.platform.tracing.otel.extension.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.extension.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.extension.readiness.PlatformExtensionReadiness;

class PlatformExtensionReadinessJmxTest {

    @Test
    void readinessIsPublishedBeforeDomainMBeansAndDoesNotClaimReady() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

        new PlatformTracingJmxRegistrar(server, readiness);

        assertThat(server.isRegistered(PlatformTracingObjectNames.EXTENSION_READINESS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
        assertThat(server.getAttribute(
                PlatformTracingObjectNames.EXTENSION_READINESS, "LifecycleState"))
                .isEqualTo("INITIALIZING");
        assertThat((String[]) server.getAttribute(
                PlatformTracingObjectNames.EXTENSION_READINESS, "Capabilities"))
                .isEmpty();
    }

    @Test
    void mbeanReflectsOnlyActuallyMarkedCapabilities() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
        new PlatformTracingJmxRegistrar(server, readiness);

        readiness.markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);

        String[] capabilities = (String[]) server.getAttribute(
                PlatformTracingObjectNames.EXTENSION_READINESS, "Capabilities");
        assertThat(Arrays.asList(capabilities)).containsExactly("PLATFORM_SAMPLER_INSTALLED");
        assertThat(server.getAttribute(
                PlatformTracingObjectNames.EXTENSION_READINESS, "SamplerInstalled"))
                .isEqualTo(true);
        assertThat(server.getAttribute(
                PlatformTracingObjectNames.EXTENSION_READINESS, "SanitizerInstalled"))
                .isEqualTo(false);
    }
}
