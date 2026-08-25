package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;

import org.junit.jupiter.api.AfterAll;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;

abstract class PlatformControlMBeanLeakGuard {

    @AfterAll
    static void assertPlatformControlMBeanReleased() {
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        if (!platform.isRegistered(PrivateControlObjectNames.WRITER)) {
            return;
        }

        Exception cleanupFailure = null;
        try {
            platform.unregisterMBean(PrivateControlObjectNames.WRITER);
        } catch (Exception exception) {
            cleanupFailure = exception;
        }

        AssertionError failure = new AssertionError(
                "Platform control MBean leaked after the test class: "
                        + PrivateControlObjectNames.WRITER);
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure);
        }
        throw failure;
    }
}
