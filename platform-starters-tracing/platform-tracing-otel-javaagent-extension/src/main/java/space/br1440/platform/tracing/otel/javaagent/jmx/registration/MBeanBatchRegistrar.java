package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class MBeanBatchRegistrar {

    record Registration(ObjectName name, Object mbean) {
    }

    private final MBeanServer server;

    MBeanBatchRegistrar(MBeanServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    void registerAllOrRollback(List<Registration> toRegister) {
        List<ObjectName> successfullyRegistered = new ArrayList<>(toRegister.size());
        Throwable firstFailure = null;

        for (Registration registration : toRegister) {
            try {
                replaceExisting(registration.mbean(), registration.name());
                successfullyRegistered.add(registration.name());
            } catch (Exception e) {
                firstFailure = e;
                break;
            }
        }

        if (firstFailure != null) {
            log.error("MBean batch registration did not complete — rolling back {} MBean(s): {}", successfullyRegistered.size(), firstFailure.getMessage());
            rollbackAndThrow(successfullyRegistered, firstFailure);
        }
    }

    void replaceExisting(Object mbean, ObjectName name) throws Exception {
        if (server.isRegistered(name)) {
            server.unregisterMBean(name);
        }

        server.registerMBean(mbean, name);
    }

    void unregisterSilently(ObjectName name) {
        try {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (InstanceNotFoundException alreadyUnregistered) {
            // MBean уже был незарегестрирован
        } catch (Exception e) {
            log.debug("Failed to unregister MBean {}: {}", name, e.getMessage());
        }
    }

    private void rollbackAndThrow(List<ObjectName> toRollback, Throwable cause) {
        String message = "Domain MBean batch JMX registration failed: " + cause.getMessage();
        JmxRegistrationException failure = new JmxRegistrationException(message, cause);
        for (int i = toRollback.size() - 1; i >= 0; i--) {
            try {
                server.unregisterMBean(toRollback.get(i));
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }

        throw failure;
    }
}
