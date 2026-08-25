package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.AttributeList;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class PlatformExtensionReadinessJmxTest {

    @Test
    void readinessIsPublishedBeforeDomainMBeansAndDoesNotClaimReady() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();

            DomainMBeanRegistrar registrar = new DomainMBeanRegistrar(server, readiness);
            registrar.registerDiagnosticReadiness();

            assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
            assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
            assertThat(server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS, "LifecycleState"))
                    .isEqualTo("INITIALIZING");
            assertThat((String[]) server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS, "Capabilities"))
                    .isEmpty();
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void mbeanReflectsOnlyPublishedSnapshotNotLiveMutation() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
            AtomicReference<ExtensionReadinessWireSnapshot> projection =
                    new AtomicReference<>(ExtensionReadinessWireSnapshot.initializing(readiness));
            DomainMBeanRegistrar registrar = new DomainMBeanRegistrar(server, readiness);
            registrar.registerDiagnosticReadiness(projection::get);

            readiness.markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
            // Live mutation of PlatformExtensionReadiness must not leak through the DynamicMBean
            // until the coordinator (or test) publishes a new immutable snapshot (S6).
            assertThat((String[]) server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS, "Capabilities"))
                    .isEmpty();

            projection.set(ExtensionReadinessWireSnapshot.initializing(readiness));
            String[] capabilities = (String[]) server.getAttribute(
                    DomainObjectNames.EXTENSION_READINESS, "Capabilities");
            assertThat(Arrays.asList(capabilities)).containsExactly("PLATFORM_SAMPLER_INSTALLED");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void getAttributesCapturesProjectionExactlyOnce() throws Exception {
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
            AtomicInteger gets = new AtomicInteger();
            ExtensionReadinessWireSnapshot frozen = ExtensionReadinessWireSnapshot.initializing(readiness);
            new DomainMBeanRegistrar(server, readiness).registerDiagnosticReadiness(() -> {
                gets.incrementAndGet();
                return frozen;
            });

            AttributeList attributes = server.getAttributes(
                    DomainObjectNames.EXTENSION_READINESS,
                    new String[] {"LifecycleState", "Capabilities", "FailureCode", "Profile"});

            assertThat(gets.get())
                    .as("getAttributes must capture projection supplier exactly once (S6)")
                    .isEqualTo(1);
            assertThat(attributes.asList()).hasSize(4);
            assertThat(attributes.asList().get(0).getValue()).isEqualTo("INITIALIZING");
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }
}
