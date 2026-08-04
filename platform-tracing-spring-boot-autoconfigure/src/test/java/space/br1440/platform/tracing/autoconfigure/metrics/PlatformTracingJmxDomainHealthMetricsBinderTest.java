package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.OwnerRegistrationSlot;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет tri-state семантику {@link PlatformTracingJmxDomainHealthMetricsBinder} на реальном
 * {@code PlatformMBeanServer}: {@code registered}/{@code readable}/{@code owner.present} должны
 * различать честный {@code 0} (домен опрошен и недоступен) от {@code NaN} (домен ещё не читался
 * успешно), а не схлопывать оба случая в один and тот же числовой ноль.
 */
class PlatformTracingJmxDomainHealthMetricsBinderTest {

    private static final String REGISTERED_METRIC = "platform.tracing.jmx.domain.registered";
    private static final String READABLE_METRIC = "platform.tracing.jmx.domain.readable";
    private static final String FRESHNESS_METRIC = "platform.tracing.jmx.domain.last_success_age_seconds";
    private static final String OWNER_PRESENT_METRIC = "platform.tracing.jmx.owner.present";

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        PlatformTracingJmxClient client = new PlatformTracingJmxClient(server);
        registry = new SimpleMeterRegistry();
        new PlatformTracingJmxDomainHealthMetricsBinder(client).bindTo(registry);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (server.isRegistered(PlatformTracingObjectNames.SAMPLING)) {
            server.unregisterMBean(PlatformTracingObjectNames.SAMPLING);
        }
    }

    @Test
    void доменные_гейджи_дают_честный_ноль_если_MBean_не_зарегистрирован() {
        assertThat(gauge(REGISTERED_METRIC, "sampling")).isEqualTo(0.0);
        assertThat(gauge(READABLE_METRIC, "sampling")).isEqualTo(0.0);
        assertThat(gauge(FRESHNESS_METRIC, "sampling")).isNaN();
    }

    @Test
    void owner_present_NaN_пока_домен_не_прочитан_успешно() {
        assertThat(ownerGauge("sampling", "sampler")).isNaN();
    }

    @Test
    void доменные_гейджи_переходят_в_1_после_регистрации_и_успешного_чтения() throws Exception {
        registerSamplingMBean();

        assertThat(gauge(REGISTERED_METRIC, "sampling")).isEqualTo(1.0);
        assertThat(gauge(READABLE_METRIC, "sampling")).isEqualTo(1.0);
        assertThat(gauge(FRESHNESS_METRIC, "sampling")).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void owner_present_0_если_домен_читаем_но_владелец_отсутствует() throws Exception {
        registerSamplingMBean();

        assertThat(ownerGauge("sampling", "sampler")).isEqualTo(0.0);
    }

    private double gauge(String name, String domain) {
        return registry.get(name).tag("domain", domain).gauge().value();
    }

    private double ownerGauge(String domain, String owner) {
        return registry.get(OWNER_PRESENT_METRIC).tags("domain", domain, "owner", owner).gauge().value();
    }

    private void registerSamplingMBean() throws Exception {
        AcceptedPolicyAccess acceptedPolicy = AcceptedPolicyAccess.isolatedSampling(
                true, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), 0.42d);
        OwnerRegistrationSlot<CompositeSampler> slot = new OwnerRegistrationSlot<>();
        PlatformSamplingControl impl = new PlatformSamplingControl(acceptedPolicy, slot, UUID.randomUUID());
        server.registerMBean(impl, PlatformTracingObjectNames.SAMPLING);
    }
}
