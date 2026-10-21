package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotPublisher;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

final class RuntimeControlsRegistrar {

    private final MBeanRegistrationScope scope;
    private final LongAdder sharedInvalidConfigCounter = new LongAdder();
    private final OwnerRegistrationSlots slots = new OwnerRegistrationSlots();
    private final AgentInstanceId agentInstanceId;

    private Handle active;
    private MBeanBatchRegistrar.RegisteredBatch batch;

    private volatile ObservationSnapshotPublisher observationPublisher;

    RuntimeControlsRegistrar(MBeanRegistrationScope scope, AgentInstanceId agentInstanceId) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.agentInstanceId = Objects.requireNonNull(agentInstanceId, "agentInstanceId");
    }

    PlatformTracingMBeanRegistrar.RuntimeRegistration register(TracingSettingsSource acceptedPolicy) {
        Objects.requireNonNull(acceptedPolicy, "acceptedPolicy");

        return scope.callSerialized(() -> {
            if (active != null) {
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

    private Handle registerLocked(TracingSettingsSource acceptedPolicy) {
        DomainMBeanFactory.DomainMBeans domainMBeans = callClassified(
                Stage.OBSERVATION_INITIALIZATION,
                () -> DomainMBeanFactory.build(slots, acceptedPolicy, sharedInvalidConfigCounter, agentInstanceId)
        );

        observationPublisher = domainMBeans.observationPublisher();
        try {
            batch = callClassified(
                    Stage.BATCH_REGISTRATION,
                    () -> scope.registerAllOrRollback(domainMBeans.registrations())
            );
            runClassified(Stage.PUBLISHER_START, observationPublisher::start);
        } catch (RuntimeException | Error failure) {
            releaseLocked().suppressInto(failure);
            throw failure;
        }

        active = new Handle(batch.batchId());
        return active;
    }

    private Release releaseLocked() {
        ObservationSnapshotPublisher publisher = observationPublisher;
        MBeanBatchRegistrar.RegisteredBatch registered = batch;
        observationPublisher = null;
        batch = null;
        active = null;

        if (registered == null) {
            return new Release(publisher, null);
        }

        try {
            registered.close();
            scope.reclaimUnreclaimed();
            return new Release(publisher, null);
        } catch (RuntimeException | Error batchFailure) {
            return new Release(publisher, batchFailure);
        }
    }

    private void requestObservationSnapshotPublication() {
        ObservationSnapshotPublisher publisher = observationPublisher;
        if (publisher != null) {
            publisher.requestSnapshotPublication();
        }
    }

    private static <T> T callClassified(Stage stage, Supplier<T> action) {
        try {
            return action.get();
        } catch (RuntimeException failure) {
            throw JmxRegistrationException.classify(stage.failureCode, stage.message, failure);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static void runClassified(Stage stage, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            throw JmxRegistrationException.classify(stage.failureCode, stage.message, failure);
        }
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

    private enum Stage {
        OBSERVATION_INITIALIZATION("JMX_OBSERVATION_INITIALIZATION_FAILED", "JMX observation initialization failed"),
        BATCH_REGISTRATION("JMX_BATCH_REGISTRATION_FAILED", "Runtime MBean batch registration failed"),
        PUBLISHER_START("JMX_OBSERVATION_PUBLISHER_START_FAILED", "JMX observation publisher start failed");

        private final String failureCode;
        private final String message;

        Stage(String failureCode, String message) {
            this.failureCode = failureCode;
            this.message = message;
        }
    }

    private record Release(ObservationSnapshotPublisher publisher, Throwable batchFailure) {

        private static final Release NONE = new Release(null, null);

        void suppressInto(Throwable primaryFailure) {
            if (batchFailure != null) {
                primaryFailure.addSuppressed(batchFailure);
            }

            closeSuppressing(publisher, primaryFailure);
        }

        void completeOutsideLock() {
            if (batchFailure == null) {
                if (publisher != null) {
                    publisher.close();
                }

                return;
            }

            closeSuppressing(publisher, batchFailure);
            if (batchFailure instanceof Error error) {
                throw error;
            }

            throw (RuntimeException) batchFailure;
        }
    }

    private final class Handle implements PlatformTracingMBeanRegistrar.RuntimeRegistration {

        private final UUID batchId;

        private Handle(UUID batchId) {
            this.batchId = batchId;
        }

        @Override
        public UUID batchId() {
            return batchId;
        }

        @Override
        public void close() {
            scope.callSerialized(() -> (active == this) ? releaseLocked() : Release.NONE)
                    .completeOutsideLock();
        }
    }
}
