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
                "applyTrigger", "attemptId", "candidateDigest",
                "expectedAcceptedSequence", "expectedRuntimeInstanceId",
                "protocolVersion", "runtimePolicy", "sourceAuthority", "sourceRevision");
        CompositeData policy = (CompositeData) request.get("runtimePolicy");
        assertThat(policy.getCompositeType().keySet()).hasSize(8);
        TabularData table = (TabularData) policy.get("samplingRouteRatios");
        assertThat(table.size()).isEqualTo(2);
        assertThat(table.getTabularType().getIndexNames()).containsExactly("routePrefix");
    }

    // ── decode: readAcceptedComplete (PRIV-R07) ────────────────────────────────
    //
    // buildCompleteResponse() constructs CompositeData using the exact same
    // type name and field order as Agent PrivateControlWireTypes.ACCEPTED_COMPLETE:
    //   "AcceptedCompleteSnapshot/v1"
    //   fields: acceptedSequence, runtimeInstanceId, samplingEnabled,
    //           samplingRatio, samplingRouteRatios, validationEnabled,
    //           validationStrict, exporterEnabled, traceControlHeadersEnabled,
    //           diagnosticsLogLevel

    @Test
    void decodesAcceptedCompleteWithNonEmptyRouteTable() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        String instanceId = "agent-instance-" + UUID.randomUUID();
        CompositeData wire = buildCompleteResponse(
                42L, instanceId, Map.of("/api", 0.75d, "/health", 0.0d));

        var decoded = codec.decodeCompleteWithSequence(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(42L);
        // runtimeInstanceId MUST be preserved through decode — used for restart detection
        assertThat(decoded.runtimeInstanceId()).isEqualTo(instanceId);
        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isTrue();
        assertThat(snap.samplingRatio()).isEqualTo(0.5d);
        assertThat(snap.samplingRouteRatios()).containsKeys("/api", "/health");
        assertThat(snap.samplingRouteRatios().get("/api")).isEqualTo(0.75d);
        assertThat(snap.samplingRouteRatios().get("/health")).isEqualTo(0.0d);
        assertThat(snap.validationEnabled()).isTrue();
        assertThat(snap.validationStrict()).isFalse();
        assertThat(snap.exporterEnabled()).isTrue();
        assertThat(snap.traceControlHeadersEnabled()).isTrue();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("INFO");
    }

    @Test
    void decodesAcceptedCompleteWithEmptyRouteTable() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        var decoded = codec.decodeCompleteWithSequence(
                buildCompleteResponse(0L, "rid-zero", Map.of()));

        assertThat(decoded.acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.runtimeInstanceId()).isEqualTo("rid-zero");
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
    void decodeCompleteThrowsOnWrongTypeName() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        // Simulates version mismatch: correct shape, wrong type name.
        CompositeType wrongType = new CompositeType(
                "space.br1440.platform.tracing.control.AcceptedCompleteResponse.v1",
                "wrong name — old Spring codec or wrong Agent version",
                new String[]{"foo"}, new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData wrongData = new CompositeDataSupport(
                wrongType, new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> codec.decodeCompleteWithSequence(wrongData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType")
                .hasMessageContaining("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void decodeCompleteThrowsOnNonCompositeDataInput() {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        // E.g. Agent returns String instead of CompositeData (version mismatch)
        assertThatThrownBy(() -> codec.decodeCompleteWithSequence("unexpected-string"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void decodeCompleteRouteRatioValuesAreCorrectlyMapped() throws OpenDataException {
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        // Verify routePrefix → ratio mapping is not swapped
        Map<String, Double> routes = Map.of("/slow", 0.1d, "/fast", 0.9d);
        var decoded = codec.decodeCompleteWithSequence(
                buildCompleteResponse(1L, "rid-map", routes));

        assertThat(decoded.snapshot().samplingRouteRatios())
                .containsEntry("/slow", 0.1d)
                .containsEntry("/fast", 0.9d);
    }

    @Test
    void decodeCompleteRouteTableIndexNameIsRoutePrefix() throws OpenDataException {
        // Guard: if Agent changes index name, the route table would still parse
        // but routePrefix/ratio slots could silently swap.
        // This test pins the index contract used by buildCompleteResponse().
        PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
        CompositeData wire = buildCompleteResponse(5L, "rid-idx", Map.of("/x", 0.5d));
        TabularData table = (TabularData) wire.get("samplingRouteRatios");
        assertThat(table.getTabularType().getIndexNames()).containsExactly("routePrefix");
        assertThat(table.getTabularType().getRowType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.RouteRatio.v1");
        assertThat(table.getTabularType().getTypeName())
                .isEqualTo("space.br1440.platform.tracing.control.RouteRatios.v1");
    }

    // ── helper ────────────────────────────────────────────────────────────────

    /**
     * Constructs a CompositeData payload matching Agent
     * {@code PrivateControlWireTypes.ACCEPTED_COMPLETE} exactly:
     * <ul>
     *   <li>type name: {@code "AcceptedCompleteSnapshot/v1"}</li>
     *   <li>field order: acceptedSequence, runtimeInstanceId, samplingEnabled,
     *       samplingRatio, samplingRouteRatios, validationEnabled, validationStrict,
     *       exporterEnabled, traceControlHeadersEnabled, diagnosticsLogLevel</li>
     *   <li>route sub-types: {@code RouteRatio.v1} / {@code RouteRatios.v1} / index=routePrefix</li>
     * </ul>
     * Any drift from Agent schema will cause
     * {@link PrivateControlOpenTypeCodec#decodeCompleteWithSequence} to throw,
     * making the tests self-enforcing.
     */
    private static CompositeData buildCompleteResponse(
            long acceptedSequence,
            String runtimeInstanceId,
            Map<String, Double> routes) throws OpenDataException {

        // Route sub-types — must mirror PrivateControlWireTypes exactly
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

        // "AcceptedCompleteSnapshot/v1" — Agent-canonical field order
        String[] keys = {
                "acceptedSequence",
                "runtimeInstanceId",
                "samplingEnabled",
                "samplingRatio",
                "samplingRouteRatios",
                "validationEnabled",
                "validationStrict",
                "exporterEnabled",
                "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.LONG,
                SimpleType.STRING,
                SimpleType.BOOLEAN,
                SimpleType.DOUBLE,
                routesType,
                SimpleType.BOOLEAN,
                SimpleType.BOOLEAN,
                SimpleType.BOOLEAN,
                SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType completeType = new CompositeType(
                "AcceptedCompleteSnapshot/v1",
                "AcceptedCompleteSnapshot/v1",
                keys, keys, types);

        return new CompositeDataSupport(completeType, keys, new Object[]{
                acceptedSequence,
                runtimeInstanceId,
                true,    // samplingEnabled
                0.5d,    // samplingRatio
                routeTable,
                true,    // validationEnabled
                false,   // validationStrict
                true,    // exporterEnabled
                true,    // traceControlHeadersEnabled
                "INFO"   // diagnosticsLogLevel
        });
    }
}
