package space.br1440.platform.tracing.otel.javaagent.control.state;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularDataSupport;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.UUID;

class PrivateControlBoundaryTest {

    @Test
    void registersExactlyOnePrivateWriterAndReleasesServer() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        AcceptedPolicyStateStore store = store(runtimeId);
        MBeanServer platformServer = ManagementFactory.getPlatformMBeanServer();
        int baselineServers = MBeanServerFactory.findMBeanServer(null).size();

        PrivateControlMBeanServerOwner owner = new PrivateControlMBeanServerOwner(
                runtimeId,
                store,
                new PolicyValidationConstraints(true));
        MBeanServer server = owner.initialize();

        assertThat(owner.initialize()).isSameAs(server);
        assertThat(MBeanServerFactory.findMBeanServer(null)).contains(server);
        if (platformServer.isRegistered(PrivateControlObjectNames.WRITER)) {
            assertThat(platformServer.getMBeanInfo(PrivateControlObjectNames.WRITER).getOperations())
                    .noneMatch(operation -> operation.getName().equals("applySnapshot"));
        }
        assertThat(server.isRegistered(PrivateControlObjectNames.MARKER)).isTrue();
        assertThat(server.isRegistered(PrivateControlObjectNames.WRITER)).isTrue();
        assertThat(server.getAttribute(PrivateControlObjectNames.MARKER, "LifecycleState"))
                .isEqualTo("WRITER_READY");

        MBeanInfo markerInfo = server.getMBeanInfo(PrivateControlObjectNames.MARKER);
        MBeanInfo writerInfo = server.getMBeanInfo(PrivateControlObjectNames.WRITER);
        assertThat(markerInfo.getAttributes()).hasSize(6)
                .allMatch(attribute -> attribute.isReadable() && !attribute.isWritable());
        assertThat(writerInfo.getAttributes()).isEmpty();
        assertThat(writerInfo.getOperations()).singleElement()
                .satisfies(operation -> assertThat(operation.getName()).isEqualTo("applySnapshot"));

        owner.close();
        owner.close();

        assertThat(MBeanServerFactory.findMBeanServer(null)).hasSize(baselineServers);
        assertThat(server.isRegistered(PrivateControlObjectNames.MARKER)).isFalse();
        assertThat(server.isRegistered(PrivateControlObjectNames.WRITER)).isFalse();
    }

    @Test
    void appliesCompleteSnapshotAndRejectsStaleOrMalformedInput() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        AcceptedPolicyStateStore store = store(runtimeId);
        PrivateControlMBeanServerOwner owner = new PrivateControlMBeanServerOwner(
                runtimeId,
                store,
                new PolicyValidationConstraints(true));
        MBeanServer server = owner.initialize();
        try {
            EffectiveTracingPolicy candidate = policy(0.25d);
            CompositeData applied = invoke(
                    server,
                    request(runtimeId, 0L, candidate, UUID.randomUUID().toString()));
            assertThat(applied.get("outcome")).isEqualTo("APPLIED");
            assertThat(applied.get("acceptedSequence")).isEqualTo(1L);
            assertThat(store.current().policy()).isEqualTo(candidate);

            CompositeData stale = invoke(
                    server,
                    request(runtimeId, 0L, policy(0.5d), UUID.randomUUID().toString()));
            assertThat(stale.get("outcome")).isEqualTo("STALE");
            assertThat(stale.get("messageCode")).isEqualTo("ACCEPTED_SEQUENCE_MISMATCH");
            assertThat(store.current().acceptedSequence()).isEqualTo(1L);

            CompositeData malformed = new CompositeDataSupport(
                    new javax.management.openmbean.CompositeType(
                            "wrong",
                            "wrong",
                            new String[]{"protocolVersion"},
                            new String[]{"protocolVersion"},
                            new javax.management.openmbean.OpenType<?>[]{
                                    javax.management.openmbean.SimpleType.INTEGER
                            }),
                    new String[]{"protocolVersion"},
                    new Object[]{1});
            CompositeData rejected = invoke(server, malformed);
            assertThat(rejected.get("outcome")).isEqualTo("REJECTED");
            assertThat(rejected.get("messageCode")).isEqualTo("TYPE_MISMATCH");
            assertThat(store.current().acceptedSequence()).isEqualTo(1L);
        } finally {
            owner.close();
        }
    }

    @Test
    void targetPlatformProjectionContainsOnlyEightApprovedReads() {
        String runtimeId = UUID.randomUUID().toString();
        AcceptedPolicyStateStore store = store(runtimeId);
        PlatformControlReadProjection projection = new PlatformControlReadProjection(
                store,
                new PolicyApplyDiagnostics(),
                () -> false,
                () -> false);

        MBeanInfo info = projection.getMBeanInfo();

        assertThat(info.getAttributes()).hasSize(8)
                .allMatch(attribute -> attribute.isReadable() && !attribute.isWritable());
        assertThat(info.getOperations()).isEmpty();
        assertThat(projection.getAttribute("RuntimeInstanceId")).isEqualTo(runtimeId);
        assertThat(projection.getAttribute("AcceptedSequence")).isEqualTo(0L);
        assertThat(projection.getAttribute("StructuralDrift")).isEqualTo(false);
        assertThat(projection.getAttribute("RestartRequired")).isEqualTo(false);
    }

    private static CompositeData invoke(MBeanServer server, CompositeData request) throws Exception {
        return (CompositeData) server.invoke(
                PrivateControlObjectNames.WRITER,
                "applySnapshot",
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private static CompositeData request(
            String runtimeId,
            long sequence,
            EffectiveTracingPolicy policy,
            String attemptId) throws Exception {
        TabularDataSupport routes = new TabularDataSupport(PrivateControlWireTypes.ROUTES);
        for (Map.Entry<String, Double> route : policy.samplingRouteRatios().entrySet()) {
            routes.put(new CompositeDataSupport(
                    PrivateControlWireTypes.ROUTE_ROW,
                    PrivateControlWireTypes.ROUTE_KEYS,
                    new Object[]{route.getValue(), route.getKey()}));
        }
        CompositeData policyData = new CompositeDataSupport(
                PrivateControlWireTypes.POLICY,
                PrivateControlWireTypes.POLICY_KEYS,
                new Object[]{
                        policy.diagnosticsLogLevel().name(),
                        policy.exporterEnabled(),
                        policy.samplingEnabled(),
                        policy.samplingRatio(),
                        routes,
                        policy.traceControlHeadersEnabled(),
                        policy.validationEnabled(),
                        policy.validationStrict()
                });
        return new CompositeDataSupport(
                PrivateControlWireTypes.REQUEST,
                PrivateControlWireTypes.REQUEST_KEYS,
                new Object[]{
                        "REFRESH",
                        attemptId,
                        EffectiveTracingPolicyDigest.sha256(policy),
                        sequence,
                        runtimeId,
                        PrivateControlObjectNames.PROTOCOL_VERSION,
                        policyData,
                        "CONFIG_SERVER",
                        "revision-1"
                });
    }

    private static AcceptedPolicyStateStore store(String runtimeId) {
        return new AcceptedPolicyStateStore(runtimeId, policy(1.0d));
    }

    private static EffectiveTracingPolicy policy(double ratio) {
        return EffectiveTracingPolicy.of(
                true,
                ratio,
                Map.of("/orders", 0.5d),
                true,
                false,
                true,
                true,
                PolicyLogLevel.INFO);
    }
}
