package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.AttributeList;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

class PlatformExtensionReadinessJmxTest {

    private static final String READINESS_ATTRIBUTE =
            JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT;

    @Test
    void readinessIsPublishedBeforeDomainMBeansAndDoesNotClaimReady() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(server, readiness);
            ExtensionReadinessWireSnapshot projection =
                    ExtensionReadinessWireSnapshot.initializing(readiness.snapshot(), "");

            // when
            try (AutoCloseable ignored = registrar.registerDiagnosticReadiness(() -> projection)) {
                // then
                assertThat(server.isRegistered(DomainObjectNames.EXTENSION_READINESS)).isTrue();
                assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
                CompositeData wire = (CompositeData) server.getAttribute(
                        DomainObjectNames.EXTENSION_READINESS, READINESS_ATTRIBUTE);
                assertThat(wire.get(JmxWireContract.FIELD_READINESS_SNAPSHOT_LIFECYCLE))
                        .isEqualTo("INITIALIZING");
                assertThat((String[]) wire.get(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES)).isEmpty();
            }
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void mbeanReflectsOnlyPublishedSnapshotNotLiveMutation() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
            AtomicReference<ExtensionReadinessWireSnapshot> projection =
                    new AtomicReference<>(ExtensionReadinessWireSnapshot.initializing(
                            readiness.snapshot(),
                            "epoch-1"
                    ));
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(server, readiness);
            try (AutoCloseable ignored = registrar.registerDiagnosticReadiness(projection::get)) {
                // when
                readiness.markInstalled(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);

                // then
                CompositeData frozen = (CompositeData) server.getAttribute(
                        DomainObjectNames.EXTENSION_READINESS, READINESS_ATTRIBUTE);
                assertThat((String[]) frozen.get(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES)).isEmpty();

                // when
                projection.set(ExtensionReadinessWireSnapshot.initializing(
                        readiness.snapshot(),
                        "epoch-1"
                ));

                // then
                CompositeData published = (CompositeData) server.getAttribute(
                        DomainObjectNames.EXTENSION_READINESS, READINESS_ATTRIBUTE);
                String[] capabilities = (String[]) published.get(
                        JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES);
                assertThat(Arrays.asList(capabilities)).containsExactly("PLATFORM_SAMPLER_INSTALLED");
            }
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void mbeanInfoExposesExactlyOneReadOnlyGeneratedCompositeAttributeAndNoOperations() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
            ExtensionReadinessWireSnapshot projection =
                    ExtensionReadinessWireSnapshot.initializing(readiness.snapshot(), "");
            PlatformTracingMBeanRegistrar registrar = new PlatformTracingMBeanRegistrar(server, readiness);
            try (AutoCloseable ignored = registrar.registerDiagnosticReadiness(() -> projection)) {
                // when
                MBeanInfo info = server.getMBeanInfo(DomainObjectNames.EXTENSION_READINESS);

                // then
                assertThat(info.getAttributes()).hasSize(1);
                assertThat(info.getAttributes()[0].getName()).isEqualTo(READINESS_ATTRIBUTE);
                assertThat(info.getAttributes()[0].isReadable()).isTrue();
                assertThat(info.getAttributes()[0].isWritable()).isFalse();
                assertThat(info.getAttributes()[0].getType()).isEqualTo(CompositeData.class.getName());
                assertThat(info.getOperations()).isEmpty();
                CompositeData wire = (CompositeData) server.getAttribute(
                        DomainObjectNames.EXTENSION_READINESS, READINESS_ATTRIBUTE);
                assertThat(wire.getCompositeType()).isEqualTo(JmxOpenTypes.READINESS_SNAPSHOT);
            }
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }

    @Test
    void getAttributesCapturesProjectionExactlyOnce() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        try {
            PlatformExtensionReadiness readiness = new PlatformExtensionReadiness();
            AtomicInteger gets = new AtomicInteger();
            ExtensionReadinessWireSnapshot frozen =
                    ExtensionReadinessWireSnapshot.initializing(readiness.snapshot(), "epoch-1");
            try (AutoCloseable ignored = new PlatformTracingMBeanRegistrar(server, readiness)
                    .registerDiagnosticReadiness(() -> {
                        gets.incrementAndGet();
                        return frozen;
                    })) {
                // when
                AttributeList attributes = server.getAttributes(
                        DomainObjectNames.EXTENSION_READINESS,
                        new String[] {READINESS_ATTRIBUTE, "UnknownAttribute"});

                // then
                assertThat(gets.get()).isEqualTo(1);
                assertThat(attributes.asList()).hasSize(1);
                assertThat(attributes.asList().getFirst().getName()).isEqualTo(READINESS_ATTRIBUTE);
                assertThat(attributes.asList().getFirst().getValue()).isSameAs(frozen.readinessSnapshot());
            }
        } finally {
            MBeanServerFactory.releaseMBeanServer(server);
        }
    }
}
