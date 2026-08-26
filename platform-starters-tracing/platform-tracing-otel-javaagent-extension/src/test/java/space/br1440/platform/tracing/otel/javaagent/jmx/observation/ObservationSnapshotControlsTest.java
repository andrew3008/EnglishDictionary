package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationControl;

class ObservationSnapshotControlsTest {

    private static final UUID AGENT_INSTANCE_ID = UUID.randomUUID();

    @Test
    void allGettersReturnPrecomputedSnapshotsUntilExplicitPublication() {
        // given
        SamplingControl sampling = new SamplingControl(
                TracingSettingsSource.isolatedSampling(true, Map.of(), 1.0d),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);
        ScrubbingControl scrubbing = new ScrubbingControl(new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);
        ValidationControl validation = new ValidationControl(new OwnerRegistrationSlot<>(), AGENT_INSTANCE_ID);
        ExportControl export = new ExportControl(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);
        ProcessorMetricsControl processorMetrics = new ProcessorMetricsControl(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID);
        DiagnosticsControl diagnostics = new DiagnosticsControl(new LongAdder(), AGENT_INSTANCE_ID);
        List<Probe> probes = List.of(
                new Probe(
                        sampling,
                        sampling::getSamplingObservation,
                        GeneratedJmxOpenTypes.SAMPLING_OBSERVATION,
                        GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_CAPTURE_SEQUENCE),
                new Probe(scrubbing, scrubbing::getScrubbingObservation,
                        GeneratedJmxOpenTypes.SCRUBBING_OBSERVATION,
                        GeneratedJmxWireContract.FIELD_SCRUBBING_OBSERVATION_CAPTURE_SEQUENCE),
                new Probe(validation, validation::getValidationObservation,
                        GeneratedJmxOpenTypes.VALIDATION_OBSERVATION,
                        GeneratedJmxWireContract.FIELD_VALIDATION_OBSERVATION_CAPTURE_SEQUENCE),
                new Probe(
                        export,
                        export::getExportObservation,
                        GeneratedJmxOpenTypes.EXPORT_OBSERVATION,
                        GeneratedJmxWireContract.FIELD_EXPORT_OBSERVATION_CAPTURE_SEQUENCE),
                new Probe(processorMetrics, processorMetrics::getProcessorMetricsObservation,
                        GeneratedJmxOpenTypes.PROCESSOR_METRICS_OBSERVATION,
                        GeneratedJmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE),
                new Probe(diagnostics, diagnostics::getDiagnosticsObservation,
                        GeneratedJmxOpenTypes.DIAGNOSTICS_OBSERVATION,
                        GeneratedJmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_CAPTURE_SEQUENCE));

        // when
        for (Probe probe : probes) {
            CompositeData first = probe.getter().get();
            CompositeData repeated = probe.getter().get();
            probe.control().publishObservationSnapshot();
            CompositeData refreshed = probe.getter().get();

            // then
            assertThat(repeated).isSameAs(first);
            assertThat(first.getCompositeType()).isEqualTo(probe.expectedType());
            assertThat(refreshed).isNotSameAs(first);
            assertThat((Long) refreshed.get(probe.captureSequenceField()))
                    .isEqualTo((Long) first.get(probe.captureSequenceField()) + 1L);
        }
    }

    private record Probe(ObservationSnapshotControl control,
                         Supplier<CompositeData> getter,
                         CompositeType expectedType,
                         String captureSequenceField) {
    }
}
