package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.util.ArrayList;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlObjectNames;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlWriterMBean;

final class PrivateControlServer {

    private final MBeanServer server;

    PrivateControlServer() {
        this(MBeanServerFactory.createMBeanServer(PrivateControlObjectNames.PRIVATE_DOMAIN));
    }

    PrivateControlServer(MBeanServer server) {
        this.server = server;
    }

    MBeanServer server() {
        return server;
    }

    void registerMarker(PrivateControlMarkerMBean marker) throws Exception {
        server.registerMBean(marker, PrivateControlObjectNames.MARKER);
    }

    void registerWriter(PrivateControlWriterMBean writer) throws Exception {
        server.registerMBean(writer, PrivateControlObjectNames.WRITER);
    }

    List<Exception> close() {
        List<Exception> failures = new ArrayList<>();
        unregister(PrivateControlObjectNames.WRITER, failures);
        unregister(PrivateControlObjectNames.MARKER, failures);
        release(failures);
        return failures;
    }

    private void unregister(ObjectName objectName, List<Exception> failures) {
        try {
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
        } catch (Exception exception) {
            failures.add(exception);
        }
    }

    private void release(List<Exception> failures) {
        try {
            if (MBeanServerFactory.findMBeanServer(null).contains(server)) {
                MBeanServerFactory.releaseMBeanServer(server);
            }
        } catch (Exception exception) {
            failures.add(exception);
        }
    }
}
