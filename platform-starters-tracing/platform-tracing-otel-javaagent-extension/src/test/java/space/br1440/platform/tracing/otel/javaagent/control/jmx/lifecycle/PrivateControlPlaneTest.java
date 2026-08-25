package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.UUID;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.SettingsValidationConstraints;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

/**
 * Порядок фаз старта, идемпотентность {@code start}/{@code close} и агрегация cleanup-сбоев
 * для {@link PrivateControlPlane} как единственного composition root.
 */
class PrivateControlPlaneTest extends PlatformControlMBeanLeakGuard {

    @Test
    void activateRegistersWriterOnPrivateServer() throws Exception {
        TracingSettingsSource access = TracingSettingsSource.isolatedDefaults();
        int baselineServers = MBeanServerFactory.findMBeanServer(null).size();
        int baselinePrivateReady = countReadyPrivateWriters();

        try (AutoCloseable plane = PrivateControlPlane.activate(access)) {
            assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers + 1);
            assertThat(countReadyPrivateWriters())
                    .as("ready private writers before=%s after activation", baselinePrivateReady)
                    .isEqualTo(baselinePrivateReady + 1);
        }

        assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers);
        assertThat(countReadyPrivateWriters()).isEqualTo(baselinePrivateReady);
    }

    @Test
    void startIsIdempotentAndRegistersExactlyOneWriter() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        TracingSettingsStore store = store(runtimeId);
        MBeanServer platformServer = ManagementFactory.getPlatformMBeanServer();
        int baselineServers = MBeanServerFactory.findMBeanServer(null).size();

        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        PrivateControlPlane plane = new PrivateControlPlane(
                runtimeId, store, updater(store, diagnostics), diagnostics);
        MBeanServer server = plane.start();

        assertThat(plane.start()).isSameAs(server);
        assertThat(MBeanServerFactory.findMBeanServer(null)).contains(server);
        assertThat(platformServer.isRegistered(PrivateControlObjectNames.WRITER)).isTrue();
        assertThat(server.isRegistered(PrivateControlObjectNames.MARKER)).isTrue();
        assertThat(server.isRegistered(PrivateControlObjectNames.WRITER)).isTrue();
        assertThat(server.getAttribute(PrivateControlObjectNames.MARKER, "LifecycleState"))
                .isEqualTo("WRITER_READY");

        MBeanInfo markerInfo = server.getMBeanInfo(PrivateControlObjectNames.MARKER);
        assertThat(markerInfo.getClassName()).isEqualTo(PrivateControlMarkerMBean.class.getName());
        assertThat(markerInfo.getAttributes()).hasSize(6)
                .allMatch(attribute -> attribute.isReadable() && !attribute.isWritable());

        plane.close();
        plane.close();

        assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers);
        assertThat(server.isRegistered(PrivateControlObjectNames.MARKER)).isFalse();
        assertThat(server.isRegistered(PrivateControlObjectNames.WRITER)).isFalse();
        assertThat(platformServer.isRegistered(PrivateControlObjectNames.WRITER)).isFalse();
    }

    @Test
    void foreignPlatformRegistrationCausesFailFastWithoutReplacingItsOwner() throws Exception {
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        int baselineServers = MBeanServerFactory.findMBeanServer(null).size();
        String foreignRuntimeId = UUID.randomUUID().toString();
        platform.registerMBean(
                new PlatformSettingsStatusMBean(store(foreignRuntimeId), new LastSettingsUpdate()),
                PrivateControlObjectNames.WRITER);

        PrivateControlPlane plane = plane(UUID.randomUUID().toString());
        try {
            assertThat(catchThrowable(plane::start))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(InstanceAlreadyExistsException.class);
            assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "RuntimeInstanceId"))
                    .isEqualTo(foreignRuntimeId);
            assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers);
        } finally {
            plane.close();
            platform.unregisterMBean(PrivateControlObjectNames.WRITER);
        }
    }

    @Test
    void secondOwnerCollisionDoesNotUnregisterFirstOwnerStatus() throws Exception {
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        int baselineServers = MBeanServerFactory.findMBeanServer(null).size();
        String firstRuntimeId = UUID.randomUUID().toString();
        PrivateControlPlane first = plane(firstRuntimeId);
        PrivateControlPlane second = plane(UUID.randomUUID().toString());

        first.start();
        try {
            assertThat(catchThrowable(second::start))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(InstanceAlreadyExistsException.class);
            assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "RuntimeInstanceId"))
                    .isEqualTo(firstRuntimeId);
            assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers + 1);
        } finally {
            second.close();
            first.close();
        }

        assertThat(platform.isRegistered(PrivateControlObjectNames.WRITER)).isFalse();
        assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers);
    }

    @Test
    void cleanupDoesNotUnregisterReplacementOwnedByAnotherRuntime() throws Exception {
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        int baselineServers = MBeanServerFactory.findMBeanServer(null).size();
        String ownerRuntimeId = UUID.randomUUID().toString();
        String replacementRuntimeId = UUID.randomUUID().toString();
        PrivateControlPlane plane = plane(ownerRuntimeId);
        MBeanServer privateServer = plane.start();

        platform.unregisterMBean(PrivateControlObjectNames.WRITER);
        platform.registerMBean(
                new PlatformSettingsStatusMBean(store(replacementRuntimeId), new LastSettingsUpdate()),
                PrivateControlObjectNames.WRITER);
        try {
            assertThat(catchThrowable(plane::close))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Private control MBean cleanup failed")
                    .hasRootCauseMessage("Platform control MBean ownership changed before cleanup");
            assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "RuntimeInstanceId"))
                    .isEqualTo(replacementRuntimeId);
            assertThat(privateServer.isRegistered(PrivateControlObjectNames.MARKER)).isFalse();
            assertThat(privateServer.isRegistered(PrivateControlObjectNames.WRITER)).isFalse();
            assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers);
        } finally {
            platform.unregisterMBean(PrivateControlObjectNames.WRITER);
            plane.close();
        }
    }

    private static int countReadyPrivateWriters() throws Exception {
        int readyWriters = 0;
        for (MBeanServer server : MBeanServerFactory.findMBeanServer(null)) {
            if (!PrivateControlObjectNames.PRIVATE_DOMAIN.equals(server.getDefaultDomain())) {
                continue;
            }
            if (server.isRegistered(PrivateControlObjectNames.MARKER)
                    && server.isRegistered(PrivateControlObjectNames.WRITER)
                    && "WRITER_READY".equals(
                    server.getAttribute(PrivateControlObjectNames.MARKER, "LifecycleState"))) {
                readyWriters++;
            }
        }
        return readyWriters;
    }

    private static TracingSettingsStore store(String runtimeId) {
        return new TracingSettingsStore(runtimeId, policy(1.0d));
    }

    private static PrivateControlPlane plane(String runtimeId) {
        TracingSettingsStore store = store(runtimeId);
        LastSettingsUpdate diagnostics = new LastSettingsUpdate();
        return new PrivateControlPlane(runtimeId, store, updater(store, diagnostics), diagnostics);
    }

    private static TracingSettingsUpdater updater(TracingSettingsStore store, LastSettingsUpdate diagnostics) {
        return new TracingSettingsUpdater(store, new SettingsValidationConstraints(true), diagnostics);
    }

    private static TracingSettings policy(double ratio) {
        return TracingSettings.of(
                true,
                ratio,
                Map.of("/orders", 0.5d),
                true,
                false,
                true,
                true,
                TracingLogLevel.INFO);
    }
}
