package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.concurrent.atomic.LongAdder;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.support.BoundTracingSettingsSources;
import space.br1440.platform.tracing.autoconfigure.support.DomainSnapshotEndpoints;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationObservationSource;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

class DomainAbsentOwnerEncodingRoundTripTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private PlatformTracingJmxClient client;
    private final AgentInstanceId agentInstanceId = AgentInstanceId.random();

    @BeforeEach
    void setUp() {
        client = new PlatformTracingJmxClient(server);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (ObjectName name : new ObjectName[]{
                DomainObjectNames.SAMPLING,
                DomainObjectNames.SCRUBBING,
                DomainObjectNames.VALIDATION,
                DomainObjectNames.EXPORT,
                DomainObjectNames.PROCESSOR_METRICS,
                DomainObjectNames.DIAGNOSTICS
        }) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    @Test
    void unregisteredMBeanIsTransportAbsent() {
        assertThat(client.sampling().registration()).isEqualTo(JmxMBeanRegistrationState.NOT_REGISTERED);
        assertThat(client.sampling().observation()).isEmpty();
        assertThat(client.jmxContractDiagnostics()).isEmpty();
    }

    @Test
    void registeredDomainsWithEmptySlotsDecodeAsPresentObservationAndAbsentOwners() throws Exception {
        registerAllDomainsWithEmptyOwnerSlots();

        String expectedAgentInstanceId = agentInstanceId.toString();

        assertThat(client.sampling().observation()).isPresent();
        assertThat(client.sampling().observation().orElseThrow().decisionCounts())
                .isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.sampling().observation().orElseThrow().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.scrubbing().observation()).isPresent();
        assertThat(client.scrubbing().observation().orElseThrow().owner())
                .isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.scrubbing().observation().orElseThrow().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.validation().observation()).isPresent();
        assertThat(client.validation().observation().orElseThrow().owner())
                .isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.validation().observation().orElseThrow().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.export().observation()).isPresent();
        ExportObservation export = client.export().observation().orElseThrow();
        assertThat(export.processor()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(export.exporter()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(export.agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.processorMetrics().observation()).isPresent();
        ProcessorMetricsObservation processor = client.processorMetrics().observation().orElseThrow();
        assertThat(processor.spanTimeout()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.composite()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.metrics()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.capture().agentInstanceId()).isEqualTo(expectedAgentInstanceId);
        assertThat(processor.capture().sequence()).isPositive();
        assertThat(processor.capture().startedAtEpochMilli()).isPositive();
        assertThat(processor.capture().completedAtEpochMilli())
                .isGreaterThanOrEqualTo(processor.capture().startedAtEpochMilli());

        assertThat(client.diagnostics().observation()).isPresent();
        assertThat(client.diagnostics().observation().orElseThrow().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.sampling().lastSuccessAge()).isPresent();
        assertThat(client.export().lastSuccessAge()).isPresent();
        assertThat(client.jmxContractDiagnostics()).isEmpty();
    }

    @Test
    void validationViolationCounterRoundTripsFromAgentOwner() throws Exception {
        OwnerRegistrationSlot<ValidatingSpanProcessor> slot = new OwnerRegistrationSlot<>();
        TracingSettingsSource acceptedPolicy = BoundTracingSettingsSources.validation(SpanAttributeValidationLevel.WARNING);
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(acceptedPolicy);
        slot.replace(processor);
        processor.onEnding(mock(ReadWriteSpan.class));
        ValidationObservationSource validation = new ValidationObservationSource(slot, acceptedPolicy, agentInstanceId);
        server.registerMBean(
                DomainSnapshotEndpoints.validation(validation::getValidationObservation),
                DomainObjectNames.VALIDATION
        );

        ValidationObservation observation = client.validation().observation().orElseThrow();
        assertThat(observation.owner()).isInstanceOf(JmxOwnerState.Present.class);
        JmxOwnerState.Present<ValidationValues> owner =
                (JmxOwnerState.Present<ValidationValues>) observation.owner();
        assertThat(owner.values().violationsTotal()).isEqualTo(1L);
        assertThat(client.jmxContractDiagnostics()).isEmpty();
    }

    private void registerAllDomainsWithEmptyOwnerSlots() throws Exception {
        TracingSettingsSource acceptedPolicy = BoundTracingSettingsSources.sampling(
                true, Collections.emptyMap(), 0.5d
        );
        SamplingObservationSource sampling = new SamplingObservationSource(
                acceptedPolicy, new OwnerRegistrationSlot<>(), agentInstanceId
        );
        ScrubbingObservationSource scrubbing = new ScrubbingObservationSource(new OwnerRegistrationSlot<>(), agentInstanceId);
        ValidationObservationSource validation = new ValidationObservationSource(
                new OwnerRegistrationSlot<>(), acceptedPolicy, agentInstanceId
        );
        ExportObservationSource export = new ExportObservationSource(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                agentInstanceId
        );
        ProcessorMetricsObservationSource processorMetrics = new ProcessorMetricsObservationSource(
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                new OwnerRegistrationSlot<>(),
                agentInstanceId
        );
        DiagnosticsObservationSource diagnostics = new DiagnosticsObservationSource(new LongAdder(), agentInstanceId);

        server.registerMBean(
                DomainSnapshotEndpoints.sampling(sampling::getSamplingObservation),
                DomainObjectNames.SAMPLING
        );

        server.registerMBean(
                DomainSnapshotEndpoints.scrubbing(scrubbing::getScrubbingObservation),
                DomainObjectNames.SCRUBBING
        );

        server.registerMBean(
                DomainSnapshotEndpoints.validation(validation::getValidationObservation),
                DomainObjectNames.VALIDATION
        );

        server.registerMBean(
                DomainSnapshotEndpoints.export(export::getExportObservation),
                DomainObjectNames.EXPORT
        );

        server.registerMBean(
                DomainSnapshotEndpoints.processorMetrics(processorMetrics::getProcessorMetricsObservation),
                DomainObjectNames.PROCESSOR_METRICS
        );

        server.registerMBean(
                DomainSnapshotEndpoints.diagnostics(diagnostics::getDiagnosticsObservation),
                DomainObjectNames.DIAGNOSTICS
        );
    }
}
