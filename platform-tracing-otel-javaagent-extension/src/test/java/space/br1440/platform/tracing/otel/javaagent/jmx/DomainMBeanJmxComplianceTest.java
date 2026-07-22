package space.br1440.platform.tracing.otel.javaagent.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * JMX Standard-MBean compliance: each of the six domain MBeans registers successfully,
 * responds to getMBeanInfo(), exposes expected operations/attributes, and does NOT register
 * under the old monolith ObjectName.
 */
class DomainMBeanJmxComplianceTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();

    @AfterEach
    void cleanup() {
        new PlatformTracingJmxRegistrar().unregisterAllMBeans();
    }

    @Test
    void все_шесть_доменных_MBean_регистрируются_без_ошибок() {
        SamplerStateHolder holder = holderWith(0.5);
        PlatformTracingJmxRegistrar registrar = new PlatformTracingJmxRegistrar();

        assertThatNoException().isThrownBy(() -> registrar.setConfigHolder(holder));

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isTrue();
    }

    @Test
    void sampling_MBean_getMBeanInfo_содержит_атрибут_SamplingRatio() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        MBeanInfo info = server.getMBeanInfo(PlatformTracingObjectNames.SAMPLING);

        assertThat(info).isNotNull();
        boolean hasSamplingRatio = java.util.Arrays.stream(info.getAttributes())
                .anyMatch(a -> "SamplingRatio".equals(a.getName()));
        assertThat(hasSamplingRatio).as("SamplingRatio attribute present").isTrue();
    }

    @Test
    void scrubbing_MBean_getMBeanInfo_содержит_операцию_updateScrubbingPolicy() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        MBeanInfo info = server.getMBeanInfo(PlatformTracingObjectNames.SCRUBBING);

        boolean hasUpdate = java.util.Arrays.stream(info.getOperations())
                .anyMatch(op -> "updateScrubbingPolicy".equals(op.getName()));
        assertThat(hasUpdate).as("updateScrubbingPolicy operation present").isTrue();
    }

    @Test
    void validation_MBean_getMBeanInfo_содержит_операцию_updateValidationPolicy() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        MBeanInfo info = server.getMBeanInfo(PlatformTracingObjectNames.VALIDATION);

        boolean hasUpdate = java.util.Arrays.stream(info.getOperations())
                .anyMatch(op -> "updateValidationPolicy".equals(op.getName()));
        assertThat(hasUpdate).as("updateValidationPolicy operation present").isTrue();
    }

    @Test
    void export_MBean_getMBeanInfo_содержит_атрибут_ExportQueueSize() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        MBeanInfo info = server.getMBeanInfo(PlatformTracingObjectNames.EXPORT);

        boolean hasQueueSize = java.util.Arrays.stream(info.getAttributes())
                .anyMatch(a -> "ExportQueueSize".equals(a.getName()));
        assertThat(hasQueueSize).as("ExportQueueSize attribute present").isTrue();
    }

    @Test
    void processorMetrics_MBean_getMBeanInfo_содержит_атрибут_ProcessorErrorsTotal() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        MBeanInfo info = server.getMBeanInfo(PlatformTracingObjectNames.PROCESSOR_METRICS);

        boolean hasErrors = java.util.Arrays.stream(info.getAttributes())
                .anyMatch(a -> "ProcessorErrorsTotal".equals(a.getName()));
        assertThat(hasErrors).as("ProcessorErrorsTotal attribute present").isTrue();
    }

    @Test
    void diagnostics_MBean_getMBeanInfo_содержит_атрибут_InvalidConfigCount() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        MBeanInfo info = server.getMBeanInfo(PlatformTracingObjectNames.DIAGNOSTICS);

        boolean hasCounter = java.util.Arrays.stream(info.getAttributes())
                .anyMatch(a -> "InvalidConfigCount".equals(a.getName()));
        assertThat(hasCounter).as("InvalidConfigCount attribute present").isTrue();
    }

    @Test
    void старый_monolith_ObjectName_не_зарегистрирован() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.3));

        javax.management.ObjectName oldName = new javax.management.ObjectName(
                "space.br1440.platform.tracing:type=Control,name=PlatformTracingControl");
        assertThat(server.isRegistered(oldName)).isFalse();
    }

    @Test
    void sampling_getAttribute_SamplingRatio_возвращает_корректное_значение() throws Exception {
        new PlatformTracingJmxRegistrar().setConfigHolder(holderWith(0.77));

        Object ratio = server.getAttribute(PlatformTracingObjectNames.SAMPLING, "SamplingRatio");
        assertThat(ratio).isEqualTo(0.77d);
    }

    private static SamplerStateHolder holderWith(double ratio) {
        return new SamplerStateHolder(
                true, Collections.emptyList(), Collections.emptyList(), Map.of(), ratio);
    }
}
