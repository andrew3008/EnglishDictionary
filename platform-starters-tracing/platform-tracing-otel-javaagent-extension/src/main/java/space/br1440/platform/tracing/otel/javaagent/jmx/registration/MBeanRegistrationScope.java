package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.management.MBeanServer;

final class MBeanRegistrationScope {

    private final MBeanBatchRegistrar batchRegistrar;
    private final Object serializationLock = new Object();

    MBeanRegistrationScope(MBeanServer mbeanServer) {
        this.batchRegistrar = new MBeanBatchRegistrar(Objects.requireNonNull(mbeanServer, "mbeanServer"));
    }

    void runSerialized(Runnable action) {
        Objects.requireNonNull(action, "action");

        synchronized (serializationLock) {
            action.run();
        }
    }

    <T> T callSerialized(Supplier<T> action) {
        Objects.requireNonNull(action, "action");

        synchronized (serializationLock) {
            return action.get();
        }
    }

    MBeanBatchRegistrar.RegisteredBatch registerOne(MBeanBatchRegistrar.Registration registration) {
        requireSerialized();
        return batchRegistrar.registerOne(registration);
    }

    MBeanBatchRegistrar.RegisteredBatch registerAllOrRollback(List<MBeanBatchRegistrar.Registration> registrations) {
        requireSerialized();
        return batchRegistrar.registerAllOrRollback(registrations);
    }

    void reclaimUnreclaimed() {
        requireSerialized();

        List<Throwable> failures = batchRegistrar.reclaimUnreclaimed();
        if (failures.isEmpty()) {
            return;
        }

        JmxRegistrationException aggregate = new JmxRegistrationException("MBean cleanup retry failed", failures.getFirst());
        failures.stream().skip(1).forEach(aggregate::addSuppressed);
        aggregate.markRollbackIncomplete();
        throw aggregate;
    }

    private void requireSerialized() {
        if (!Thread.holdsLock(serializationLock)) {
            throw new IllegalStateException("MBean batch mutation requires MBeanRegistrationScope serialization lock");
        }
    }
}
