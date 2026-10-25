package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.PlatformSettingsStatusMBean;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PlatformSettingsMutationMBean;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotPublisher;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationObservationSource;

import lombok.experimental.UtilityClass;

@UtilityClass
final class DomainMBeanFactory {

    static DomainMBeans build(OwnerRegistrationSlots slots,
                              TracingSettingsSource acceptedPolicy,
                              LongAdder sharedInvalidConfigCounter,
                              AgentInstanceId agentInstanceId) {
        SamplingObservationSource sampling = new SamplingObservationSource(acceptedPolicy, slots.compositeSampler(), agentInstanceId);
        ScrubbingObservationSource scrubbing = new ScrubbingObservationSource(slots.scrubbing(), agentInstanceId);
        ValidationObservationSource validation = new ValidationObservationSource(slots.validating(), acceptedPolicy, agentInstanceId);
        ExportObservationSource export = new ExportObservationSource(slots.exportProcessor(), slots.safeExporter(), agentInstanceId);
        ProcessorMetricsObservationSource processorMetrics = new ProcessorMetricsObservationSource(
                slots.spanTimeout(), slots.composite(), slots.metrics(), agentInstanceId
        );
        DiagnosticsObservationSource diagnostics = new DiagnosticsObservationSource(sharedInvalidConfigCounter, agentInstanceId);

        List<MBeanBatchRegistrar.Registration> registrations = List.of(
                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.SETTINGS_STATUS,
                        new PlatformSettingsStatusMBean(acceptedPolicy.updater())
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.SAMPLING,
                        new ReadOnlyCompositeDataDynamicMBean(
                                JmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                                JmxOpenTypes.SAMPLING_OBSERVATION,
                                sampling::getSamplingObservation
                        )
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.SCRUBBING,
                        new ReadOnlyCompositeDataDynamicMBean(
                                JmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                                JmxOpenTypes.SCRUBBING_OBSERVATION,
                                scrubbing::getScrubbingObservation
                        )
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.VALIDATION,
                        new ReadOnlyCompositeDataDynamicMBean(
                                JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                                JmxOpenTypes.VALIDATION_OBSERVATION,
                                validation::getValidationObservation
                        )
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.EXPORT,
                        new ReadOnlyCompositeDataDynamicMBean(
                                JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                                JmxOpenTypes.EXPORT_OBSERVATION,
                                export::getExportObservation
                        )
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.PROCESSOR_METRICS,
                        new ReadOnlyCompositeDataDynamicMBean(
                                JmxWireContract.ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                                JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION,
                                processorMetrics::getProcessorMetricsObservation
                        )
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.DIAGNOSTICS,
                        new ReadOnlyCompositeDataDynamicMBean(
                                JmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                                JmxOpenTypes.DIAGNOSTICS_OBSERVATION,
                                diagnostics::getDiagnosticsObservation
                        )
                ),

                MBeanBatchRegistrar.Registration.dynamic(
                        DomainObjectNames.SETTINGS_MUTATION,
                        new PlatformSettingsMutationMBean(acceptedPolicy.updater())
                )
        );

        return new DomainMBeans(
                registrations,
                new ObservationSnapshotPublisher(
                        List.of(
                                sampling::publishObservationSnapshot,
                                scrubbing::publishObservationSnapshot,
                                validation::publishObservationSnapshot,
                                export::publishObservationSnapshot,
                                processorMetrics::publishObservationSnapshot,
                                diagnostics::publishObservationSnapshot
                        )
                )
        );
    }

    record DomainMBeans(List<MBeanBatchRegistrar.Registration> registrations,
                        ObservationSnapshotPublisher observationPublisher) {

        DomainMBeans {
            registrations = List.copyOf(Objects.requireNonNull(registrations, "registrations"));
            Objects.requireNonNull(observationPublisher, "observationPublisher");
        }
    }
}
