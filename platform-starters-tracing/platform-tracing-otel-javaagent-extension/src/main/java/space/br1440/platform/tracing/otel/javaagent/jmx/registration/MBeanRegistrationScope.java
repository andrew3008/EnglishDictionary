package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.management.MBeanServer;

import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

final class MBeanRegistrationScope {

    private final MBeanBatchRegistrar batchRegistrar;
    private final PlatformExtensionReadiness extensionReadiness;
    private final Object serializationLock = new Object();

    MBeanRegistrationScope(MBeanServer mbeanServer, PlatformExtensionReadiness extensionReadiness) {
        this.batchRegistrar = new MBeanBatchRegistrar(Objects.requireNonNull(mbeanServer, "mbeanServer"));
        this.extensionReadiness = Objects.requireNonNull(extensionReadiness, "extensionReadiness");
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

    PlatformExtensionReadiness extensionReadiness() {
        return extensionReadiness;
    }

    MBeanBatchRegistrar.RegisteredBatch registerOne(MBeanBatchRegistrar.Registration registration) {
        requireSerialized();
        return batchRegistrar.registerOne(registration);
    }

    MBeanBatchRegistrar.RegisteredBatch registerAllOrRollback(List<MBeanBatchRegistrar.Registration> registrations) {
        requireSerialized();
        return batchRegistrar.registerAllOrRollback(registrations);
    }

    void failRegistration(String code, Throwable failure) {
        extensionReadiness.fail(code, failure);
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
        failRegistration("FAILED_ROLLBACK_INCOMPLETE", aggregate);
        throw aggregate;
    }

    private void requireSerialized() {
        if (!Thread.holdsLock(serializationLock)) {
            throw new IllegalStateException("MBean batch mutation requires MBeanRegistrationScope serialization lock");
        }
    }
}
