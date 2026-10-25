package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.concurrent.atomic.LongAdder;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.BoundTracingSettingsSources;
import space.br1440.platform.tracing.autoconfigure.support.DomainSnapshotEndpoints;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.diagnostics.DiagnosticsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.export.ExportObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.metrics.ProcessorMetricsObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.scrubbing.ScrubbingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.ValidationObservationSource;

import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformTracingJmxMetricsScrapeTest {

    private static final int DOMAIN_COUNT = 6;
    private static final int COALESCED_OBSERVATION_READ_COUNT = 11;

    private final MBeanServer server = MBeanServerFactory.newMBeanServer();
    private final AgentInstanceId agentInstanceId = AgentInstanceId.random();

    @BeforeEach
    void setUp() throws Exception {
        registerAllDomainsWithEmptyOwnerSlots();
    }

    @AfterEach
    void cleanup() throws Exception {
        for (ObjectName name : domainObjectNames()) {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        }
    }

    @Test
    void fullScrapeCoalescesJmxTransportCallsPerBinderDomain() throws Exception {
        // given
        MBeanServer countingServer = mock(MBeanServer.class, delegatesTo(server));
        PlatformTracingJmxClient client = new PlatformTracingJmxClient(
                countingServer,
                mock(AgentReadinessCoherenceGuard.class)
        );

        MonotonicJmxCounterGroupBridge.Ticker ticker = () -> 0L;
        CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource = new CoalescingJmxObservationSource<>(
                () -> client.diagnostics().observation(),
                ticker
        );

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PlatformTracingJmxDomainHealthMetricsBinder(client, ticker).bindTo(registry);
        new PlatformTracingSamplerMetricsBinder(client, diagnosticsSource, ticker).bindTo(registry);
        new PlatformTracingSafeWrapperMetricsBinder(diagnosticsSource).bindTo(registry);
        new PlatformTracingScrubbingMetricsBinder(client, ticker).bindTo(registry);
        new PlatformTracingExportMetricsBinder(client, ticker).bindTo(registry);
        new PlatformTracingProcessorMetricsBinder(client, ticker).bindTo(registry);

        // when
        for (Meter meter : registry.getMeters()) {
            meter.measure().forEach(Measurement::getValue);
        }

        // then
        verify(countingServer, times(COALESCED_OBSERVATION_READ_COUNT)).getMBeanInfo(any(ObjectName.class));
        verify(countingServer, times(COALESCED_OBSERVATION_READ_COUNT))
                .getAttribute(any(ObjectName.class), anyString());
        verify(countingServer, times(DOMAIN_COUNT)).isRegistered(any(ObjectName.class));
    }

    private void registerAllDomainsWithEmptyOwnerSlots() throws Exception {
        TracingSettingsSource acceptedPolicy = BoundTracingSettingsSources.sampling(
                true,
                Collections.emptyMap(),
                0.5d
        );

        SamplingObservationSource sampling = new SamplingObservationSource(
                acceptedPolicy,
                new OwnerRegistrationSlot<>(),
                agentInstanceId
        );

        ScrubbingObservationSource scrubbing = new ScrubbingObservationSource(
                new OwnerRegistrationSlot<>(),
                agentInstanceId
        );

        ValidationObservationSource validation = new ValidationObservationSource(
                new OwnerRegistrationSlot<>(),
                acceptedPolicy,
                agentInstanceId
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

    private static ObjectName[] domainObjectNames() {
        return new ObjectName[]{
                DomainObjectNames.SAMPLING,
                DomainObjectNames.SCRUBBING,
                DomainObjectNames.VALIDATION,
                DomainObjectNames.EXPORT,
                DomainObjectNames.PROCESSOR_METRICS,
                DomainObjectNames.DIAGNOSTICS
        };
    }
}
