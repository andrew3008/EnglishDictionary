package space.br1440.platform.tracing.otel.javaagent.jmx.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationObservationSource;

class ObservationSnapshotSourcesTest {

    private static final AgentInstanceId AGENT_INSTANCE_ID = AgentInstanceId.random();

    @Test
    void allGettersReturnPrecomputedSnapshotsUntilExplicitPublication() {
        // given
        SamplingObservationSource sampling = new SamplingObservationSource(
                TracingSettingsSourceTestFixtures.boundSampling(AgentInstanceId.random(), true, Map.of(), 1.0d),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID
        );

        ScrubbingObservationSource scrubbing = new ScrubbingObservationSource(
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID
        );

        ValidationObservationSource validation = new ValidationObservationSource(
                new OwnerRegistrationSlot<>(),
                TracingSettingsSourceTestFixtures.boundDefaults(AgentInstanceId.random()),
                AGENT_INSTANCE_ID
        );

        ExportObservationSource export = new ExportObservationSource(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID
        );

        ProcessorMetricsObservationSource processorMetrics = new ProcessorMetricsObservationSource(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                AGENT_INSTANCE_ID
        );

        DiagnosticsObservationSource diagnostics = new DiagnosticsObservationSource(new LongAdder(), AGENT_INSTANCE_ID);
        List<Probe> probes = List.of(
                new Probe(
                        sampling::publishObservationSnapshot, sampling::getSamplingObservation,
                        JmxOpenTypes.SAMPLING_OBSERVATION
                ),

                new Probe(
                        scrubbing::publishObservationSnapshot, scrubbing::getScrubbingObservation,
                        JmxOpenTypes.SCRUBBING_OBSERVATION
                ),

                new Probe(
                        validation::publishObservationSnapshot, validation::getValidationObservation,
                        JmxOpenTypes.VALIDATION_OBSERVATION
                ),

                new Probe(
                        export::publishObservationSnapshot, export::getExportObservation,
                        JmxOpenTypes.EXPORT_OBSERVATION
                ),

                new Probe(
                        processorMetrics::publishObservationSnapshot,
                        processorMetrics::getProcessorMetricsObservation,
                        JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION
                ),

                new Probe(
                        diagnostics::publishObservationSnapshot, diagnostics::getDiagnosticsObservation,
                        JmxOpenTypes.DIAGNOSTICS_OBSERVATION
                )
        );

        // when
        for (Probe probe : probes) {
            CompositeData first = probe.getter().get();
            CompositeData repeated = probe.getter().get();
            probe.publish().run();
            CompositeData refreshed = probe.getter().get();

            // then
            assertThat(repeated).isSameAs(first);
            assertThat(first.getCompositeType()).isEqualTo(probe.expectedType());
            assertThat(refreshed).isNotSameAs(first);
        }
    }

    private record Probe(Runnable publish,
                         Supplier<CompositeData> getter,
                         CompositeType expectedType) {
    }
}
