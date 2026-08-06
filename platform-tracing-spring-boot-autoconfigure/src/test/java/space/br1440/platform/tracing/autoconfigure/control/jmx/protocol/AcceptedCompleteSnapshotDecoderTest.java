package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

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
import java.util.Map;
import java.util.UUID;

/**
 * Smoke-тест {@link AcceptedCompleteSnapshotDecoder#decode(Object)} (PRIV-R07).
 * Расширенные негативные сценарии — в {@code P54MalformedPayloadTest} и
 * {@code P55MalformedPayloadTest}.
 */
class AcceptedCompleteSnapshotDecoderTest {

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
        String instanceId = "agent-instance-" + UUID.randomUUID();
        CompositeData wire = buildCompleteResponse(
                42L, instanceId, Map.of("/api", 0.75d, "/health", 0.0d));

        var decoded = AcceptedCompleteSnapshotDecoder.decode(wire);

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
        var decoded = AcceptedCompleteSnapshotDecoder.decode(
                buildCompleteResponse(0L, "rid-zero", Map.of()));

        assertThat(decoded.acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.runtimeInstanceId()).isEqualTo("rid-zero");
        assertThat(decoded.snapshot().samplingRouteRatios()).isEmpty();
    }

    @Test
    void decodeCompleteThrowsOnNull() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void decodeCompleteThrowsOnWrongTypeName() throws OpenDataException {
        // Simulates version mismatch: correct shape, wrong type name.
        CompositeType wrongType = new CompositeType(
                "space.br1440.platform.tracing.control.AcceptedCompleteResponse.v1",
                "wrong name — old Spring codec or wrong Agent version",
                new String[]{"foo"}, new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData wrongData = new CompositeDataSupport(
                wrongType, new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(wrongData))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType")
                .hasMessageContaining("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void decodeCompleteThrowsOnNonCompositeDataInput() {
        // E.g. Agent returns String instead of CompositeData (version mismatch)
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode("unexpected-string"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void decodeCompleteRouteRatioValuesAreCorrectlyMapped() throws OpenDataException {
        // Verify routePrefix → ratio mapping is not swapped
        Map<String, Double> routes = Map.of("/slow", 0.1d, "/fast", 0.9d);
        var decoded = AcceptedCompleteSnapshotDecoder.decode(
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
     * {@link AcceptedCompleteSnapshotDecoder#decode} to throw,
     * making the tests self-enforcing.
     */
    static CompositeData buildCompleteResponse(
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
