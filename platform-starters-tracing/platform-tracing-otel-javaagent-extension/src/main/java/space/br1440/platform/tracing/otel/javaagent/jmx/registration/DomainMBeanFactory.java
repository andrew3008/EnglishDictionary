package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationControl;

import lombok.experimental.UtilityClass;

@UtilityClass
final class DomainMBeanFactory {

    static List<MBeanBatchRegistrar.Registration> build(OwnerRegistrationSlots slots,
                                                        TracingSettingsSource acceptedPolicy,
                                                        LongAdder sharedInvalidConfigCounter,
                                                        UUID agentInstanceId) {
        return List.of(
                new MBeanBatchRegistrar.Registration(DomainObjectNames.SAMPLING,
                        new SamplingControl(acceptedPolicy, slots.compositeSampler(), agentInstanceId)
                ),

                new MBeanBatchRegistrar.Registration(DomainObjectNames.SCRUBBING,
                        new ScrubbingControl(slots.scrubbing(), agentInstanceId)
                ),

                new MBeanBatchRegistrar.Registration(DomainObjectNames.VALIDATION,
                        new ValidationControl(slots.validating(), agentInstanceId)
                ),

                new MBeanBatchRegistrar.Registration(DomainObjectNames.EXPORT,
                        new ExportControl(slots.exportProcessor(), slots.safeExporter(), agentInstanceId)
                ),

                new MBeanBatchRegistrar.Registration(DomainObjectNames.PROCESSOR_METRICS,
                        new ProcessorMetricsControl(slots.watchdog(), slots.composite(), slots.metrics(), agentInstanceId)
                ),

                new MBeanBatchRegistrar.Registration(DomainObjectNames.DIAGNOSTICS,
                        new DiagnosticsControl(sharedInvalidConfigCounter, agentInstanceId)
                )
        );
    }
}
