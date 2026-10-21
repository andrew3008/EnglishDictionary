package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.RuntimeMBeanException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class AgentReadinessProbeTest {

    @Test
    void readsOneExactGeneratedReadySnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot expected = ReadinessTestFixtures.readySnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(expected));
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertThat(result).isEqualTo(new AgentReadinessProbe.ProbeResult.Present(expected));
    }

    @Test
    void readsInitializingSnapshotWithoutFlatteningFields() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot expected = ReadinessTestFixtures.initializingSnapshot();
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(expected));
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertThat(result).isEqualTo(new AgentReadinessProbe.ProbeResult.Present(expected));
    }

    @Test
    void readsFailedSnapshotWithProducerFailureCode() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot expected = ReadinessTestFixtures.failedSnapshot("DISTRIBUTION_MISMATCH");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(expected));
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertThat(result).isEqualTo(new AgentReadinessProbe.ProbeResult.Present(expected));
    }

    @Test
    void readsFailedSnapshotWithPrimaryAndEffectiveFailureCodes() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot expected = ReadinessTestFixtures.failedSnapshot(
                "ACTIVATION_FAILED",
                "FAILED_ROLLBACK_INCOMPLETE");
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(expected));
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertThat(result).isEqualTo(new AgentReadinessProbe.ProbeResult.Present(expected));
    }

    @Test
    void rejectsNonCanonicalPrimaryFailureCodeAsMalformedSnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessSnapshot malformed = ReadinessTestFixtures.failedSnapshot(
                " ACTIVATION_FAILED",
                "FAILED_ROLLBACK_INCOMPLETE"
        );
        ReadinessTestFixtures.register(server, () -> ReadinessTestFixtures.composite(malformed));
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.MALFORMED_SNAPSHOT);
    }

    @Test
    void rejectsSnapshotWithoutPrimaryFailureCodeAsMalformedSnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        ReadinessTestFixtures.register(
                server,
                AgentReadinessProbeTest::snapshotWithoutPrimaryFailureCode);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.MALFORMED_SNAPSHOT);
    }

    @Test
    void rejectsNullPrimaryFailureCodeAsMalformedSnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        ReadinessTestFixtures.register(
                server,
                AgentReadinessProbeTest::snapshotWithNullPrimaryFailureCode);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.MALFORMED_SNAPSHOT);
    }

    @Test
    void rejectsDuplicateCapabilityTokenAsMalformedSnapshot() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        ReadinessTestFixtures.register(
                server,
                AgentReadinessProbeTest::snapshotWithDuplicateCapability);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.MALFORMED_SNAPSHOT);
    }

    @Test
    void attributeFailureIsIndeterminateAndKeepsWrappedCause() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        IllegalStateException failure = new IllegalStateException("simulated readiness attribute failure");
        ReadinessTestFixtures.register(server, () -> {
            throw failure;
        });
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertThat(result).isInstanceOfSatisfying(AgentReadinessProbe.ProbeResult.Indeterminate.class, indeterminate -> {
            assertThat(indeterminate.reason()).isEqualTo(AgentReadinessProbe.ProbeReason.INVOCATION_FAILED);
            assertThat(indeterminate.cause()).isInstanceOf(RuntimeMBeanException.class).hasCause(failure);
        });
    }

    @Test
    void rejectsAbsentEndpoint() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertThat(probe.endpointRegistered(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .isFalse();
        assertRejected(result, AgentReadinessProbe.ProbeReason.ENDPOINT_DISAPPEARED);
    }

    @Test
    void rejectsMalformedCompositeType() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        ReadinessTestFixtures.register(server, AgentReadinessProbeTest::wrongComposite);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.MALFORMED_SNAPSHOT);
    }

    @Test
    void rejectsCustomDtoBeforeDecode() {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        ReadinessTestFixtures.register(server, CustomReadinessDto::new);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.JMX_WIRE_TYPE_VIOLATION);
    }

    @Test
    void rejectsLegacyNonOpenMBeanMetadataBeforeReadingPayload() throws Exception {
        // given
        MBeanServer server = MBeanServerFactory.createMBeanServer();
        server.registerMBean(
                new LegacyReadiness(),
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);
        AgentReadinessProbe probe = new AgentReadinessProbe(server);

        // when
        AgentReadinessProbe.ProbeResult result =
                probe.read(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME);

        // then
        assertRejected(result, AgentReadinessProbe.ProbeReason.INCOMPATIBLE_METADATA);
    }

    private static CompositeData wrongComposite() {
        try {
            CompositeType type = new CompositeType(
                    "wrong",
                    "wrong",
                    new String[]{"value"},
                    new String[]{"value"},
                    new OpenType<?>[]{SimpleType.STRING});
            return new CompositeDataSupport(type, Map.of("value", "wrong"));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create malformed readiness fixture", exception);
        }
    }

    private static CompositeData snapshotWithoutPrimaryFailureCode() {
        CompositeData source = ReadinessTestFixtures.composite(ReadinessTestFixtures.readySnapshot());
        CompositeType sourceType = source.getCompositeType();
        List<String> itemNames = new ArrayList<>(sourceType.keySet());
        itemNames.remove(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE);

        String[] names = itemNames.toArray(String[]::new);
        String[] descriptions = itemNames.stream()
                .map(sourceType::getDescription)
                .toArray(String[]::new);
        OpenType<?>[] types = itemNames.stream()
                .map(sourceType::getType)
                .toArray(OpenType<?>[]::new);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String itemName : itemNames) {
            values.put(itemName, source.get(itemName));
        }

        try {
            CompositeType type = new CompositeType(
                    sourceType.getTypeName(),
                    sourceType.getDescription(),
                    names,
                    descriptions,
                    types);
            return new CompositeDataSupport(type, values);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot create readiness snapshot without primaryFailureCode",
                    exception);
        }
    }

    private static CompositeData snapshotWithNullPrimaryFailureCode() {
        CompositeData source = ReadinessTestFixtures.composite(ReadinessTestFixtures.readySnapshot());
        Map<String, Object> values = new LinkedHashMap<>();
        for (String itemName : source.getCompositeType().keySet()) {
            values.put(itemName, source.get(itemName));
        }
        values.put(JmxWireContract.FIELD_READINESS_SNAPSHOT_PRIMARY_FAILURE_CODE, null);
        try {
            return new CompositeDataSupport(source.getCompositeType(), values);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot create readiness snapshot with null primaryFailureCode",
                    exception
            );
        }
    }

    private static CompositeData snapshotWithDuplicateCapability() {
        CompositeData source = ReadinessTestFixtures.composite(ReadinessTestFixtures.readySnapshot());
        String capability = ReadinessTestFixtures.COMPLETE_CAPABILITIES.iterator().next();

        Map<String, Object> values = new LinkedHashMap<>();
        for (String itemName : source.getCompositeType().keySet()) {
            values.put(itemName, source.get(itemName));
        }

        values.put(
                JmxWireContract.FIELD_READINESS_SNAPSHOT_CAPABILITIES,
                new String[]{capability, capability}
        );

        try {
            return new CompositeDataSupport(source.getCompositeType(), values);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot create readiness snapshot with duplicate capability",
                    exception
            );
        }
    }

    private static void assertRejected(
            AgentReadinessProbe.ProbeResult result,
            AgentReadinessProbe.ProbeReason reason) {
        assertThat(result).isEqualTo(new AgentReadinessProbe.ProbeResult.Rejected(reason));
    }

    public interface LegacyReadinessMBean {
        @SuppressWarnings("unused")
        CompositeData getReadinessSnapshot();
    }

    public static final class LegacyReadiness implements LegacyReadinessMBean {
        @Override
        @SuppressWarnings("unused")
        public CompositeData getReadinessSnapshot() {
            return ReadinessTestFixtures.composite(ReadinessTestFixtures.readySnapshot());
        }
    }

    private static final class CustomReadinessDto {
    }
}
