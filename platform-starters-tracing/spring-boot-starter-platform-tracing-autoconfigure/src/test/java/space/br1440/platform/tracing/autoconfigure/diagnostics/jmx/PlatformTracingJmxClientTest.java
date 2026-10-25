package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.management.MBeanServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.sampling.SamplingObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxMBeanRegistrationState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.support.BoundTracingSettingsSources;
import space.br1440.platform.tracing.autoconfigure.support.DomainSnapshotEndpoints;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

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
    void constructorWithoutVerifierIsPackagePrivate() throws Exception {
        // given
        int serverConstructor = PlatformTracingJmxClient.class
                .getDeclaredConstructor(MBeanServer.class)
                .getModifiers();

        // when
        boolean serverConstructorHasPackageAccess = hasPackageAccess(serverConstructor);
        boolean noArgumentConstructorExists = false;
        for (Constructor<?> constructor : PlatformTracingJmxClient.class.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 0) {
                noArgumentConstructorExists = true;
                break;
            }
        }

        // then
        assertThat(serverConstructorHasPackageAccess).isTrue();
        assertThat(noArgumentConstructorExists).isFalse();
    }

    @Test
    void sampling_observation_returns_value_after_registration() throws Exception {
        registerSamplingMBean();

        Optional<SamplingObservation> observation = client.sampling().observation();
        assertThat(observation).isPresent();
        assertThat(observation.get().samplerEnabled()).isTrue();
        assertThat(observation.get().samplingRatio()).isEqualTo(0.42d);
        assertThat(observation.get().routeRatioCount()).isZero();
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
        TracingSettingsSource acceptedPolicy = BoundTracingSettingsSources.sampling(
                true, Collections.emptyMap(), 0.42d
        );

        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        SamplingObservationSource impl = new SamplingObservationSource(acceptedPolicy, slot, AgentInstanceId.random());
        server.registerMBean(
                DomainSnapshotEndpoints.sampling(impl::getSamplingObservation),
                DomainObjectNames.SAMPLING);
    }

    private static boolean hasPackageAccess(int modifiers) {
        return !Modifier.isPublic(modifiers)
                && !Modifier.isProtected(modifiers)
                && !Modifier.isPrivate(modifiers);
    }
}
