package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.support.DomainSnapshotEndpoints;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTracingJmxClientTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private PlatformTracingJmxClient client;

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
    void registrationSnapshot_all_NOT_REGISTERED_when_no_MBean_is_registered() {
        JmxMBeanRegistrationSnapshot snapshot = client.registrationSnapshot();
        assertThat(snapshot.allRegistered()).isFalse();
        assertThat(snapshot.sampling()).isEqualTo(JmxMBeanRegistrationState.NOT_REGISTERED);
        assertThat(snapshot.diagnostics()).isEqualTo(JmxMBeanRegistrationState.NOT_REGISTERED);
    }

    @Test
    void sampling_observation_empty_when_MBean_unavailable() {
        assertThat(client.sampling().observation()).isEmpty();
    }

    @Test
    void scrubbing_observation_empty_when_MBean_unavailable() {
        assertThat(client.scrubbing().observation()).isEmpty();
    }

    @Test
    void diagnostics_observation_empty_when_MBean_unavailable() {
        assertThat(client.diagnostics().observation()).isEmpty();
    }

    @Test
    void export_observation_empty_when_MBean_unavailable() {
        assertThat(client.export().observation()).isEmpty();
    }

    @Test
    void jmxContractDiagnostics_empty_until_first_contract_violation() {
        List<JmxDiagnosticSnapshot> diagnostics = client.jmxContractDiagnostics();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void sampling_observation_returns_value_after_registration() throws Exception {
        registerSamplingMBean();

        Optional<SamplingObservation> observation = client.sampling().observation();
        assertThat(observation).isPresent();
        assertThat(observation.get().samplerEnabled()).isTrue();
        assertThat(observation.get().samplingRatio()).isEqualTo(0.42d);
        assertThat(observation.get().liveRouteRatioCount()).isZero();
    }

    @Test
    void registrationSnapshot_REGISTERED_only_for_sampling_after_its_registration() throws Exception {
        registerSamplingMBean();

        JmxMBeanRegistrationSnapshot snapshot = client.registrationSnapshot();
        assertThat(snapshot.sampling()).isEqualTo(JmxMBeanRegistrationState.REGISTERED);
        assertThat(snapshot.allRegistered()).isFalse();
    }

    @Test
    void decisionCounts_owner_absent_when_composite_sampler_not_attached() throws Exception {
        registerSamplingMBean();

        Optional<SamplingObservation> observation = client.sampling().observation();
        assertThat(observation).isPresent();
        assertThat(observation.get().decisionCounts()).isInstanceOf(JmxOwnerState.Absent.class);
    }

    private void registerSamplingMBean() throws Exception {
        TracingSettingsSource acceptedPolicy = TracingSettingsSource.isolatedSampling(
                true, Collections.emptyMap(), 0.42d
        );

        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        SamplingObservationSource impl = new SamplingObservationSource(acceptedPolicy, slot, UUID.randomUUID());
        server.registerMBean(
                DomainSnapshotEndpoints.sampling(impl::getSamplingObservation),
                DomainObjectNames.SAMPLING);
    }
}
