package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;

class PlatformSettingsStatusRegistrationTest {

    private MBeanServer platform;

    @AfterEach
    void tearDown() {
        if (platform != null) {
            MBeanServerFactory.releaseMBeanServer(platform);
        }
    }

    @Test
    void registersReadOnlyStatusWithExactMBeanInfoClassName() throws Exception {
        platform = MBeanServerFactory.createMBeanServer();
        TracingSettingsStore store = store(UUID.randomUUID().toString());

        PlatformSettingsStatusRegistration registration =
                new PlatformSettingsStatusRegistration(store, new LastSettingsUpdate(), platform);

        MBeanInfo info = platform.getMBeanInfo(PrivateControlObjectNames.WRITER);
        assertThat(info.getClassName()).isEqualTo(PlatformSettingsStatusMBean.class.getName());
        assertThat(registration.close()).isNull();
    }

    @Test
    void collisionOnRegisterFailsFast() throws Exception {
        platform = MBeanServerFactory.createMBeanServer();
        platform.registerMBean(
                new PlatformSettingsStatusMBean(store(UUID.randomUUID().toString()), new LastSettingsUpdate()),
                PrivateControlObjectNames.WRITER);

        assertThatThrownBy(() -> new PlatformSettingsStatusRegistration(
                store(UUID.randomUUID().toString()), new LastSettingsUpdate(), platform))
                .isInstanceOf(InstanceAlreadyExistsException.class);
    }

    @Test
    void closeUnregistersOwnMBeanAndRepeatedCloseIsSafe() throws Exception {
        platform = MBeanServerFactory.createMBeanServer();
        TracingSettingsStore store = store(UUID.randomUUID().toString());
        PlatformSettingsStatusRegistration registration =
                new PlatformSettingsStatusRegistration(store, new LastSettingsUpdate(), platform);

        assertThat(registration.close()).isNull();
        assertThat(platform.isRegistered(PrivateControlObjectNames.WRITER)).isFalse();
        assertThat(registration.close()).isNull();
    }

    @Test
    void closeDetectsOwnershipMismatchAndDoesNotUnregisterForeignMBean() throws Exception {
        platform = MBeanServerFactory.createMBeanServer();
        String ownerRuntimeId = UUID.randomUUID().toString();
        String replacementRuntimeId = UUID.randomUUID().toString();
        PlatformSettingsStatusRegistration registration = new PlatformSettingsStatusRegistration(
                store(ownerRuntimeId), new LastSettingsUpdate(), platform);

        platform.unregisterMBean(PrivateControlObjectNames.WRITER);
        platform.registerMBean(
                new PlatformSettingsStatusMBean(store(replacementRuntimeId), new LastSettingsUpdate()),
                PrivateControlObjectNames.WRITER);

        Exception failure = registration.close();

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Platform control MBean ownership changed before cleanup");
        assertThat(platform.getAttribute(PrivateControlObjectNames.WRITER, "RuntimeInstanceId"))
                .isEqualTo(replacementRuntimeId);
    }

    @Test
    void platformProjectionContainsOnlySixApprovedReads() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        TracingSettingsStore store = store(runtimeId);
        PlatformSettingsStatusMBean projection = new PlatformSettingsStatusMBean(store, new LastSettingsUpdate());

        MBeanInfo info = projection.getMBeanInfo();

        assertThat(info.getAttributes()).hasSize(6)
                .allMatch(attribute -> attribute.isReadable() && !attribute.isWritable());
        assertThat(info.getOperations()).isEmpty();
        assertThat(projection.getAttribute("RuntimeInstanceId")).isEqualTo(runtimeId);
        assertThat(projection.getAttribute("AcceptedSequence")).isEqualTo(0L);
    }

    private static TracingSettingsStore store(String runtimeId) {
        return new TracingSettingsStore(runtimeId, TracingSettings.of(
                true, 1.0d, Map.of(), true, false, true, true, TracingLogLevel.INFO));
    }
}
