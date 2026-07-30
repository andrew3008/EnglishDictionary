package space.br1440.platform.tracing.autoconfigure.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.PlatformTracingObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for {@link PlatformTracingJmxClient}: availability detection
 * and graceful read degradation when domain MBeans are absent.
 */
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
    void allMBeansAvailable_false_если_ни_один_не_зарегистрирован() {
        assertThat(client.allMBeansAvailable()).isFalse();
    }

    @Test
    void getMBeansStatus_все_false_если_ни_один_не_зарегистрирован() {
        Map<TracingControlDomain, Boolean> status = client.getMBeansStatus();

        assertThat(status).containsKeys(
                TracingControlDomain.SAMPLING, TracingControlDomain.SCRUBBING,
                TracingControlDomain.VALIDATION, TracingControlDomain.EXPORT,
                TracingControlDomain.PROCESSOR_METRICS, TracingControlDomain.DIAGNOSTICS);
        assertThat(status.values()).allMatch(v -> !v);
    }

    @Test
    void getMBeansStatus_sampling_true_после_регистрации_только_sampling() throws Exception {
        registerSamplingMBean();

        Map<TracingControlDomain, Boolean> status = client.getMBeansStatus();

        assertThat(status.get(TracingControlDomain.SAMPLING)).isTrue();
        assertThat(status.get(TracingControlDomain.SCRUBBING)).isFalse();
    }

    @Test
    void getCurrentRatio_возвращает_empty_если_sampling_недоступен() {
        assertThat(client.getCurrentRatio()).isEmpty();
    }

    @Test
    void isSamplerEnabled_возвращает_empty_если_недоступен() {
        assertThat(client.isSamplerEnabled()).isEmpty();
    }

    @Test
    void getLiveDropPathPrefixes_возвращает_empty_если_недоступен() {
        assertThat(client.getLiveDropPathPrefixes()).isEmpty();
    }

    @Test
    void getLiveRouteRatios_возвращает_empty_если_недоступен() {
        assertThat(client.getLiveRouteRatios()).isEmpty();
    }

    @Test
    void getSamplerDecisionCounts_возвращает_пустую_карту_если_недоступен() {
        assertThat(client.getSamplerDecisionCounts()).isEmpty();
    }

    @Test
    void getInvalidConfigCount_возвращает_empty_если_diagnostics_недоступен() {
        assertThat(client.getInvalidConfigCount()).isEmpty();
    }

    @Test
    void getScrubbingMetrics_возвращает_пустую_карту_если_недоступен() {
        assertThat(client.getScrubbingMetrics()).isEmpty();
    }

    @Test
    void getProcessorErrorsByName_возвращает_пустую_карту_если_недоступен() {
        assertThat(client.getProcessorErrorsByName()).isEmpty();
    }

    @Test
    void getConfigAuditTrail_возвращает_пустой_список_если_недоступен() {
        assertThat(client.getConfigAuditTrail()).isEmpty();
    }

    @Test
    void isSamplerEnabled_возвращает_присутствующее_значение_после_регистрации() throws Exception {
        registerSamplingMBean();

        Optional<Boolean> enabled = client.isSamplerEnabled();
        assertThat(enabled).isPresent();
        assertThat(enabled.get()).isTrue();
    }

    @Test
    void getCurrentRatio_возвращает_значение_если_sampling_зарегистрирован() throws Exception {
        registerSamplingMBean();

        Optional<Double> ratio = client.getCurrentRatio();
        assertThat(ratio).isPresent();
        assertThat(ratio.get()).isEqualTo(0.42d);
    }

    @Test
    void allMBeansAvailable_true_после_регистрации_sampling() throws Exception {
        registerSamplingMBean();
        assertThat(client.allMBeansAvailable()).isFalse();
        assertThat(client.getMBeansStatus().get(TracingControlDomain.SAMPLING)).isTrue();
    }

    @Test
    void isAvailable_является_алиасом_allMBeansAvailable() {
        assertThat(client.isAvailable()).isEqualTo(client.allMBeansAvailable());
    }

    private void registerSamplingMBean() throws Exception {
        AcceptedPolicyAccess acceptedPolicy = AcceptedPolicyAccess.isolatedSampling(
                true, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap(), 0.42d);
        CompositeSampler sampler = new CompositeSampler(acceptedPolicy);
        PlatformSamplingControl impl = new PlatformSamplingControl(acceptedPolicy, sampler);
        server.registerMBean(impl, PlatformTracingObjectNames.SAMPLING);
    }
}
