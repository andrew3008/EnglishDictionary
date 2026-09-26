package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;

class AcceptedPolicySnapshotDecoderTest {

    private static final String AGENT_INSTANCE_ID = "ae1d3aae-817e-4c6f-b803-6e50732b1d76";

    @Test
    void decodesAcceptedCompleteWithNonEmptyRouteTable() throws OpenDataException {
        String instanceId = UUID.randomUUID().toString();
        CompositeData wire = buildCompleteResponse(
                42L, instanceId, Map.of("/api", 0.75d, "/health", 0.0d));

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(42L);
        // agentInstanceId должен сохраняться при декодировании для обнаружения перезапуска
        assertThat(decoded.agentInstanceId()).isEqualTo(instanceId);
        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isTrue();
        assertThat(snap.samplingRatio()).isEqualTo(0.5d);
        assertThat(snap.samplingRouteRatios()).containsKeys("/api", "/health");
        assertThat(snap.samplingRouteRatios().get("/api")).isEqualTo(0.75d);
        assertThat(snap.samplingRouteRatios().get("/health")).isEqualTo(0.0d);
        assertThat(snap.validationLevel()).isEqualTo("WARNING");
        assertThat(snap.exporterEnabled()).isTrue();
        assertThat(snap.traceControlHeadersEnabled()).isTrue();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("INFO");
    }

    @Test
    void decodesAcceptedCompleteWithEmptyRouteTable() throws OpenDataException {
        var decoded = AcceptedPolicySnapshotDecoder.decode(
                buildCompleteResponse(0L, AGENT_INSTANCE_ID, Map.of()));

        assertThat(decoded.acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.agentInstanceId()).isEqualTo(AGENT_INSTANCE_ID);
        assertThat(decoded.snapshot().samplingRouteRatios()).isEmpty();
    }

    @Test
    void decodeCompleteThrowsOnNull() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void decodeCompleteThrowsOnWrongTypeName() throws OpenDataException {
        CompositeType wrongType = new CompositeType(
                "space.br1440.platform.tracing.control.AcceptedCompleteResponse.v1",
                "wrong name — old Spring codec or wrong Agent version",
                new String[]{"foo"}, new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData wrongData = new CompositeDataSupport(
                wrongType, new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(wrongData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType")
                .hasMessageContaining("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void decodeCompleteThrowsOnNonCompositeDataInput() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode("unexpected-string"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void decodeCompleteRouteRatioValuesAreCorrectlyMapped() throws OpenDataException {
        Map<String, Double> routes = Map.of("/slow", 0.1d, "/fast", 0.9d);
        var decoded = AcceptedPolicySnapshotDecoder.decode(
                buildCompleteResponse(1L, AGENT_INSTANCE_ID, routes));

        assertThat(decoded.snapshot().samplingRouteRatios())
                .containsEntry("/slow", 0.1d)
                .containsEntry("/fast", 0.9d);
    }

    @Test
    void decodeCompleteRouteTableIndexNameIsRoutePrefix() throws OpenDataException {
        CompositeData wire = buildCompleteResponse(5L, AGENT_INSTANCE_ID, Map.of("/x", 0.5d));
        TabularData table = (TabularData) wire.get("samplingRouteRatios");
        assertThat(table.getTabularType().getIndexNames()).containsExactly("routePrefix");
        assertThat(table.getTabularType().getRowType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.RouteRatio.v1");
        assertThat(table.getTabularType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.RouteRatios.v1");
    }

    static CompositeData buildCompleteResponse(long acceptedSequence,
                                               String agentInstanceId,
                                               Map<String, Double> routes) throws OpenDataException {

        CompositeType routeRow = JmxOpenTypes.ROUTE_RATIO;
        TabularType routesType = JmxOpenTypes.ROUTE_RATIOS;
        TabularDataSupport routeTable = new TabularDataSupport(routesType);
        for (Map.Entry<String, Double> e : routes.entrySet()) {
            routeTable.put(new CompositeDataSupport(routeRow,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }

        // Для AcceptedCompleteSnapshot/v1 используется канонический порядок полей агента.
        String[] keys = {
                "acceptedSequence",
                "agentInstanceId",
                "acceptedPolicyDigest",
                "lastApplyOutcome",
                "lastAttemptId",
                "failureCode",
                "samplingEnabled",
                "samplingRatio",
                "samplingRouteRatios",
                "validationLevel",
                "exporterEnabled",
                "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        CompositeType completeType = JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT;

        return new CompositeDataSupport(completeType, keys, new Object[]{
                acceptedSequence,
                agentInstanceId,
                "accepted-policy-digest",
                "APPLIED",
                "attempt-id",
                "",
                true,    // samplingEnabled
                0.5d,    // samplingRatio
                routeTable,
                "WARNING",
                true,    // exporterEnabled
                true,    // traceControlHeadersEnabled
                "INFO"   // diagnosticsLogLevel
        });
    }
}
