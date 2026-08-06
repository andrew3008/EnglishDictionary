package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5 TEST §20 P5.4 — p5-malformed-payload-tests
 *
 * Spring-side decoder (AcceptedCompleteSnapshotDecoder.decode) MUST throw
 * IllegalArgumentException for every malformed, unknown, missing,
 * null, wrong-type, invalid-enum, NaN/Infinite/out-of-range, and oversized
 * Open-Type payload.
 *
 * No Agent classloader required — all payloads are JDK CompositeData constructed
 * in this test classloader, mirroring what the Agent would emit over JMX.
 */
class P54MalformedPayloadTest {

    // ── §1 null and non-CompositeData inputs ──────────────────────────────────

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsString() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode("not-composite-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsInteger() {
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    // ── §2 wrong CompositeType name ───────────────────────────────────────────

    @Test
    void rejectsWrongTypeName() throws OpenDataException {
        CompositeType wrongType = new CompositeType(
                "WrongName/v1", "WrongName/v1",
                new String[]{"foo"}, new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData cd = new CompositeDataSupport(
                wrongType, new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void rejectsV2TypeName() throws OpenDataException {
        // Future-version type name must not be silently accepted
        CompositeType v2 = new CompositeType(
                "AcceptedCompleteSnapshot/v2", "AcceptedCompleteSnapshot/v2",
                new String[]{"acceptedSequence"}, new String[]{"acceptedSequence"},
                new OpenType<?>[]{SimpleType.LONG});
        CompositeData cd = new CompositeDataSupport(
                v2, new String[]{"acceptedSequence"}, new Object[]{1L});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    // ── §3 missing required fields ────────────────────────────────────────────

    @Test
    void rejectsMissingRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildWith(
                new String[]{"acceptedSequence", "samplingEnabled", "samplingRatio",
                        "samplingRouteRatios", "validationEnabled", "validationStrict",
                        "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"},
                new OpenType<?>[]{SimpleType.LONG, SimpleType.BOOLEAN, SimpleType.DOUBLE,
                        makeEmptyRouteTableType(), SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING},
                new Object[]{1L, true, 0.5d, emptyRouteTable(), true, false, true, true, "INFO"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAcceptedSequence() throws OpenDataException {
        CompositeData cd = buildWith(
                new String[]{"runtimeInstanceId", "samplingEnabled", "samplingRatio",
                        "samplingRouteRatios", "validationEnabled", "validationStrict",
                        "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"},
                new OpenType<?>[]{SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.DOUBLE,
                        makeEmptyRouteTableType(), SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING},
                new Object[]{"rid-1", true, 0.5d, emptyRouteTable(), true, false, true, true, "INFO"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingSamplingRouteRatios() throws OpenDataException {
        CompositeData cd = buildWith(
                new String[]{"acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                        "samplingRatio", "validationEnabled", "validationStrict",
                        "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"},
                new OpenType<?>[]{SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                        SimpleType.DOUBLE, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                        SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING},
                new Object[]{1L, "rid-1", true, 0.5d, true, false, true, true, "INFO"});

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── §4 wrong scalar types ─────────────────────────────────────────────────

    @Test
    void rejectsSequenceAsString() throws OpenDataException {
        // acceptedSequence declared as STRING instead of LONG
        String[] keys = {
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, makeEmptyRouteTableType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        CompositeData cd = new CompositeDataSupport(ct, keys, new Object[]{
                "STRING-NOT-LONG", "rid-1", true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO"
        });

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSamplingRatioAsString() throws OpenDataException {
        // samplingRatio declared as STRING instead of DOUBLE
        String[] keys = {
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.STRING, // <-- wrong type
                makeEmptyRouteTableType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        CompositeData cd = new CompositeDataSupport(ct, keys, new Object[]{
                1L, "rid-1", true, "0.5", emptyRouteTable(),
                true, false, true, true, "INFO"
        });

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── §5 null values for non-nullable fields ────────────────────────────────

    @Test
    void rejectsNullRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, null,
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullDiagnosticsLogLevel() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, null);

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── §6 invalid enum / runtime-id / sequence ───────────────────────────────

    @Test
    void rejectsUnknownDiagnosticsLogLevel() throws OpenDataException {
        // Closed set mirrors Agent PolicyLogLevel: OFF/ERROR/WARN/INFO/DEBUG/TRACE.
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "VERBOSE");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void rejectsBlankRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "   ",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    @Test
    void rejectsEmptyRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    @Test
    void rejectsNegativeAcceptedSequence() throws OpenDataException {
        CompositeData cd = buildValidWith(
                -1L, "rid-1",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    // ── §7 NaN / Infinite / out-of-range ─────────────────────────────────────

    @Test
    void rejectsNaNSamplingRatio() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, Double.NaN, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsInfiniteSamplingRatio() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, Double.POSITIVE_INFINITY, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsSamplingRatioAboveOne() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 1.0001d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsSamplingRatioBelow0() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, -0.001d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsNaNRouteRatio() throws OpenDataException {
        TabularData badRoutes = buildRouteTable(Map.of("/api", Double.NaN));
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, badRoutes,
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInfiniteRouteRatio() throws OpenDataException {
        TabularData badRoutes = buildRouteTable(
                Map.of("/api", Double.NEGATIVE_INFINITY));
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, badRoutes,
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── §8 oversized payload ──────────────────────────────────────────────────

    @Test
    void rejectsOversizedRouteTable() throws OpenDataException {
        // Plan §20 P5.4: wire bounds — >placeholder-7 routes is oversized
        java.util.LinkedHashMap<String, Double> bigRoutes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            bigRoutes.put("/route-" + i, 0.5d);
        }
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, buildRouteTable(bigRoutes),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRouteRatios");
    }

    @Test
    void rejectsOversizedRouteKey() throws OpenDataException {
        String longKey = "/route-" + "x".repeat(1024);
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, buildRouteTable(Map.of(longKey, 0.5d)),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedRuntimeInstanceId() throws OpenDataException {
        // Codec enforces MAX_LOGICAL_ACCEPTED_COMPLETE_BYTES (32768), not a dedicated
        // runtimeInstanceId char-cap. A 40 KiB id must fail the logical payload bound.
        String longId = "x".repeat(40_960);
        CompositeData cd = buildValidWith(
                1L, longId,
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical payload exceeds");
    }

    // ── §9 unknown extra fields (forward-compat) ──────────────────────────────

    @Test
    void rejectsUnknownExtraField() throws OpenDataException {
        String[] keys = {
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel",
                "unknownFuture"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, makeEmptyRouteTableType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING, SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        CompositeData cd = new CompositeDataSupport(ct, keys, new Object[]{
                1L, "rid-1", true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO", "future-value"
        });

        // Strict equals on the canonical CompositeType rejects any schema drift
        // (extra keys change the OpenType identity even when the type name matches).
        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

    // ── §10 wrong TabularType row type name ───────────────────────────────────

    @Test
    void rejectsWrongRouteRowTypeName() throws OpenDataException {
        CompositeType wrongRow = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v2",
                "space.br1440.platform.tracing.control.RouteRatio.v2",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
        TabularType wrongTabular = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                wrongRow,
                new String[]{"routePrefix"});
        TabularDataSupport wrongTable = new TabularDataSupport(wrongTabular);

        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, wrongTable,
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedCompleteSnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CompositeData buildValidWith(
            long seq, String rid,
            boolean sampEn, double sampRatio, TabularData routes,
            boolean valEn, boolean valStrict, boolean expEn,
            boolean tcHeaders, String logLevel) throws OpenDataException {

        String[] keys = {
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, routes.getTabularType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        return new CompositeDataSupport(ct, keys, new Object[]{
                seq, rid, sampEn, sampRatio, routes,
                valEn, valStrict, expEn, tcHeaders, logLevel
        });
    }

    private CompositeData buildWith(
            String[] keys, OpenType<?>[] types, Object[] values)
            throws OpenDataException {
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        return new CompositeDataSupport(ct, keys, values);
    }

    private TabularType makeEmptyRouteTableType() throws OpenDataException {
        CompositeType rowType = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
        return new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                rowType,
                new String[]{"routePrefix"});
    }

    private TabularData emptyRouteTable() throws OpenDataException {
        return new TabularDataSupport(makeEmptyRouteTableType());
    }

    private TabularData buildRouteTable(Map<String, Double> routes)
            throws OpenDataException {
        CompositeType rowType = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
        TabularType tabType = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                rowType,
                new String[]{"routePrefix"});
        TabularDataSupport table = new TabularDataSupport(tabType);
        for (var e : routes.entrySet()) {
            table.put(new CompositeDataSupport(rowType,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }
        return table;
    }
}
