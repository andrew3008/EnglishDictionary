package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.util.Collections;
import javax.management.MBeanServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceException;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.BoundTracingSettingsSources;
import space.br1440.platform.tracing.autoconfigure.support.DomainSnapshotEndpoints;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.SamplingObservationSource;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformTracingJmxDomainHealthMetricsBinderTest {

    private static final String REGISTERED_METRIC = "platform.tracing.jmx.domain.registered";
    private static final String READABLE_METRIC = "platform.tracing.jmx.domain.readable";
    private static final String FRESHNESS_METRIC = "platform.tracing.jmx.domain.last_success_age_seconds";
    private static final String OWNER_PRESENT_METRIC = "platform.tracing.jmx.owner.present";

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private AgentReadinessCoherenceGuard coherenceGuard;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        coherenceGuard = mock(AgentReadinessCoherenceGuard.class);
        PlatformTracingJmxClient client = new PlatformTracingJmxClient(
                server,
                coherenceGuard
        );
        registry = new SimpleMeterRegistry();
        new PlatformTracingJmxDomainHealthMetricsBinder(client).bindTo(registry);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (server.isRegistered(DomainObjectNames.SAMPLING)) {
            server.unregisterMBean(DomainObjectNames.SAMPLING);
        }
    }

    @Test
    void domain_gauges_report_honest_zero_when_MBean_unregistered() {
        assertThat(gauge(REGISTERED_METRIC)).isEqualTo(0.0);
        assertThat(gauge(READABLE_METRIC)).isEqualTo(0.0);
        assertThat(gauge(FRESHNESS_METRIC)).isNaN();
    }

    @Test
    void owner_present_NaN_until_domain_read_succeeds() {
        assertThat(ownerGauge()).isNaN();
    }

    @Test
    void domain_gauges_become_1_after_registration_and_successful_read() throws Exception {
        registerSamplingMBean();

        assertThat(gauge(REGISTERED_METRIC)).isEqualTo(1.0);
        assertThat(gauge(READABLE_METRIC)).isEqualTo(1.0);
        assertThat(gauge(FRESHNESS_METRIC)).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void owner_present_0_when_domain_readable_but_owner_absent() throws Exception {
        registerSamplingMBean();

        assertThat(ownerGauge()).isEqualTo(0.0);
    }

    @Test
    void terminalCoherenceFailureProducesEmptyObservationWithoutEscapingScrape() throws Exception {
        // given
        AgentReadinessCoherenceException coherenceFailure =
                mock(AgentReadinessCoherenceException.class);
        doThrow(coherenceFailure)
                .when(coherenceGuard)
                .requirePinnedAgentInstanceId(anyString());
        when(coherenceGuard.terminalFailureCode())
                .thenReturn(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED);
        registerSamplingMBean();

        // when
        double readable = gauge(READABLE_METRIC);
        double ownerPresent = ownerGauge();

        // then
        assertThat(readable).isZero();
        assertThat(ownerPresent).isNaN();
        assertThat(coherenceGuard.terminalFailureCode())
                .isEqualTo(AgentReadinessCoherenceGuard.PINNED_ANCHOR_CHANGED);
    }

    private double gauge(String name) {
        return registry.get(name).tag("domain", "sampling").gauge().value();
    }

    private double ownerGauge() {
        return registry.get(OWNER_PRESENT_METRIC).tags("domain", "sampling", "owner", "sampler").gauge().value();
    }

    private void registerSamplingMBean() throws Exception {
        TracingSettingsSource acceptedPolicy = BoundTracingSettingsSources.sampling(
                true, Collections.emptyMap(), 0.42d
        );

        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        SamplingObservationSource impl = new SamplingObservationSource(acceptedPolicy, slot, AgentInstanceId.random());
        server.registerMBean(
                DomainSnapshotEndpoints.sampling(impl::getSamplingObservation),
                DomainObjectNames.SAMPLING
        );
    }
}
