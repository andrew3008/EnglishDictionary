package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.observation.JmxObservationWireContract;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.openmbean.CompositeData;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for read-only {@link DomainMBeanRegistrar} registration.
 */
class DomainMBeanRegistrarIntegrationTest {

    private MBeanServer server;
    private DomainMBeanRegistrar registrar;
    private TracingSettingsSource samplerHolder;
    private ValidatingSpanProcessor validatingProcessor;

    @BeforeEach
    void setUp() {
        server = ManagementFactory.getPlatformMBeanServer();
        cleanUp();

        samplerHolder = TracingSettingsSource.isolatedSampling(true, Collections.emptyMap(), 0.2d);
        validatingProcessor = new ValidatingSpanProcessor(
                TracingSettingsSource.isolatedValidation(true, false, false));

        registrar = new DomainMBeanRegistrar(server);
    }

    @AfterEach
    void cleanUp() {
        if (registrar != null) {
            registrar.unregisterAllMBeans();
        }
        unregisterSilently(DomainObjectNames.SAMPLING);
        unregisterSilently(DomainObjectNames.SCRUBBING);
        unregisterSilently(DomainObjectNames.VALIDATION);
        unregisterSilently(DomainObjectNames.EXPORT);
        unregisterSilently(DomainObjectNames.PROCESSOR_METRICS);
        unregisterSilently(DomainObjectNames.DIAGNOSTICS);
        unregisterSilently(DomainObjectNames.CONTROL_PROTOCOL);
    }

    @Test
    void allSixDomainMBeansRegisteredAfterWiring() {
        wireMinimal();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    @Test
    void registeredDomainMBeansExposeNoMutatingOperations() throws Exception {
        wireMinimal();

        for (javax.management.ObjectName name : List.of(
                DomainObjectNames.SAMPLING,
                DomainObjectNames.SCRUBBING,
                DomainObjectNames.VALIDATION,
                DomainObjectNames.EXPORT,
                DomainObjectNames.PROCESSOR_METRICS,
                DomainObjectNames.DIAGNOSTICS)) {
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

        CompositeData observation = (CompositeData) server.getAttribute(
                DomainObjectNames.SAMPLING, "SamplingObservation");
        double ratio = (double) observation.get(JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO);
        assertThat(ratio).isEqualTo(0.2d);
    }

    @Test
    void unregisterAllRemovesAllDomainMBeans() {
        wireMinimal();

        registrar.unregisterAllMBeans();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isFalse();
    }

    @Test
    void secondRegisterRuntimeControlsWhileActiveIsRejected() {
        wireMinimal();

        assertThatThrownBy(() -> registrar.registerRuntimeControls(samplerHolder))
                .as("re-bind while active is an error (§9 registration contract)")
                .isInstanceOf(IllegalStateException.class);

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    @Test
    void noMBeansRegisteredBeforeRuntimeControlsAreRegistered() {
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(
                new CompositeSampler(samplerHolder, CategoryBSamplingAccess.snapshot(List.of(), List.of())));

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.CONTROL_PROTOCOL)).isFalse();
    }

    private void wireMinimal() {
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(
                new CompositeSampler(samplerHolder, CategoryBSamplingAccess.snapshot(List.of(), List.of())));
        registrar.registerRuntimeControls(samplerHolder);
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
