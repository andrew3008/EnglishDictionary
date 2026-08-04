package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.PlatformDiagnosticsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.PlatformExportControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.PlatformProcessorMetricsControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.PlatformScrubbingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.MetricsSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.SpanWatchdogProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.export.ExportObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;

import static org.assertj.core.api.Assertions.assertThat;

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
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS
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

    private void registerAllDomainsWithEmptyOwnerSlots() throws Exception {
        AcceptedPolicyAccess acceptedPolicy = AcceptedPolicyAccess.isolatedSampling(
                true, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), 0.5d);
        server.registerMBean(
                new PlatformSamplingControl(acceptedPolicy, new OwnerRegistrationSlot<CompositeSampler>(), agentInstanceId),
                PlatformTracingObjectNames.SAMPLING);
        server.registerMBean(
                new PlatformScrubbingControl(new OwnerRegistrationSlot<ScrubbingSpanProcessor>(), agentInstanceId),
                PlatformTracingObjectNames.SCRUBBING);
        server.registerMBean(
                new PlatformValidationControl(new OwnerRegistrationSlot<ValidatingSpanProcessor>(), agentInstanceId),
                PlatformTracingObjectNames.VALIDATION);
        server.registerMBean(
                new PlatformExportControl(
                        new OwnerRegistrationSlot<PlatformDropOldestExportSpanProcessor>(),
                        new OwnerRegistrationSlot<SafeSpanExporter>(),
                        agentInstanceId),
                PlatformTracingObjectNames.EXPORT);
        server.registerMBean(
                new PlatformProcessorMetricsControl(
                        new OwnerRegistrationSlot<SpanWatchdogProcessor>(),
                        new OwnerRegistrationSlot<PlatformCompositeSpanProcessor>(),
                        new OwnerRegistrationSlot<MetricsSpanProcessor>(),
                        agentInstanceId),
                PlatformTracingObjectNames.PROCESSOR_METRICS);
        server.registerMBean(
                new PlatformDiagnosticsControl(new LongAdder(), agentInstanceId),
                PlatformTracingObjectNames.DIAGNOSTICS);
    }
}
