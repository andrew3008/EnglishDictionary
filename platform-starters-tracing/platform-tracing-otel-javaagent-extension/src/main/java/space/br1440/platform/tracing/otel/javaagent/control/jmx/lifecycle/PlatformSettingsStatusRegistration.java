package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;

final class PlatformSettingsStatusRegistration {

    private final TracingSettingsStore store;
    private final MBeanServer platform;
    private boolean registered;

    PlatformSettingsStatusRegistration(TracingSettingsStore store, LastSettingsUpdate diagnostics) throws Exception {
        this(store, diagnostics, ManagementFactory.getPlatformMBeanServer());
    }

    PlatformSettingsStatusRegistration(TracingSettingsStore store,
                                       LastSettingsUpdate diagnostics,
                                       MBeanServer platform) throws Exception {
        this.store = store;
        this.platform = platform;
        platform.registerMBean(
                new PlatformSettingsStatusMBean(store, diagnostics),
                PrivateControlObjectNames.WRITER
        );
        this.registered = true;
    }

    Exception close() {
        if (!registered) {
            return null;
        }

        try {
            if (platform.isRegistered(PrivateControlObjectNames.WRITER)) {
                Object registeredRuntimeInstanceId = platform.getAttribute(
                        PrivateControlObjectNames.WRITER,
                        "RuntimeInstanceId"
                );

                String expectedRuntimeInstanceId = store.current().runtimeInstanceId();
                if (!expectedRuntimeInstanceId.equals(registeredRuntimeInstanceId)) {
                    return new IllegalStateException("Platform control MBean ownership changed before cleanup");
                }
                platform.unregisterMBean(PrivateControlObjectNames.WRITER);
            }

            registered = false;
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }
}
