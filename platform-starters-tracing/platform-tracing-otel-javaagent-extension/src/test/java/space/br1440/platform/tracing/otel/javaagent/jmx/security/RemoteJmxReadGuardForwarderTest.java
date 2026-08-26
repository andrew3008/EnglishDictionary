package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxReadGuardTestFixtures.composite;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxReadGuardTestFixtures.endpoint;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxReadGuardTestFixtures.neutralComposite;

import java.util.List;
import java.util.Map;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

class RemoteJmxReadGuardForwarderTest {

    private static final List<EndpointFixture> ENDPOINTS = List.of(
            new EndpointFixture(
                    GeneratedJmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT,
                    GeneratedJmxOpenTypes.READINESS_SNAPSHOT),
            new EndpointFixture(
                    GeneratedJmxWireContract.SETTINGS_STATUS_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT,
                    GeneratedJmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT),
            new EndpointFixture(
                    GeneratedJmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION,
                    GeneratedJmxOpenTypes.SAMPLING_OBSERVATION),
            new EndpointFixture(
                    GeneratedJmxWireContract.SCRUBBING_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_SCRUBBING_OBSERVATION_SCRUBBING_OBSERVATION,
                    GeneratedJmxOpenTypes.SCRUBBING_OBSERVATION),
            new EndpointFixture(
                    GeneratedJmxWireContract.VALIDATION_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_VALIDATION_OBSERVATION_VALIDATION_OBSERVATION,
                    GeneratedJmxOpenTypes.VALIDATION_OBSERVATION),
            new EndpointFixture(
                    GeneratedJmxWireContract.EXPORT_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_EXPORT_OBSERVATION_EXPORT_OBSERVATION,
                    GeneratedJmxOpenTypes.EXPORT_OBSERVATION),
            new EndpointFixture(
                    GeneratedJmxWireContract.PROCESSOR_METRICS_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract
                            .ATTRIBUTE_PROCESSOR_METRICS_OBSERVATION_PROCESSOR_METRICS_OBSERVATION,
                    GeneratedJmxOpenTypes.PROCESSOR_METRICS_OBSERVATION),
            new EndpointFixture(
                    GeneratedJmxWireContract.DIAGNOSTICS_OBSERVATION_OBJECT_NAME,
                    GeneratedJmxWireContract.ATTRIBUTE_DIAGNOSTICS_OBSERVATION_DIAGNOSTICS_OBSERVATION,
                    GeneratedJmxOpenTypes.DIAGNOSTICS_OBSERVATION));

    private MBeanServer server;

    @BeforeEach
    void setUp() {
        server = MBeanServerFactory.createMBeanServer();
    }

    @AfterEach
    void cleanUp() {
        MBeanServerFactory.releaseMBeanServer(server);
    }

    @Test
    void allGeneratedReadEndpointsReturnCanonicalMetadataAndValidatedNeutralPayloads() throws Exception {
        // given
        RemoteJmxAccessProfile.Builder profile = RemoteJmxAccessProfile.builder();
        for (EndpointFixture fixture : ENDPOINTS) {
            server.registerMBean(
                    endpoint(
                            fixture.attributeName(),
                            fixture.openType(),
                            true,
                            neutralComposite(fixture.openType())),
                    fixture.objectName());
            profile.allowAttribute(fixture.objectName(), fixture.attributeName());
        }
        DefaultDenyMBeanServerForwarder forwarder = forwarder(profile.build());

        // when
        // then
        for (EndpointFixture fixture : ENDPOINTS) {
            MBeanInfo info = forwarder.getMBeanInfo(fixture.objectName());
            assertThat(info).isInstanceOf(OpenMBeanInfo.class);
            assertThat(info.getClassName())
                    .isEqualTo("space.br1440.platform.tracing.jmx.GeneratedReadOnlyEndpoint");
            assertThat(info.getAttributes()).hasSize(1);
            MBeanAttributeInfo attribute = info.getAttributes()[0];
            assertThat(attribute.getName()).isEqualTo(fixture.attributeName());
            assertThat(attribute).isInstanceOf(OpenMBeanAttributeInfo.class);
            assertThat(((OpenMBeanAttributeInfo) attribute).getOpenType())
                    .isEqualTo(fixture.openType());

            Object single = forwarder.getAttribute(fixture.objectName(), fixture.attributeName());
            assertThat(single).isInstanceOf(CompositeData.class);
            assertThat(((CompositeData) single).getCompositeType()).isEqualTo(fixture.openType());

            AttributeList bulk = forwarder.getAttributes(
                    fixture.objectName(),
                    new String[]{fixture.attributeName()});
            assertThat(bulk.asList()).hasSize(1);
            assertThat(((CompositeData) ((Attribute) bulk.get(0)).getValue()).getCompositeType())
                    .isEqualTo(fixture.openType());
        }
    }

    @Test
    void sameObjectNameCustomDtoAndThrowablePayloadsAreDeniedWithoutSerializationLeak() throws Exception {
        // given
        EndpointFixture customFixture = ENDPOINTS.get(2);
        EndpointFixture throwableFixture = ENDPOINTS.get(4);
        server.registerMBean(
                endpoint(
                        customFixture.attributeName(),
                        customFixture.openType(),
                        true,
                        new HostilePayload("CUSTOM_DTO_CANARY")),
                customFixture.objectName());
        server.registerMBean(
                endpoint(
                        throwableFixture.attributeName(),
                        throwableFixture.openType(),
                        true,
                        new IllegalStateException("THROWABLE_CANARY")),
                throwableFixture.objectName());
        DefaultDenyMBeanServerForwarder forwarder = forwarder(profile(customFixture, throwableFixture));

        // when
        Throwable customFailure = catchThrowable(() -> forwarder.getAttribute(
                customFixture.objectName(),
                customFixture.attributeName()));
        Throwable bulkFailure = catchThrowable(() -> forwarder.getAttributes(
                customFixture.objectName(),
                new String[]{customFixture.attributeName()}));
        Throwable throwableFailure = catchThrowable(() -> forwarder.getAttribute(
                throwableFixture.objectName(),
                throwableFixture.attributeName()));

        // then
        assertStableDenial(customFailure, "getAttribute:payload-violation", customFixture.objectName());
        assertStableDenial(bulkFailure, "getAttributes:payload-violation", customFixture.objectName());
        assertStableDenial(throwableFailure, "getAttribute:payload-violation", throwableFixture.objectName());
        assertThat(customFailure.getMessage())
                .doesNotContain("CUSTOM_DTO_CANARY", HostilePayload.class.getName());
        assertThat(throwableFailure.getMessage())
                .doesNotContain("THROWABLE_CANARY", IllegalStateException.class.getName());
    }

    @Test
    void hostileSameObjectNameMetadataFailsClosedBeforePayloadRead() throws Exception {
        // given
        EndpointFixture fixture = ENDPOINTS.get(2);
        server.registerMBean(
                endpoint(
                        fixture.attributeName(),
                        fixture.openType(),
                        false,
                        new HostilePayload("METADATA_BYPASS_CANARY")),
                fixture.objectName());
        DefaultDenyMBeanServerForwarder forwarder = forwarder(profile(fixture));

        // when
        Throwable infoFailure = catchThrowable(() -> forwarder.getMBeanInfo(fixture.objectName()));
        Throwable readFailure = catchThrowable(() -> forwarder.getAttribute(
                fixture.objectName(),
                fixture.attributeName()));
        Throwable bulkFailure = catchThrowable(() -> forwarder.getAttributes(
                fixture.objectName(),
                new String[]{fixture.attributeName()}));

        // then
        assertStableDenial(infoFailure, "getMBeanInfo:metadata-violation", fixture.objectName());
        assertStableDenial(readFailure, "getAttribute:metadata-violation", fixture.objectName());
        assertStableDenial(bulkFailure, "getAttributes:metadata-violation", fixture.objectName());
        assertThat(infoFailure.getMessage()).doesNotContain("METADATA_BYPASS_CANARY");
    }

    @Test
    void generatedPayloadExceedingManifestStringLimitIsDenied() throws Exception {
        // given
        EndpointFixture fixture = ENDPOINTS.get(2);
        String oversized = "OVERSIZED_CANARY_"
                + "x".repeat(GeneratedJmxWireContract.MAX_STRING_UTF8_BYTES + 1);
        CompositeData payload = composite(
                fixture.openType(),
                Map.of(
                        GeneratedJmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID,
                        oversized));
        server.registerMBean(
                endpoint(
                        fixture.attributeName(),
                        fixture.openType(),
                        true,
                        payload),
                fixture.objectName());
        DefaultDenyMBeanServerForwarder forwarder = forwarder(profile(fixture));

        // when
        Throwable failure = catchThrowable(() -> forwarder.getAttribute(
                fixture.objectName(),
                fixture.attributeName()));

        // then
        assertStableDenial(failure, "getAttribute:payload-violation", fixture.objectName());
        assertThat(failure.getMessage()).doesNotContain("OVERSIZED_CANARY");
    }

    private DefaultDenyMBeanServerForwarder forwarder(RemoteJmxAccessProfile profile) {
        DefaultDenyMBeanServerForwarder forwarder = new DefaultDenyMBeanServerForwarder(profile);
        forwarder.setMBeanServer(server);
        return forwarder;
    }

    private static RemoteJmxAccessProfile profile(EndpointFixture... fixtures) {
        RemoteJmxAccessProfile.Builder builder = RemoteJmxAccessProfile.builder();
        for (EndpointFixture fixture : fixtures) {
            builder.allowAttribute(fixture.objectName(), fixture.attributeName());
        }
        return builder.build();
    }

    private static void assertStableDenial(
            Throwable failure,
            String operation,
            ObjectName objectName) {
        assertThat(failure)
                .isExactlyInstanceOf(SecurityException.class)
                .hasMessage("Remote JMX access denied: operation=" + operation
                        + ", target=" + objectName.getCanonicalName())
                .hasNoCause();
    }

    private record EndpointFixture(
            ObjectName objectName,
            String attributeName,
            CompositeType openType) {
    }

    private record HostilePayload(String canary) {
    }
}
