package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.management.DynamicMBean;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentLogField;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ControlPlaneLogEvent;

final class MBeanBatchRegistrar {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(MBeanBatchRegistrar.class);

    record Registration(ObjectName name, DynamicMBean mbean) {

        Registration {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mbean, "mbean");
        }

        static Registration dynamic(ObjectName name, DynamicMBean mbean) {
            return new Registration(name, mbean);
        }
    }

    static final class RegisteredBatch implements AutoCloseable {

        private final MBeanBatchRegistrar owner;
        private final UUID batchId;
        private final List<OwnedDynamicMBean> journal;
        private boolean closed;

        private RegisteredBatch(MBeanBatchRegistrar owner, UUID batchId, List<OwnedDynamicMBean> journal) {
            this.owner = owner;
            this.batchId = batchId;
            this.journal = List.copyOf(journal);
        }

        UUID batchId() {
            return batchId;
        }

        List<UUID> registrationIds() {
            return journal.stream().map(OwnedDynamicMBean::registrationId).toList();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.unregisterOwned(this);
        }
    }

    private final MBeanServer server;

    private final Queue<OwnedDynamicMBean> unreclaimed = new ConcurrentLinkedQueue<>();

    private static final String CLEANUP_FAILURE_MESSAGE = "MBean batch cleanup failed";

    MBeanBatchRegistrar(MBeanServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    RegisteredBatch registerAllOrRollback(List<Registration> toRegister) {
        Objects.requireNonNull(toRegister, "toRegister");
        UUID batchId = UUID.randomUUID();
        List<OwnedDynamicMBean> journal = new ArrayList<>(toRegister.size());

        for (Registration registration : toRegister) {
            OwnedDynamicMBean owned = new OwnedDynamicMBean(
                    registration.mbean(), registration.name(), batchId, UUID.randomUUID()
            );
            try {
                server.registerMBean(owned, registration.name());
                journal.add(owned);
            } catch (InstanceAlreadyExistsException collision) {
                JmxRegistrationException incident = new JmxRegistrationException(
                        "JMX ObjectName ownership collision: " + registration.name(), collision
                );
                rollbackAndThrow(journal, incident);
            } catch (Exception registrationFailure) {
                JmxRegistrationException failure = new JmxRegistrationException(
                        "Domain MBean batch JMX registration failed at " + registration.name(),
                        registrationFailure
                );
                rollbackAndThrow(journal, failure);
            } catch (Error registrationFailure) {
                rollbackAndRethrow(journal, registrationFailure);
            }
        }
        return new RegisteredBatch(this, batchId, journal);
    }

    RegisteredBatch registerOne(Registration registration) {
        return registerAllOrRollback(List.of(registration));
    }

    private void rollbackAndThrow(List<OwnedDynamicMBean> journal, JmxRegistrationException primary) {
        List<Throwable> cleanupFailures = unregisterOwnedInReverse(journal);
        cleanupFailures.forEach(primary::addSuppressed);
        if (!cleanupFailures.isEmpty()) {
            primary.markRollbackIncomplete();
        }

        log.log(ControlPlaneLogEvent.MBEAN_BATCH_REGISTRATION_FAILED, fields -> fields
                .putLong(AgentLogField.COUNT, journal.size())
                .cause(primary));

        throw primary;
    }

    private void rollbackAndRethrow(List<OwnedDynamicMBean> journal, Error primary) {
        List<Throwable> cleanupFailures = unregisterOwnedInReverse(journal);
        if (!cleanupFailures.isEmpty()) {
            primary.addSuppressed(rollbackIncompleteFailure(cleanupFailures));
        }

        log.log(ControlPlaneLogEvent.MBEAN_BATCH_REGISTRATION_FAILED, fields -> fields
                .putLong(AgentLogField.COUNT, journal.size())
                .cause(primary));

        throw primary;
    }

    private void unregisterOwned(RegisteredBatch batch) {
        List<Throwable> failures = unregisterOwnedInReverse(batch.journal);
        if (failures.isEmpty()) {
            return;
        }

        throw rollbackIncompleteFailure(failures);
    }

    private static JmxRegistrationException rollbackIncompleteFailure(List<Throwable> cleanupFailures) {
        JmxRegistrationException aggregate = new JmxRegistrationException(
                CLEANUP_FAILURE_MESSAGE,
                cleanupFailures.getFirst()
        );
        cleanupFailures.stream().skip(1).forEach(aggregate::addSuppressed);
        aggregate.markRollbackIncomplete();
        return aggregate;
    }

    private List<Throwable> unregisterOwnedInReverse(List<OwnedDynamicMBean> journal) {
        if (journal.isEmpty()) {
            return List.of();
        }

        List<Throwable> failures = new ArrayList<>();
        List<OwnedDynamicMBean> reverse = new ArrayList<>(journal);
        Collections.reverse(reverse);
        for (OwnedDynamicMBean registration : reverse) {
            try {
                registration.unregisterOwned(server);
                unreclaimed.remove(registration);
            } catch (Exception | Error cleanupFailure) {
                unreclaimed.add(registration);
                failures.add(cleanupFailure);
            }
        }
        return failures;
    }

    List<Throwable> reclaimUnreclaimed() {
        if (unreclaimed.isEmpty()) {
            return List.of();
        }

        List<Throwable> failures = new ArrayList<>();
        for (Iterator<OwnedDynamicMBean> pending = unreclaimed.iterator(); pending.hasNext(); ) {
            OwnedDynamicMBean registration = pending.next();
            try {
                registration.unregisterOwned(server);
                pending.remove();
            } catch (Exception | Error cleanupFailure) {
                failures.add(cleanupFailure);
            }
        }

        return failures;
    }
}
