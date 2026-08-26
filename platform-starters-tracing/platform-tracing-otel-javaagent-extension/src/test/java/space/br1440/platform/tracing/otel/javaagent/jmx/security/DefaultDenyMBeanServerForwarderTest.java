package space.br1440.platform.tracing.otel.javaagent.jmx.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxReadGuardTestFixtures.endpoint;
import static space.br1440.platform.tracing.otel.javaagent.jmx.security.RemoteJmxReadGuardTestFixtures.neutralComposite;

import java.util.Set;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.Query;
import javax.management.StandardMBean;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenMBeanInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.GeneratedJmxWireContract;

class DefaultDenyMBeanServerForwarderTest {

    private static final ObjectName OBSERVATION =
            GeneratedJmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME;
    private static final String OBSERVATION_ATTRIBUTE =
            GeneratedJmxWireContract.ATTRIBUTE_SAMPLING_OBSERVATION_SAMPLING_OBSERVATION;
    private static final ObjectName MUTATION =
            GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME;
    private static final ObjectName FOREIGN = objectName(
            "space.br1440.platform.tracing:type=Observation,name=Foreign");

    private MBeanServer server;
    private DefaultDenyMBeanServerForwarder forwarder;

    @BeforeEach
    void setUp() throws Exception {
        server = MBeanServerFactory.newMBeanServer();
        server.registerMBean(
                endpoint(
                        OBSERVATION_ATTRIBUTE,
                        GeneratedJmxOpenTypes.SAMPLING_OBSERVATION,
                        true,
                        neutralComposite(GeneratedJmxOpenTypes.SAMPLING_OBSERVATION)),
                OBSERVATION);
        server.registerMBean(new StandardMBean(new TestControl(), TestControlMBean.class), MUTATION);
        RemoteJmxAccessProfile profile = RemoteJmxAccessProfile.builder()
                .allowAttribute(OBSERVATION, OBSERVATION_ATTRIBUTE)
                .build();
        forwarder = new DefaultDenyMBeanServerForwarder(profile);
        forwarder.setMBeanServer(server);
    }

    @Test
    void allows_only_profiled_observation_reads() throws Exception {
        // given
        // when
        Object attribute = forwarder.getAttribute(OBSERVATION, OBSERVATION_ATTRIBUTE);

        // then
        assertThat(attribute).isInstanceOf(CompositeData.class);
        assertThat(((CompositeData) attribute).getCompositeType())
                .isEqualTo(GeneratedJmxOpenTypes.SAMPLING_OBSERVATION);
        assertThat(forwarder.getMBeanInfo(OBSERVATION)).isInstanceOf(OpenMBeanInfo.class);
        assertThat(forwarder.getObjectInstance(OBSERVATION).getObjectName()).isEqualTo(OBSERVATION);
    }

    @Test
    void denies_mutation_object_even_when_operation_name_looks_read_only() {
        // given
        String[] emptySignature = new String[0];

        // when
        var invocation = catchThrowable(() ->
                forwarder.invoke(MUTATION, "snapshot", new Object[0], emptySignature));

        // then
        assertThat(invocation)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=invoke:<provided>")
                .hasMessageContaining("target=<unapproved-object-name>");
        assertThat(invocation.getMessage()).doesNotContain(MUTATION.getCanonicalName());
    }

    @Test
    void denies_unlisted_attribute_and_operation_on_observation_object() {
        // given
        String[] stringSignature = {String.class.getName()};

        // when
        var forbiddenAttribute = catchThrowable(() ->
                forwarder.getAttribute(OBSERVATION, "Secret"));
        var forbiddenOverload = catchThrowable(() ->
                forwarder.invoke(OBSERVATION, "snapshot", new Object[]{"full"}, stringSignature));

        // then
        assertThat(forbiddenAttribute).isInstanceOf(SecurityException.class);
        assertThat(forbiddenOverload).isInstanceOf(SecurityException.class);
    }

    @Test
    void denialMessagesDoNotReflectMaliciousOperationOrAttributeTokens() {
        // given
        String operationCanary = "OPERATION_CANARY\r\nforged=true";
        String attributeCanary = "ATTRIBUTE_CANARY_" + "x".repeat(16_384);

        // when
        var operationFailure = catchThrowable(() ->
                forwarder.invoke(OBSERVATION, operationCanary, new Object[0], new String[0]));
        var attributeFailure = catchThrowable(() ->
                forwarder.getAttribute(OBSERVATION, attributeCanary));

        // then
        assertThat(operationFailure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=invoke:<provided>");
        assertThat(attributeFailure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=getAttribute:<provided>");
        assertThat(operationFailure.getMessage())
                .doesNotContain("OPERATION_CANARY", "\r", "\n");
        assertThat(attributeFailure.getMessage())
                .doesNotContain("ATTRIBUTE_CANARY", "\r", "\n");
        assertThat(operationFailure.getMessage().length()).isLessThan(256);
        assertThat(attributeFailure.getMessage().length()).isLessThan(256);
    }

    @Test
    void denialMessageDoesNotReflectUnapprovedObjectName() {
        // given
        String objectNameCanary = "OBJECT_NAME_CANARY\r\nforged=true";
        ObjectName unapproved = objectName(
                "space.br1440.platform.tracing:type=Foreign,name=" + ObjectName.quote(objectNameCanary));

        // when
        var failure = catchThrowable(() -> forwarder.isRegistered(unapproved));

        // then
        assertThat(failure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("target=<unapproved-object-name>");
        assertThat(failure.getMessage())
                .doesNotContain("OBJECT_NAME_CANARY", "\r", "\n");
    }

    @Test
    void getAttributesAcceptsOneProfiledAttributeAndRejectsOversizedOrDuplicateRequests() throws Exception {
        // given
        String[] oneAttribute = {OBSERVATION_ATTRIBUTE};
        String[] oversized = {OBSERVATION_ATTRIBUTE, "Secret"};
        String[] duplicated = {OBSERVATION_ATTRIBUTE, OBSERVATION_ATTRIBUTE};

        // when
        AttributeList accepted = forwarder.getAttributes(OBSERVATION, oneAttribute);
        var missingFailure = catchThrowable(() -> forwarder.getAttributes(OBSERVATION, null));
        var oversizedFailure = catchThrowable(() -> forwarder.getAttributes(OBSERVATION, oversized));
        var duplicateFailure = catchThrowable(() -> forwarder.getAttributes(OBSERVATION, duplicated));

        // then
        assertThat(accepted.asList())
                .extracting(Attribute::getName)
                .containsExactly(OBSERVATION_ATTRIBUTE);
        assertThat(missingFailure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=getAttributes:missing-attributes");
        assertThat(oversizedFailure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=getAttributes:attribute-count");
        assertThat(duplicateFailure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=getAttributes:duplicate-attribute");
    }

    @Test
    void denies_all_write_and_server_lifecycle_operations() throws Exception {
        // given
        StandardMBean candidate = new StandardMBean(new TestControl(), TestControlMBean.class);
        ObjectName candidateName = objectName("space.br1440.platform.tracing:type=Mutation,name=Candidate");

        // when
        var setAttribute = catchThrowable(() ->
                forwarder.setAttribute(OBSERVATION, new Attribute(OBSERVATION_ATTRIBUTE, 8)));
        var register = catchThrowable(() ->
                forwarder.registerMBean(candidate, candidateName));
        var unregister = catchThrowable(() ->
                forwarder.unregisterMBean(OBSERVATION));
        var create = catchThrowable(() ->
                forwarder.createMBean(String.class.getName(), candidateName));
        var instantiate = catchThrowable(() ->
                forwarder.instantiate(String.class.getName()));
        var isInstanceOf = catchThrowable(() ->
                forwarder.isInstanceOf(OBSERVATION, TestControl.class.getName()));
        // then
        assertThat(Set.of(
                setAttribute,
                register,
                unregister,
                create,
                instantiate,
                isInstanceOf))
                .allMatch(SecurityException.class::isInstance);
        assertThat(server.isRegistered(OBSERVATION)).isTrue();
        assertThat(server.isRegistered(candidateName)).isFalse();
    }

    @Test
    void delegatesConnectorInternalClassLoaderRepositoryLookup() {
        // given
        var expected = server.getClassLoaderRepository();

        // when
        var actual = forwarder.getClassLoaderRepository();

        // then
        assertThat(actual).isSameAs(expected);
    }

    @Test
    void delegatesConnectorInternalClassLoaderLookupOnlyForGeneratedContractEndpoints()
            throws Exception {
        // given
        ClassLoader observationLoader = server.getClassLoaderFor(OBSERVATION);
        ClassLoader mutationLoader = server.getClassLoaderFor(MUTATION);

        // when
        ClassLoader forwardedObservationLoader = forwarder.getClassLoaderFor(OBSERVATION);
        ClassLoader forwardedMutationLoader = forwarder.getClassLoaderFor(MUTATION);
        var foreignFailure = catchThrowable(() -> forwarder.getClassLoaderFor(FOREIGN));

        // then
        assertThat(forwardedObservationLoader).isSameAs(observationLoader);
        assertThat(forwardedMutationLoader).isSameAs(mutationLoader);
        assertThat(foreignFailure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=getClassLoaderFor")
                .hasMessageContaining("target=<unapproved-object-name>");
        assertThat(foreignFailure.getMessage()).doesNotContain(FOREIGN.getCanonicalName());
    }

    @Test
    void discovery_filters_everything_outside_observation_profile() {
        // given
        ObjectName domainPattern = objectName("space.br1440.platform.tracing:*");

        // when
        Set<ObjectName> names = forwarder.queryNames(domainPattern, null);

        // then
        assertThat(names).containsExactlyInAnyOrder(OBSERVATION);
        assertThat(forwarder.queryMBeans(domainPattern, null))
                .extracting(instance -> instance.getObjectName())
                .containsExactlyInAnyOrder(OBSERVATION);
        assertThat(forwarder.getMBeanCount()).isEqualTo(1);
        assertThat(forwarder.getDomains()).containsExactly("space.br1440.platform.tracing");
    }

    @Test
    void rejects_query_expressions_instead_of_executing_them_on_mbeans() {
        // given
        var query = Query.eq(Query.attr(OBSERVATION_ATTRIBUTE), Query.value(7));

        // when
        var failure = catchThrowable(() ->
                forwarder.queryNames(null, query));

        // then
        assertThat(failure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("query-with-expression");
    }

    @Test
    void mbean_server_can_be_assigned_only_once() {
        // given
        MBeanServer replacement = MBeanServerFactory.createMBeanServer();

        try {
            // when
            var failure = catchThrowable(() ->
                    forwarder.setMBeanServer(replacement));

            // then
            assertThat(failure)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("MBeanServer has already been assigned");
            assertThat(forwarder.getMBeanServer()).isSameAs(server);
        } finally {
            MBeanServerFactory.releaseMBeanServer(replacement);
        }
    }

    @Test
    void unassigned_forwarder_fails_without_falling_back_to_platform_server() {
        // given
        DefaultDenyMBeanServerForwarder unassigned = new DefaultDenyMBeanServerForwarder(
                RemoteJmxAccessProfile.builder().allowAttribute(OBSERVATION, OBSERVATION_ATTRIBUTE).build());

        // when
        var failure = catchThrowable(() ->
                unassigned.getAttribute(OBSERVATION, OBSERVATION_ATTRIBUTE));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MBeanServer has not been assigned");
    }

    @Test
    void access_profile_rejects_pattern_object_names() {
        // given
        ObjectName pattern = objectName("space.br1440.platform.tracing:type=Observation,*");

        // when
        var failure = catchThrowable(() ->
                RemoteJmxAccessProfile.builder().allowObservationEndpoint(pattern));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Observation ObjectName must be exact");
    }

    @Test
    void accessProfileBuilderAlwaysRejectsGeneratedMutationEndpoint() {
        // given
        ObjectName mutation = GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME;

        // when
        var endpointFailure = catchThrowable(() ->
                RemoteJmxAccessProfile.builder().allowObservationEndpoint(mutation));
        var attributeFailure = catchThrowable(() ->
                RemoteJmxAccessProfile.builder().allowAttribute(mutation, "AcceptedPolicySnapshot"));
        // then
        assertThat(Set.of(endpointFailure, attributeFailure))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Remote JMX mutation endpoint must not be allowlisted"));
    }

    @Test
    void accessProfileBuilderRejectsForeignExactEndpoint() {
        // given
        ObjectName foreign = FOREIGN;

        // when
        var endpointFailure = catchThrowable(() ->
                RemoteJmxAccessProfile.builder().allowObservationEndpoint(foreign));
        var attributeFailure = catchThrowable(() ->
                RemoteJmxAccessProfile.builder().allowAttribute(foreign, "Value"));
        // then
        assertThat(Set.of(endpointFailure, attributeFailure))
                .allSatisfy(failure -> assertThat(failure)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Remote JMX endpoint must be a generated read-only endpoint"));
    }

    @Test
    void accessProfileBuilderRejectsAttributeOutsideGeneratedContract() {
        // given
        String schemaDriftAttribute = "Value";

        // when
        var failure = catchThrowable(() ->
                RemoteJmxAccessProfile.builder().allowAttribute(OBSERVATION, schemaDriftAttribute));

        // then
        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Remote JMX attribute must match generated read-only contract");
    }

    @Test
    void forwarderHardDeniesMutationWithNormalReadOnlyProfile() throws Exception {
        // given
        ObjectName mutation = GeneratedJmxWireContract.SETTINGS_MUTATION_OBJECT_NAME;
        MBeanServer isolated = MBeanServerFactory.newMBeanServer();
        isolated.registerMBean(new StandardMBean(new TestControl(), TestControlMBean.class), mutation);
        RemoteJmxAccessProfile profile = RemoteJmxAccessProfile.builder()
                .allowAttribute(OBSERVATION, OBSERVATION_ATTRIBUTE)
                .build();
        DefaultDenyMBeanServerForwarder hardened = new DefaultDenyMBeanServerForwarder(profile);
        hardened.setMBeanServer(isolated);

        // when
        var failure = catchThrowable(() -> hardened.invoke(
                mutation,
                "applySnapshot",
                new Object[]{null},
                new String[]{Object.class.getName()}));

        // then
        assertThat(failure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=invoke:<provided>")
                .hasMessageContaining("target=<unapproved-object-name>");
        assertThat(failure.getMessage()).doesNotContain(mutation.getCanonicalName());
    }

    @Test
    void forwarderHardDeniesForeignEndpointEvenWhenProfileClaimsAttributeIsAllowed() throws Exception {
        // given
        server.registerMBean(new StandardMBean(new TestControl(), TestControlMBean.class), FOREIGN);
        RemoteJmxAccessProfile maliciousProfile = mock(RemoteJmxAccessProfile.class);
        when(maliciousProfile.allowsAttribute(any(ObjectName.class), any(String.class))).thenReturn(true);
        DefaultDenyMBeanServerForwarder hardened = new DefaultDenyMBeanServerForwarder(maliciousProfile);
        hardened.setMBeanServer(server);

        // when
        var failure = catchThrowable(() -> hardened.getAttribute(FOREIGN, OBSERVATION_ATTRIBUTE));

        // then
        assertThat(failure)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("operation=getAttribute:<provided>")
                .hasMessageContaining("target=<unapproved-object-name>");
        assertThat(failure.getMessage()).doesNotContain(FOREIGN.getCanonicalName());
    }

    private static ObjectName objectName(String value) {
        try {
            return new ObjectName(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid test ObjectName", exception);
        }
    }

    public interface TestControlMBean {

        CompositeData getSamplingObservation();
    }

    public static final class TestControl implements TestControlMBean {

        private final CompositeData value = neutralComposite(GeneratedJmxOpenTypes.SAMPLING_OBSERVATION);

        @Override
        public CompositeData getSamplingObservation() {
            return value;
        }
    }
}
