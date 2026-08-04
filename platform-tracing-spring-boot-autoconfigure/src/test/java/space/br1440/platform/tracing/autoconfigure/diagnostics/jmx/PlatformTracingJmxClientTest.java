package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
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
    void registrationSnapshot_все_NOT_REGISTERED_если_ни_один_MBean_не_зарегистрирован() {
        JmxMBeanRegistrationSnapshot snapshot = client.registrationSnapshot();
        assertThat(snapshot.allRegistered()).isFalse();
        assertThat(snapshot.sampling()).isEqualTo(JmxMBeanRegistrationState.NOT_REGISTERED);
        assertThat(snapshot.diagnostics()).isEqualTo(JmxMBeanRegistrationState.NOT_REGISTERED);
    }

    @Test
    void sampling_observation_пусто_если_MBean_недоступен() {
        assertThat(client.sampling().observation()).isEmpty();
    }

    @Test
    void scrubbing_observation_пусто_если_MBean_недоступен() {
        assertThat(client.scrubbing().observation()).isEmpty();
    }

    @Test
    void diagnostics_observation_пусто_если_MBean_недоступен() {
        assertThat(client.diagnostics().observation()).isEmpty();
    }

    @Test
    void export_observation_пусто_если_MBean_недоступен() {
        assertThat(client.export().observation()).isEmpty();
    }

    @Test
    void jmxContractDiagnostics_пуст_до_первого_нарушения_контракта() {
        List<JmxDiagnosticSnapshot> diagnostics = client.jmxContractDiagnostics();
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void sampling_observation_возвращает_значение_после_регистрации() throws Exception {
        registerSamplingMBean();

        Optional<SamplingObservation> observation = client.sampling().observation();
        assertThat(observation).isPresent();
        assertThat(observation.get().samplerEnabled()).isTrue();
        assertThat(observation.get().samplingRatio()).isEqualTo(0.42d);
        assertThat(observation.get().liveRouteRatioCount()).isZero();
    }

    @Test
    void registrationSnapshot_REGISTERED_только_для_sampling_после_его_регистрации() throws Exception {
        registerSamplingMBean();

        JmxMBeanRegistrationSnapshot snapshot = client.registrationSnapshot();
        assertThat(snapshot.sampling()).isEqualTo(JmxMBeanRegistrationState.REGISTERED);
        assertThat(snapshot.allRegistered()).isFalse();
    }

    @Test
    void decisionCounts_owner_absent_если_composite_sampler_не_подключён() throws Exception {
        registerSamplingMBean();

        Optional<SamplingObservation> observation = client.sampling().observation();
        assertThat(observation).isPresent();
        assertThat(observation.get().decisionCounts()).isInstanceOf(JmxOwnerState.Absent.class);
    }

    private void registerSamplingMBean() throws Exception {
        AcceptedPolicyAccess acceptedPolicy = AcceptedPolicyAccess.isolatedSampling(
                true, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), 0.42d
        );

        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        PlatformSamplingControl impl = new PlatformSamplingControl(acceptedPolicy, slot, UUID.randomUUID());
        server.registerMBean(impl, PlatformTracingObjectNames.SAMPLING);
    }
}
