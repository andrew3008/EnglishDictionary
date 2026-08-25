package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;
import javax.management.MBeanServer;

import io.opentelemetry.sdk.trace.ReadWriteSpan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanLimitsMetricsProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.watchdog.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

/**
 * Absent-owner encoding end-to-end на живом {@code PlatformMBeanServer}: агент кодирует
 * presence-дискриминатор, {@code *JmxView} декодирует в {@link JmxOwnerState.Absent},
 * {@code domain.readable} остаётся {@code 1}, {@code owner.present} — {@code 0},
 * contract violation не записывается. Незарегистрированный MBean по-прежнему даёт
 * transport-{@code Absent} и {@code readable = 0}.
 */
class DomainAbsentOwnerEncodingRoundTripTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private PlatformTracingJmxClient client;
    private final UUID agentInstanceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        client = new PlatformTracingJmxClient(server);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var name : new javax.management.ObjectName[]{
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
        assertThat(client.sampling().observation().get().decisionCounts())
                .isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.sampling().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.scrubbing().observation()).isPresent();
        assertThat(client.scrubbing().observation().get().owner())
                .isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.scrubbing().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.validation().observation()).isPresent();
        assertThat(client.validation().observation().get().owner())
                .isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(client.validation().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.export().observation()).isPresent();
        ExportObservation export = client.export().observation().get();
        assertThat(export.processor()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(export.exporter()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(export.agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.processorMetrics().observation()).isPresent();
        ProcessorMetricsObservation processor = client.processorMetrics().observation().get();
        assertThat(processor.watchdog()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.composite()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.metrics()).isInstanceOf(JmxOwnerState.Absent.class);
        assertThat(processor.agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        assertThat(client.diagnostics().observation()).isPresent();
        assertThat(client.diagnostics().observation().get().agentInstanceId()).isEqualTo(expectedAgentInstanceId);

        // Observation присутствует (transport Present), владельцы Absent, CV не записывается.
        assertThat(client.sampling().lastSuccessAge()).isPresent();
        assertThat(client.export().lastSuccessAge()).isPresent();
        assertThat(client.jmxContractDiagnostics()).isEmpty();
    }

    @Test
    void validationViolationCounterRoundTripsFromAgentOwner() throws Exception {
        OwnerRegistrationSlot<ValidatingSpanProcessor> slot = new OwnerRegistrationSlot<>();
        ValidatingSpanProcessor processor = new ValidatingSpanProcessor(
                TracingSettingsSource.isolatedValidation(true, false, false));
        slot.replace(processor);
        processor.onEnding(mock(ReadWriteSpan.class));
        server.registerMBean(
                new ValidationControl(slot, agentInstanceId),
                DomainObjectNames.VALIDATION);

        ValidationObservation observation = client.validation().observation().orElseThrow();
        assertThat(observation.owner()).isInstanceOf(JmxOwnerState.Present.class);
        @SuppressWarnings("unchecked")
        JmxOwnerState.Present<ValidationValues> owner =
                (JmxOwnerState.Present<ValidationValues>) observation.owner();
        assertThat(owner.values().violationsTotal()).isEqualTo(1L);
        assertThat(client.jmxContractDiagnostics()).isEmpty();
    }

    private void registerAllDomainsWithEmptyOwnerSlots() throws Exception {
        TracingSettingsSource acceptedPolicy = TracingSettingsSource.isolatedSampling(
                true, Collections.emptyMap(), 0.5d);
        server.registerMBean(
                new SamplingControl(acceptedPolicy, new OwnerRegistrationSlot<CompositeSampler>(), agentInstanceId),
                DomainObjectNames.SAMPLING);
        server.registerMBean(
                new ScrubbingControl(new OwnerRegistrationSlot<>(), agentInstanceId),
                DomainObjectNames.SCRUBBING);
        server.registerMBean(
                new ValidationControl(new OwnerRegistrationSlot<ValidatingSpanProcessor>(), agentInstanceId),
                DomainObjectNames.VALIDATION);
        server.registerMBean(
                new ExportControl(
                        new OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor>(),
                        new OwnerRegistrationSlot<SafeSpanExporter>(),
                        agentInstanceId),
                DomainObjectNames.EXPORT);
        server.registerMBean(
                new ProcessorMetricsControl(
                        new OwnerRegistrationSlot<SpanWatchdogProcessor>(),
                        new OwnerRegistrationSlot<PlatformCompositeSpanProcessor>(),
                        new OwnerRegistrationSlot<SpanLimitsMetricsProcessor>(),
                        agentInstanceId),
                DomainObjectNames.PROCESSOR_METRICS);
        server.registerMBean(
                new DiagnosticsControl(new LongAdder(), agentInstanceId),
                DomainObjectNames.DIAGNOSTICS);
    }
}
