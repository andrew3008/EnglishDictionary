package space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

class PrivateControlOpenTypeCodecTest {

    // ── encode ─────────────────────────────────────────────────────────────────

    @Test
    void encodesExactCompleteSnapshotWithDeterministicRouteTable() {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        Map<String, Double> routes = new LinkedHashMap<>();
        routes.put("/z", 0.2d);
        routes.put("/a", 0.8d);

        CompositeData request = codec.encodeRequest(
                new PrivateControlOpenTypeCodec.PrivateControlSnapshot(
                        UUID.randomUUID().toString(),
                        7L,
                        UUID.randomUUID().toString(),
                        "CONFIG_SERVER",
                        "REFRESH",
                        "revision-7",
                        "a".repeat(64),
                        true,
                        0.5d,
                        routes,
                        true,
                        false,
                        true,
                        true,
                        "INFO"));

        assertThat(request.getCompositeType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.ApplySnapshotRequest.v1");
        assertThat(request.getCompositeType().keySet()).containsExactlyInAnyOrder(
                "applyTrigger",
                "attemptId",
                "candidateDigest",
                "expectedAcceptedSequence",
                "expectedRuntimeInstanceId",
                "protocolVersion",
                "runtimePolicy",
                "sourceAuthority",
                "sourceRevision");
        CompositeData policy = (CompositeData) request.get("runtimePolicy");
        assertThat(policy.getCompositeType().keySet()).hasSize(8);
        TabularData table = (TabularData) policy.get("samplingRouteRatios");
        assertThat(table.size()).isEqualTo(2);
        assertThat(table.getTabularType().getIndexNames()).containsExactly("routePrefix");
    }

    // ── decode: readAcceptedComplete (PRIV-R07) ────────────────────────────────

    /**
     * Full round-trip: Agent wire payload → AcceptedSnapshotWithSequence.
     * Constructs a CompositeData matching the AcceptedCompleteResponse.v1 schema
     * exactly as the Agent MBean would return it, then verifies every leaf.
     */
    @Test
    void decodesAcceptedCompleteWithNonEmptyRouteTable() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        CompositeData wire = buildCompleteResponse(42L, Map.of("/api", 0.75d, "/health", 0.0d));

        var decoded = codec.decodeCompleteWithSequence(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(42L);
        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isTrue();
        assertThat(snap.samplingRatio()).isEqualTo(0.5d);
        assertThat(snap.samplingRouteRatios()).containsKeys("/api", "/health");
        assertThat(snap.samplingRouteRatios().get("/api")).isEqualTo(0.75d);
        assertThat(snap.validationEnabled()).isTrue();
        assertThat(snap.validationStrict()).isFalse();
        assertThat(snap.exporterEnabled()).isTrue();
        assertThat(snap.traceControlHeadersEnabled()).isTrue();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("INFO");
    }

    @Test
    void decodesAcceptedCompleteWithEmptyRouteTable() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        CompositeData wire = buildCompleteResponse(0L, Map.of());

        var decoded = codec.decodeCompleteWithSequence(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.snapshot().samplingRouteRatios()).isEmpty();
    }

    @Test
    void decodeCompleteThrowsOnNull() {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        assertThatThrownBy(() -> codec.decodeCompleteWithSequence(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void decodeCompleteThrowsOnWrongCompositeType() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        // Build a CompositeData with a different type name — simulates version mismatch.
        CompositeType wrongType = new CompositeType(
                "space.br1440.platform.tracing.control.WRONG.v99",
                "wrong",
                new String[]{"foo"},
                new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData wrongData = new CompositeDataSupport(wrongType,
                new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> codec.decodeCompleteWithSequence(wrongData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Constructs the exact AcceptedCompleteResponse.v1 CompositeData that the Agent
     * MBean will return once PROPOSED_PRIV_R07 is approved and merged.
     * Field order and type names must match the Agent-side PrivateControlWireTypes constants.
     */
    private static CompositeData buildCompleteResponse(
            long acceptedSequence,
            Map<String, Double> routes) throws OpenDataException {

        // Route sub-types (must mirror PrivateControlWireTypes exactly)
        CompositeType routeRow = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
        TabularType routesType = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                routeRow,
                new String[]{"routePrefix"});
        TabularDataSupport routeTable = new TabularDataSupport(routesType);
        for (Map.Entry<String, Double> e : routes.entrySet()) {
            routeTable.put(new CompositeDataSupport(routeRow,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }

        // AcceptedCompleteResponse.v1 (10 fields, canonical alphabetical order)
        String[] keys = {
                "acceptedSequence",
                "diagnosticsLogLevel",
                "exporterEnabled",
                "runtimeInstanceId",
                "samplingEnabled",
                "samplingRatio",
                "samplingRouteRatios",
                "traceControlHeadersEnabled",
                "validationEnabled",
                "validationStrict"
        };
        OpenType<?>[] types = {
                SimpleType.LONG,
                SimpleType.STRING,
                SimpleType.BOOLEAN,
                SimpleType.STRING,
                SimpleType.BOOLEAN,
                SimpleType.DOUBLE,
                routesType,
                SimpleType.BOOLEAN,
                SimpleType.BOOLEAN,
                SimpleType.BOOLEAN
        };
        CompositeType completeType = new CompositeType(
                "space.br1440.platform.tracing.control.AcceptedCompleteResponse.v1",
                "space.br1440.platform.tracing.control.AcceptedCompleteResponse.v1",
                keys, keys, types);

        return new CompositeDataSupport(completeType, keys, new Object[]{
                acceptedSequence,
                "INFO",
                true,
                "test-runtime-id",
                true,
                0.5d,
                routeTable,
                true,
                true,
                false
        });
    }
}
