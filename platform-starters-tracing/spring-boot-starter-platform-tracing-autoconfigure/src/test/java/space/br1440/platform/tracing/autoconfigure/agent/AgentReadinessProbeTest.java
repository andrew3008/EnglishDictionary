package space.br1440.platform.tracing.autoconfigure.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
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

    private static void assertRejected(
            AgentReadinessProbe.ProbeResult result,
            AgentReadinessProbe.ProbeReason reason) {
        assertThat(result).isEqualTo(new AgentReadinessProbe.ProbeResult.Rejected(reason));
    }

    public interface LegacyReadinessMBean {
        CompositeData getReadinessSnapshot();
    }

    public static final class LegacyReadiness implements LegacyReadinessMBean {
        @Override
        public CompositeData getReadinessSnapshot() {
            return ReadinessTestFixtures.composite(ReadinessTestFixtures.readySnapshot());
        }
    }

    private static final class CustomReadinessDto {
    }
}
