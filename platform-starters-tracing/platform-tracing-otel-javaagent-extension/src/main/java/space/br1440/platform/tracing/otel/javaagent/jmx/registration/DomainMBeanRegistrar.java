package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.lang.management.ManagementFactory;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import javax.management.MBeanServer;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotPublisher;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessDynamicMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
import space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxExposurePolicy.MutationRegistrationPermit;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.watchdog.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

public final class DomainMBeanRegistrar {

    public interface RuntimeRegistration extends AutoCloseable {

        UUID batchId();

        @Override
        void close();
    }

    final LongAdder sharedInvalidConfigCounter = new LongAdder();

    private final PlatformExtensionReadiness extensionReadiness;
    private final UUID agentInstanceId = UUID.randomUUID();
    private final AtomicReference<TracingSettingsSource> registeredAcceptedPolicy = new AtomicReference<>();
    private final OwnerRegistrationSlots slots = new OwnerRegistrationSlots();
    private final MBeanBatchRegistrar batchRegistrar;
    private final AtomicLong diagnosticGeneration = new AtomicLong();
    private final AtomicLong runtimeGeneration = new AtomicLong();
    private final Object registrationLock = new Object();

    private MBeanBatchRegistrar.RegisteredBatch diagnosticBatch;
    private MBeanBatchRegistrar.RegisteredBatch runtimeBatch;
    private volatile ObservationSnapshotPublisher observationPublisher;
    private long activeDiagnosticGeneration;
    private long activeRuntimeGeneration;

    public DomainMBeanRegistrar() {
        this(ManagementFactory.getPlatformMBeanServer(), new PlatformExtensionReadiness());
    }

    public DomainMBeanRegistrar(MBeanServer mbeanServer) {
        this(mbeanServer, new PlatformExtensionReadiness());
    }

    DomainMBeanRegistrar(MBeanServer mbeanServer, PlatformExtensionReadiness extensionReadiness) {
        this.extensionReadiness = Objects.requireNonNull(extensionReadiness, "extensionReadiness");
        this.batchRegistrar = new MBeanBatchRegistrar(Objects.requireNonNull(mbeanServer, "mbeanServer"));
    }

    public AutoCloseable registerDiagnosticReadiness(Supplier<ExtensionReadinessWireSnapshot> projection) {
        Objects.requireNonNull(projection, "projection");

        synchronized (registrationLock) {
            if (activeDiagnosticGeneration != 0L) {
                throw new IllegalStateException("DomainMBeanRegistrar already has an active readiness registration");
            }

            long token = diagnosticGeneration.incrementAndGet();
            try {
                diagnosticBatch = batchRegistrar.registerOne(MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.EXTENSION_READINESS,
                        new ExtensionReadinessDynamicMBean(projection)
                ));
            } catch (JmxRegistrationException failure) {
                failRegistration("READINESS_MBEAN_REGISTRATION_FAILED", failure);
                throw failure;
            }
            activeDiagnosticGeneration = token;
            return new DiagnosticReadinessRegistration(token);
        }
    }

    public RuntimeRegistration registerRuntimeControls(
            TracingSettingsSource acceptedPolicy,
            MutationRegistrationPermit permit) {
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");
        Objects.requireNonNull(permit, "Mutation registration permit must not be null");

        synchronized (registrationLock) {
            if (activeRuntimeGeneration != 0L) {
                throw new IllegalStateException("DomainMBeanRegistrar already has an active runtime registration");
            }
            return registerRuntimeControlsLocked(acceptedPolicy, permit);
        }
    }

    private RuntimeRegistration registerRuntimeControlsLocked(
            TracingSettingsSource acceptedPolicy,
            MutationRegistrationPermit permit) {
        permit.verify();
        TracingSettingsSource previous = registeredAcceptedPolicy.getAndSet(acceptedPolicy);
        long token = runtimeGeneration.incrementAndGet();
        DomainMBeanFactory.DomainMBeans domainMBeans;
        try {
            domainMBeans = DomainMBeanFactory.build(
                    slots,
                    acceptedPolicy,
                    sharedInvalidConfigCounter,
                    agentInstanceId
            );
        } catch (RuntimeException | Error failure) {
            registeredAcceptedPolicy.set(previous);
            failRegistration("JMX_OBSERVATION_INITIALIZATION_FAILED", failure);
            throw failure;
        }

        try {
            runtimeBatch = batchRegistrar.registerAllOrRollback(domainMBeans.registrations());
        } catch (JmxRegistrationException failure) {
            domainMBeans.observationPublisher().close();
            registeredAcceptedPolicy.set(previous);
            failRegistration(
                    failure.rollbackIncomplete() ? "FAILED_ROLLBACK_INCOMPLETE" : "JMX_BATCH_REGISTRATION_FAILED",
                    failure
            );
            throw failure;
        } catch (Error failure) {
            domainMBeans.observationPublisher().close();
            registeredAcceptedPolicy.set(previous);
            failRegistration(
                    failure.getSuppressed().length == 0
                            ? "JMX_BATCH_REGISTRATION_FAILED"
                            : "FAILED_ROLLBACK_INCOMPLETE",
                    failure
            );
            throw failure;
        }

        observationPublisher = domainMBeans.observationPublisher();
        try {
            observationPublisher.start();
        } catch (RuntimeException | Error failure) {
            registeredAcceptedPolicy.set(previous);
            try {
                closeRuntimeLocked();
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                failRegistration("FAILED_ROLLBACK_INCOMPLETE", rollbackFailure);
            }
            failRegistration("JMX_OBSERVATION_PUBLISHER_START_FAILED", failure);
            throw failure;
        }

        activeRuntimeGeneration = token;
        return new RuntimeControlsRegistration(token, previous, runtimeBatch.batchId());
    }

    public void setAcceptedPolicy(TracingSettingsSource acceptedPolicy) {
        registeredAcceptedPolicy.set(Objects.requireNonNull(acceptedPolicy, "acceptedPolicy"));
        requestObservationSnapshotPublication();
    }

    public void setCompositeSampler(CompositeSampler compositeSampler) {
        attachOwner(slots.compositeSampler(), compositeSampler);
    }

    public void setWatchdog(SpanWatchdogProcessor watchdog) {
        attachOwner(slots.watchdog(), watchdog);
    }

    public void setComposite(PlatformCompositeSpanProcessor composite) {
        attachOwner(slots.composite(), composite);
    }

    public void setMetrics(SpanLimitsMetricsProcessor metrics) {
        attachOwner(slots.metrics(), metrics);
    }

    public void setScrubbing(ScrubbingRuntime scrubbing) {
        attachOwner(slots.scrubbing(), scrubbing);
    }

    public void setValidating(ValidatingSpanProcessor validating) {
        attachOwner(slots.validating(), validating);
    }

    public void setExportProcessor(PlatformDropOldestExportSpanProcessor exportProcessor) {
        attachOwner(slots.exportProcessor(), exportProcessor);
    }

    public void setSafeExporter(SafeSpanExporter safeExporter) {
        attachOwner(slots.safeExporter(), safeExporter);
    }

    private <T> void attachOwner(OwnerRegistrationSlot<T> slot, T owner) {
        slot.replace(owner);
        requestObservationSnapshotPublication();
    }

    private void requestObservationSnapshotPublication() {
        ObservationSnapshotPublisher publisher = observationPublisher;
        if (publisher != null) {
            publisher.requestPublish();
        }
    }

    public PlatformExtensionReadiness extensionReadiness() {
        return extensionReadiness;
    }

    private void failRegistration(String code, Throwable failure) {
        extensionReadiness.fail(code, failure);
    }

    private void closeRuntimeLocked() {
        ObservationSnapshotPublisher publisher = observationPublisher;
        observationPublisher = null;
        if (publisher != null) {
            publisher.close();
        }

        if (runtimeBatch == null) {
            activeRuntimeGeneration = 0L;
            return;
        }
        try {
            runtimeBatch.close();
        } catch (JmxRegistrationException failure) {
            failRegistration("FAILED_ROLLBACK_INCOMPLETE", failure);
            throw failure;
        } finally {
            runtimeBatch = null;
            activeRuntimeGeneration = 0L;
        }
    }

    private void closeDiagnosticLocked() {
        if (diagnosticBatch == null) {
            return;
        }
        try {
            diagnosticBatch.close();
        } catch (JmxRegistrationException failure) {
            failRegistration("FAILED_ROLLBACK_INCOMPLETE", failure);
            throw failure;
        } finally {
            diagnosticBatch = null;
            activeDiagnosticGeneration = 0L;
        }
    }

    private final class DiagnosticReadinessRegistration implements AutoCloseable {

        private final long token;
        private boolean closed;

        private DiagnosticReadinessRegistration(long token) {
            this.token = token;
        }

        @Override
        public void close() {
            synchronized (registrationLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (activeDiagnosticGeneration == token) {
                    closeDiagnosticLocked();
                }
            }
        }
    }

    private final class RuntimeControlsRegistration implements RuntimeRegistration {

        private final long token;
        private final TracingSettingsSource previous;
        private final UUID batchId;
        private boolean closed;

        private RuntimeControlsRegistration(long token, TracingSettingsSource previous, UUID batchId) {
            this.token = token;
            this.previous = previous;
            this.batchId = batchId;
        }

        @Override
        public UUID batchId() {
            return batchId;
        }

        @Override
        public void close() {
            synchronized (registrationLock) {
                if (closed) {
                    return;
                }
                closed = true;
                if (activeRuntimeGeneration != token) {
                    return;
                }

                try {
                    closeRuntimeLocked();
                } finally {
                    registeredAcceptedPolicy.set(previous);
                }
            }
        }
    }
}
