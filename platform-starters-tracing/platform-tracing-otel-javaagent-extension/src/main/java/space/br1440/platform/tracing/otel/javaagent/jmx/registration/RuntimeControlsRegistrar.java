package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotPublisher;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

final class RuntimeControlsRegistrar {

    private final MBeanRegistrationScope scope;
    private final LongAdder sharedInvalidConfigCounter = new LongAdder();
    private final OwnerRegistrationSlots slots = new OwnerRegistrationSlots();
    private final UUID agentInstanceId;

    private long generation;
    private long activeGeneration;
    private MBeanBatchRegistrar.RegisteredBatch batch;

    private volatile ObservationSnapshotPublisher observationPublisher;

    RuntimeControlsRegistrar(MBeanRegistrationScope scope, UUID agentInstanceId) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    PlatformTracingMBeanRegistrar.RuntimeRegistration register(TracingSettingsSource acceptedPolicy) {
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");

        return scope.callSerialized(() -> {
            if (activeGeneration != 0L) {
                throw new IllegalStateException("PlatformTracingMBeanRegistrar already has an active runtime registration");
            }

            return registerLocked(acceptedPolicy);
        });
    }

    void setCompositeSampler(CompositeSampler compositeSampler) {
        attachOwner(slots.compositeSampler(), compositeSampler);
    }

    void setSpanTimeout(SpanTimeoutProcessor spanTimeout) {
        attachOwner(slots.spanTimeout(), spanTimeout);
    }

    void setComposite(PlatformCompositeSpanProcessor composite) {
        attachOwner(slots.composite(), composite);
    }

    void setMetrics(SpanLimitsMetricsProcessor metrics) {
        attachOwner(slots.metrics(), metrics);
    }

    void setScrubbing(ScrubbingRuntime scrubbing) {
        attachOwner(slots.scrubbing(), scrubbing);
    }

    void setValidating(ValidatingSpanProcessor validating) {
        attachOwner(slots.validating(), validating);
    }

    void setExportProcessor(PlatformDropOldestExportSpanProcessor exportProcessor) {
        attachOwner(slots.exportProcessor(), exportProcessor);
    }

    void setSafeExporter(SafeSpanExporter safeExporter) {
        attachOwner(slots.safeExporter(), safeExporter);
    }

    private <T> void attachOwner(OwnerRegistrationSlot<T> slot, T owner) {
        slot.replace(owner);
        requestObservationSnapshotPublication();
    }

    private PlatformTracingMBeanRegistrar.RuntimeRegistration registerLocked(TracingSettingsSource acceptedPolicy) {
        long token = ++generation;

        DomainMBeanFactory.DomainMBeans domainMBeans;
        try {
            domainMBeans = DomainMBeanFactory.build(
                    slots,
                    acceptedPolicy,
                    sharedInvalidConfigCounter,
                    agentInstanceId
            );
        } catch (RuntimeException | Error failure) {
            scope.failRegistration("JMX_OBSERVATION_INITIALIZATION_FAILED", failure);
            throw failure;
        }

        try {
            batch = scope.registerAllOrRollback(domainMBeans.registrations());
        } catch (RuntimeException | Error failure) {
            failBatchRegistrationLocked(domainMBeans.observationPublisher(), failure);
            throw failure;
        }

        observationPublisher = domainMBeans.observationPublisher();
        try {
            observationPublisher.start();
        } catch (RuntimeException | Error failure) {
            failPublisherStartLocked(failure);
            throw failure;
        }

        activeGeneration = token;
        return new Handle(token, batch.batchId());
    }

    private void failBatchRegistrationLocked(ObservationSnapshotPublisher unstartedPublisher, Throwable failure) {
        closeSuppressing(unstartedPublisher, failure);
        scope.failRegistration(batchFailureReason(failure), failure);
    }

    private void failPublisherStartLocked(Throwable failure) {
        ObservationSnapshotPublisher startedPublisher = detachPublisherLocked();
        String reason = "JMX_OBSERVATION_PUBLISHER_START_FAILED";
        try {
            closeLocked();
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            reason = "FAILED_ROLLBACK_INCOMPLETE";
        } finally {
            closeSuppressing(startedPublisher, failure);
        }

        scope.failRegistration(reason, failure);
    }

    private static String batchFailureReason(Throwable failure) {
        return rollbackIncomplete(failure) ? "FAILED_ROLLBACK_INCOMPLETE" : "JMX_BATCH_REGISTRATION_FAILED";
    }

    private static boolean rollbackIncomplete(Throwable failure) {
        if (markedRollbackIncomplete(failure)) {
            return true;
        }

        for (Throwable suppressed : failure.getSuppressed()) {
            if (markedRollbackIncomplete(suppressed)) {
                return true;
            }
        }

        return false;
    }

    private static boolean markedRollbackIncomplete(Throwable failure) {
        return failure instanceof JmxRegistrationException jmxFailure && jmxFailure.rollbackIncomplete();
    }

    private void requestObservationSnapshotPublication() {
        ObservationSnapshotPublisher publisher = observationPublisher;
        if (publisher != null) {
            publisher.requestSnapshotPublication();
        }
    }

    private ObservationSnapshotPublisher detachPublisherLocked() {
        ObservationSnapshotPublisher publisher = observationPublisher;
        observationPublisher = null;
        return publisher;
    }

    private static void closeSuppressing(ObservationSnapshotPublisher publisher, Throwable primaryFailure) {
        if (publisher == null) {
            return;
        }

        try {
            publisher.close();
        } catch (RuntimeException | Error closeFailure) {
            primaryFailure.addSuppressed(closeFailure);
        }
    }

    private void closeLocked() {
        if (batch == null) {
            activeGeneration = 0L;
            scope.reclaimUnreclaimed();
            return;
        }

        try {
            batch.close();
        } catch (JmxRegistrationException failure) {
            scope.failRegistration("FAILED_ROLLBACK_INCOMPLETE", failure);
            throw failure;
        } finally {
            batch = null;
            activeGeneration = 0L;
        }

        scope.reclaimUnreclaimed();
    }

    private final class Handle implements PlatformTracingMBeanRegistrar.RuntimeRegistration {

        private final long token;
        private final UUID batchId;
        private boolean closed;
        private ObservationSnapshotPublisher detachedPublisher;

        private Handle(long token, UUID batchId) {
            this.token = token;
            this.batchId = batchId;
        }

        @Override
        public UUID batchId() {
            return batchId;
        }

        @Override
        public void close() {
            boolean closePublisherOutside;
            try {
                closePublisherOutside = scope.callSerialized(this::closeLockedOnce);
            } catch (RuntimeException | Error batchFailure) {
                closeSuppressing(takeDetachedPublisher(), batchFailure);
                throw batchFailure;
            }

            if (closePublisherOutside) {
                closeDetachedPublisher(takeDetachedPublisher());
            }
        }

        private boolean closeLockedOnce() {
            if (closed) {
                return false;
            }

            closed = true;
            if (activeGeneration != token) {
                return false;
            }

            detachedPublisher = detachPublisherLocked();
            closeLocked();
            return true;
        }

        private ObservationSnapshotPublisher takeDetachedPublisher() {
            ObservationSnapshotPublisher publisher = detachedPublisher;
            detachedPublisher = null;
            return publisher;
        }

        private void closeDetachedPublisher(ObservationSnapshotPublisher publisher) {
            if (publisher != null) {
                publisher.close();
            }
        }
    }
}
