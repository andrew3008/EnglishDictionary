package space.br1440.platform.tracing.otel.javaagent.jmx.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures.boundSampling;
import static space.br1440.platform.tracing.otel.javaagent.readiness.ReadinessSnapshotAssertions.initializingOf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
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
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.jmx.DomainObjectNames;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.jmx.readiness.ExtensionReadinessWireSnapshot;
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
        server = MBeanServerFactory.newMBeanServer();
        cleanUp();

        AgentInstanceId agentInstanceId = AgentInstanceId.random();
        samplerHolder = boundSampling(agentInstanceId, true, Collections.emptyMap(), 0.2d);
        validatingProcessor = new ValidatingSpanProcessor(samplerHolder);
        registrar = new PlatformTracingMBeanRegistrar(server, agentInstanceId);
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
        // given/when
        wireMinimal();

        // then
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
        // given/when
        wireMinimal();

        // then
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
        // given
        wireMinimal();

        // when
        CompositeData observation = (CompositeData) server.getAttribute(
                DomainObjectNames.SAMPLING, "SamplingObservation"
        );

        double ratio = (double) observation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO
        );

        // then
        assertThat(ratio).isEqualTo(0.2d);
    }

    @Test
    void readinessSettingsAndObservationsExposeOneAgentInstanceId() throws Exception {
        // given
        String expectedAgentInstanceId = registrar.agentInstanceId().toString();
        ExtensionReadinessWireSnapshot readiness = ExtensionReadinessWireSnapshot.initializing(
                initializingOf(registrar.extensionReadiness().snapshot()),
                expectedAgentInstanceId
        );

        trackRegistration(registrar.registerDiagnosticReadiness(() -> readiness));
        wireMinimal();
        Map<ObjectName, ObservationIdentityContract> observationContracts = Map.of(
                DomainObjectNames.SAMPLING,

                new ObservationIdentityContract(
                        JmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                        JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID
                ),

                DomainObjectNames.SCRUBBING,

                new ObservationIdentityContract(
                        JmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                        JmxWireContract.FIELD_SCRUBBING_OBSERVATION_AGENT_INSTANCE_ID
                ),

                DomainObjectNames.VALIDATION,

                new ObservationIdentityContract(
                        JmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                        JmxWireContract.FIELD_VALIDATION_OBSERVATION_AGENT_INSTANCE_ID
                ),

                DomainObjectNames.EXPORT,

                new ObservationIdentityContract(
                        JmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                        JmxWireContract.FIELD_EXPORT_OBSERVATION_AGENT_INSTANCE_ID
                ),

                DomainObjectNames.PROCESSOR_METRICS,

                new ObservationIdentityContract(
                        JmxWireContract
                                .ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                        JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_AGENT_INSTANCE_ID
                ),

                DomainObjectNames.DIAGNOSTICS,

                new ObservationIdentityContract(
                        JmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                        JmxWireContract.FIELD_DIAGNOSTICS_OBSERVATION_AGENT_INSTANCE_ID
                )
        );

        // when
        CompositeData readinessSnapshot = (CompositeData) server.getAttribute(
                DomainObjectNames.EXTENSION_READINESS,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT
        );

        CompositeData settingsSnapshot = (CompositeData) server.getAttribute(
                DomainObjectNames.SETTINGS_STATUS,
                JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT
        );

        Map<ObjectName, CompositeData> observations = new LinkedHashMap<>();
        for (Map.Entry<ObjectName, ObservationIdentityContract> entry : observationContracts.entrySet()) {
            observations.put(
                    entry.getKey(),
                    (CompositeData) server.getAttribute(entry.getKey(), entry.getValue().attributeName())
            );
        }

        CompositeData repeatedSamplingObservation = (CompositeData) server.getAttribute(
                DomainObjectNames.SAMPLING,
                JmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION
        );

        // then
        assertThat(readinessSnapshot.get(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_AGENT_INSTANCE_ID))
                .isEqualTo(expectedAgentInstanceId);

        assertThat(settingsSnapshot.get(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID))
                .isEqualTo(expectedAgentInstanceId);

        for (Map.Entry<ObjectName, ObservationIdentityContract> entry : observationContracts.entrySet()) {
            assertThat(observations.get(entry.getKey()).get(entry.getValue().agentInstanceIdField()))
                    .isEqualTo(expectedAgentInstanceId);
        }

        assertThat(repeatedSamplingObservation.get(
                JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID))
                .isEqualTo(expectedAgentInstanceId);
    }

    @Test
    void closingRuntimeRegistrationRemovesAllDomainMBeans() {
        // given
        PlatformTracingMBeanRegistrar.RuntimeRegistration runtimeRegistration = wireMinimal();

        // when
        runtimeRegistration.close();

        // then
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
        // given
        wireMinimal();

        // when/then
        assertThatThrownBy(() -> registrar.registerRuntimeControls(samplerHolder))
                .as("re-bind while active is an error (§9 registration contract)")
                .isInstanceOf(IllegalStateException.class);

        assertThat(server.isRegistered(DomainObjectNames.SAMPLING)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_STATUS)).isTrue();
        assertThat(server.isRegistered(DomainObjectNames.SETTINGS_MUTATION)).isTrue();
    }

    @Test
    void noMBeansRegisteredBeforeRuntimeControlsAreRegistered() {
        // given/when
        registrar.setValidating(validatingProcessor);
        registrar.setCompositeSampler(
                new CompositeSampler(samplerHolder, CategoryBSamplingAccess.snapshot(List.of(), List.of())));

        // then
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

    private record ObservationIdentityContract(String attributeName, String agentInstanceIdField) {
    }
}
