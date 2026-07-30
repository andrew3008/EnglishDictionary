package space.br1440.platform.tracing.otel.javaagent.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.state.AcceptedPolicyAccess;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for read-only {@link PlatformTracingJmxRegistrar} registration.
 */
class PlatformTracingJmxRegistrarIntegrationTest {

    private MBeanServer server;
    private PlatformTracingJmxRegistrar registrar;
    private AcceptedPolicyAccess samplerHolder;
    private ValidatingSpanProcessor validatingProcessor;

    @BeforeEach
    void setUp() {
        server = ManagementFactory.getPlatformMBeanServer();
        cleanUp();

        samplerHolder = AcceptedPolicyAccess.isolatedSampling(true, List.of(), List.of(),
                Collections.emptyMap(), 0.2d);
        validatingProcessor = new ValidatingSpanProcessor(false, false);

        registrar = new PlatformTracingJmxRegistrar(server);
    }

    @AfterEach
    void cleanUp() {
        if (registrar != null) {
            registrar.unregisterAllMBeans();
        }
        unregisterSilently(PlatformTracingObjectNames.SAMPLING);
        unregisterSilently(PlatformTracingObjectNames.SCRUBBING);
        unregisterSilently(PlatformTracingObjectNames.VALIDATION);
        unregisterSilently(PlatformTracingObjectNames.EXPORT);
        unregisterSilently(PlatformTracingObjectNames.PROCESSOR_METRICS);
        unregisterSilently(PlatformTracingObjectNames.DIAGNOSTICS);
        unregisterSilently(PlatformTracingObjectNames.CONTROL_PROTOCOL);
    }

    @Test
    void allSixDomainMBeansRegisteredAfterWiring() {
        wireMinimal();

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    @Test
    void registeredDomainMBeansExposeNoMutatingOperations() throws Exception {
        wireMinimal();

        for (javax.management.ObjectName name : List.of(
                PlatformTracingObjectNames.SAMPLING,
                PlatformTracingObjectNames.SCRUBBING,
                PlatformTracingObjectNames.VALIDATION,
                PlatformTracingObjectNames.EXPORT,
                PlatformTracingObjectNames.PROCESSOR_METRICS,
                PlatformTracingObjectNames.DIAGNOSTICS)) {
            MBeanInfo info = server.getMBeanInfo(name);
            assertThat(info.getOperations())
                    .allMatch(operation -> operation.getImpact() != MBeanOperationInfo.ACTION
                            && operation.getImpact() != MBeanOperationInfo.ACTION_INFO);
            assertThat(info.getAttributes())
                    .allMatch(attribute -> !attribute.isWritable());
        }
    }

    @Test
    void samplingMBeanReflectsAcceptedPolicy() throws Exception {
        wireMinimal();

        double ratio = (double) server.getAttribute(
                PlatformTracingObjectNames.SAMPLING, "SamplingRatio");
        assertThat(ratio).isEqualTo(0.2d);
    }

    @Test
    void unregisterAllRemovesAllDomainMBeans() {
        wireMinimal();

        registrar.unregisterAllMBeans();

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.DIAGNOSTICS)).isFalse();
    }

    @Test
    void secondSetAcceptedPolicyIsIdempotent() {
        wireMinimal();
        registrar.setAcceptedPolicy(samplerHolder);

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    @Test
    void noMBeansRegisteredBeforeAcceptedPolicyIsSet() {
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(new CompositeSampler(samplerHolder));

        assertThat(server.isRegistered(PlatformTracingObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(PlatformTracingObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    private void wireMinimal() {
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(new CompositeSampler(samplerHolder));
        registrar.setAcceptedPolicy(samplerHolder);
    }

    private void unregisterSilently(javax.management.ObjectName name) {
        try {
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
        } catch (Exception ignored) {
        }
    }
}
