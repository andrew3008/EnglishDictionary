package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanInfoSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.control.CategoryBSamplingAccess;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.CompositeSampler;

@SuppressWarnings("resource")
class PlatformTracingMBeanRegistrarIntegrationTest {

    private final List<AutoCloseable> registrations = new ArrayList<>();
    private MBeanServer server;
    private PlatformTracingMBeanRegistrar registrar;
    private TracingSettingsSource samplerHolder;
    private ValidatingSpanProcessor validatingProcessor;

    @BeforeEach
    void setUp() {
        server = ManagementFactory.getPlatformMBeanServer();
        cleanUp();

        samplerHolder = TracingSettingsSource.isolatedSampling(true, Collections.emptyMap(), 0.2d);
        validatingProcessor = new ValidatingSpanProcessor(
                TracingSettingsSource.isolatedValidation(true, false)
        );

        registrar = new PlatformTracingMBeanRegistrar(server);
    }

    @AfterEach
    void cleanUp() {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (Exception ignored) {
            }
        }

        registrations.clear();
    }

    @Test
    void allDomainMBeansRegisteredAfterWiring() {
        wireMinimal();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isTrue();
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
                DomainObjectNames.DIAGNOSTICS,
                DomainObjectNames.SETTINGS_STATUS)) {
            MBeanInfo info = server.getMBeanInfo(name);
            assertThat(info.getOperations())
                    .allMatch(operation -> operation.getImpact() != MBeanOperationInfo.ACTION
                            && operation.getImpact() != MBeanOperationInfo.ACTION_INFO);
            assertThat(info.getAttributes())
                    .allMatch(attribute -> !attribute.isWritable());
        }
    }

    @Test
    void registeredObservationMBeansExposeExactGeneratedOpenMetadata() throws Exception {
        // given
        wireMinimal();
        Map<ObjectName, ObservationContract> contracts = Map.of(
                DomainObjectNames.SAMPLING,
                new ObservationContract(
                        JmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                        JmxOpenTypes.SAMPLING_OBSERVATION
                ),

                DomainObjectNames.SCRUBBING,
                new ObservationContract(
                        JmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                        JmxOpenTypes.SCRUBBING_OBSERVATION
                ),

                DomainObjectNames.VALIDATION,
                new ObservationContract(
                        JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                        JmxOpenTypes.VALIDATION_OBSERVATION
                ),

                DomainObjectNames.EXPORT,
                new ObservationContract(
                        JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                        JmxOpenTypes.EXPORT_OBSERVATION
                ),

                DomainObjectNames.PROCESSOR_METRICS,
                new ObservationContract(
                        JmxWireContract
                                .ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                        JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION
                ),

                DomainObjectNames.DIAGNOSTICS,
                new ObservationContract(
                        JmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                        JmxOpenTypes.DIAGNOSTICS_OBSERVATION
                )
        );

        // when
        Map<ObjectName, MBeanInfo> metadata = new LinkedHashMap<>();
        for (ObjectName objectName : contracts.keySet()) {
            metadata.put(objectName, server.getMBeanInfo(objectName));
        }

        // then
        for (Map.Entry<ObjectName, ObservationContract> entry : contracts.entrySet()) {
            MBeanInfo info = metadata.get(entry.getKey());
            assertThat(info).isExactlyInstanceOf(OpenMBeanInfoSupport.class);
            assertThat(info.getClassName()).isEqualTo("space.br1440.platform.tracing.jmx.GeneratedReadOnlyEndpoint");
            assertThat(info.getConstructors()).isEmpty();
            assertThat(info.getOperations()).isEmpty();
            assertThat(info.getNotifications()).isEmpty();
            assertThat(info.getAttributes()).hasSize(1);

            MBeanAttributeInfo attribute = info.getAttributes()[0];
            assertThat(attribute).isExactlyInstanceOf(OpenMBeanAttributeInfoSupport.class);
            assertThat(attribute.getName()).isEqualTo(entry.getValue().attributeName());
            assertThat(attribute.getType()).isEqualTo(CompositeData.class.getName());
            assertThat(attribute.isReadable()).isTrue();
            assertThat(attribute.isWritable()).isFalse();
            assertThat(attribute.isIs()).isFalse();
            assertThat(((OpenMBeanAttributeInfoSupport) attribute).getOpenType())
                    .isEqualTo(entry.getValue().openType());
        }
    }

    @Test
    void samplingMBeanReflectsAcceptedPolicy() throws Exception {
        wireMinimal();

        CompositeData observation = (CompositeData) server.getAttribute(
                DomainObjectNames.SAMPLING, "SamplingObservation"
        );

        double ratio = (double) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO
        );

        assertThat(ratio).isEqualTo(0.2d);
    }

    @Test
    void closingRuntimeRegistrationRemovesAllDomainMBeans() {
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration = wireMinimal();

        runtimeRegistration.close();

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SCRUBBING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.VALIDATION)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.EXPORT)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.PROCESSOR_METRICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.DIAGNOSTICS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
    }

    @Test
    void secondRegisterRuntimeControlsWhileActiveIsRejected() {
        wireMinimal();

        assertThatThrownBy(() -> registrar.registerRuntimeControls(samplerHolder))
                .as("re-bind while active is an error (§9 registration contract)")
                .isInstanceOf(IllegalStateException.class);

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isTrue();
    }

    @Test
    void noMBeansRegisteredBeforeRuntimeControlsAreRegistered() {
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(
                new CompositeSampler(samplerHolder, CategoryBSamplingAccess.snapshot(List.of(), List.of())));

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isFalse();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isFalse();
    }

    private PlatformTracingMBeanRegistrar.RuntimeRegistration wireMinimal() {
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(
                new CompositeSampler(samplerHolder, CategoryBSamplingAccess.snapshot(List.of(), List.of())));
        return trackRegistration(registrar.registerRuntimeControls(samplerHolder));
    }

    private <T extends AutoCloseable> T trackRegistration(T registration) {
        registrations.add(registration);
        return registration;
    }

    private record ObservationContract(String attributeName, CompositeType openType) {
    }
}
