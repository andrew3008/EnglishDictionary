package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.PlatformSettingsStatusMBean;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PlatformSettingsMutationMBean;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.ObservationSnapshotPublisher;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationControl;

import lombok.experimental.UtilityClass;

@UtilityClass
final class DomainMBeanFactory {

    static DomainMBeans build(OwnerRegistrationSlots slots,
                              TracingSettingsSource acceptedPolicy,
                              LongAdder sharedInvalidConfigCounter,
                              UUID agentInstanceId) {
        SamplingControl sampling = new SamplingControl(acceptedPolicy, slots.compositeSampler(), agentInstanceId);
        ScrubbingControl scrubbing = new ScrubbingControl(slots.scrubbing(), agentInstanceId);
        ValidationControl validation = new ValidationControl(slots.validating(), agentInstanceId);
        ExportControl export = new ExportControl(slots.exportProcessor(), slots.safeExporter(), agentInstanceId);
        ProcessorMetricsControl processorMetrics = new ProcessorMetricsControl(
                slots.watchdog(), slots.composite(), slots.metrics(), agentInstanceId);
        DiagnosticsControl diagnostics = new DiagnosticsControl(sharedInvalidConfigCounter, agentInstanceId);

        List<MBeanBatchRegistrar.Registration> registrations = List.of(
                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.SETTINGS_STATUS,
                        new PlatformSettingsStatusMBean(acceptedPolicy.updater())
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.SAMPLING,
                        new ReadOnlyCompositeDataDynamicMBean(
                                GeneratedJmxWireContract
                                        .ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                                GeneratedJmxOpenTypes.SAMPLING_OBSERVATION,
                                sampling::getSamplingObservation)
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.SCRUBBING,
                        new ReadOnlyCompositeDataDynamicMBean(
                                GeneratedJmxWireContract
                                        .ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                                GeneratedJmxOpenTypes.SCRUBBING_OBSERVATION,
                                scrubbing::getScrubbingObservation)
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.VALIDATION,
                        new ReadOnlyCompositeDataDynamicMBean(
                                GeneratedJmxWireContract
                                        .ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                                GeneratedJmxOpenTypes.VALIDATION_OBSERVATION,
                                validation::getValidationObservation)
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.EXPORT,
                        new ReadOnlyCompositeDataDynamicMBean(
                                GeneratedJmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                                GeneratedJmxOpenTypes.EXPORT_OBSERVATION,
                                export::getExportObservation)
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.PROCESSOR_METRICS,
                        new ReadOnlyCompositeDataDynamicMBean(
                                GeneratedJmxWireContract
                                        .ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                                GeneratedJmxOpenTypes.PROCESSOR_METRICS_OBSERVATION,
                                processorMetrics::getProcessorMetricsObservation)
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.DIAGNOSTICS,
                        new ReadOnlyCompositeDataDynamicMBean(
                                GeneratedJmxWireContract
                                        .ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                                GeneratedJmxOpenTypes.DIAGNOSTICS_OBSERVATION,
                                diagnostics::getDiagnosticsObservation)
                ),

                MBeanBatchRegistrar.Registration.dynamic(DomainObjectNames.SETTINGS_MUTATION,
                        new PlatformSettingsMutationMBean(acceptedPolicy.updater())
                )
        );
        List<ObservationSnapshotControl> observations = List.of(
                sampling,
                scrubbing,
                validation,
                export,
                processorMetrics,
                diagnostics);
        return new DomainMBeans(registrations, new ObservationSnapshotPublisher(observations));
    }

    record DomainMBeans(List<MBeanBatchRegistrar.Registration> registrations,
                        ObservationSnapshotPublisher observationPublisher) {

        DomainMBeans {
            registrations = List.copyOf(Objects.requireNonNull(registrations, "registrations"));
            observationPublisher = Objects.requireNonNull(observationPublisher, "observationPublisher");
        }
    }
}
