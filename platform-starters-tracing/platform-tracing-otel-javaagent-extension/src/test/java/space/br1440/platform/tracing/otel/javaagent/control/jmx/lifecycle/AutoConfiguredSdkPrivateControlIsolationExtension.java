package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.lang.management.ManagementFactory;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import space.br1440.platform.tracing.otel.javaagent.SharedBootstrapBindingsReset;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;

public final class AutoConfiguredSdkPrivateControlIsolationExtension
        implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        cleanup();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        cleanup();
    }

    private static void cleanup() throws Exception {
        SharedBootstrapBindingsReset.reset();

        unregisterIfPresent(
                ManagementFactory.getPlatformMBeanServer(),
                PrivateControlObjectNames.WRITER);

        List<MBeanServer> retainedServers = MBeanServerFactory.findMBeanServer(null);
        for (MBeanServer candidate : retainedServers) {
            if (!PrivateControlObjectNames.PRIVATE_DOMAIN.equals(candidate.getDefaultDomain())) {
                continue;
            }
            unregisterIfPresent(candidate, PrivateControlObjectNames.WRITER);
            unregisterIfPresent(candidate, PrivateControlObjectNames.MARKER);
            MBeanServerFactory.releaseMBeanServer(candidate);
        }
    }

    private static void unregisterIfPresent(MBeanServer server, ObjectName objectName) throws Exception {
        if (server.isRegistered(objectName)) {
            server.unregisterMBean(objectName);
        }
    }
}
