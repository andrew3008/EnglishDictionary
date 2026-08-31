package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
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

import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class MalformedPayloadTest {

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsString() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode("not-composite-data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsInteger() {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(42))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected CompositeData");
    }

    @Test
    void rejectsWrongTypeName() throws OpenDataException {
        CompositeType wrongType = new CompositeType(
                "WrongName/v1", "WrongName/v1",
                new String[]{"foo"}, new String[]{"foo"},
                new OpenType<?>[]{SimpleType.STRING});
        CompositeData cd = new CompositeDataSupport(
                wrongType, new String[]{"foo"}, new Object[]{"bar"});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void rejectsV2TypeName() throws OpenDataException {
        CompositeType v2 = new CompositeType(
                "AcceptedCompleteSnapshot/v2", "AcceptedCompleteSnapshot/v2",
                new String[]{"acceptedSequence"}, new String[]{"acceptedSequence"},
                new OpenType<?>[]{SimpleType.LONG}
        );

        CompositeData cd = new CompositeDataSupport(v2, new String[]{"acceptedSequence"}, new Object[]{1L});

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSequenceAsString() throws OpenDataException {
        // acceptedSequence declared as STRING instead of LONG
        String[] keys = acceptedSnapshotFieldNames();
        OpenType<?>[] types = {
                SimpleType.STRING, SimpleType.STRING, SimpleType.STRING,
                SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, makeEmptyRouteTableType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        CompositeData cd = new CompositeDataSupport(ct, keys, new Object[]{
                "STRING-NOT-LONG", "rid-1", "accepted-policy-digest", "APPLIED", "attempt-id", "",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO"
        });

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSamplingRatioAsString() throws OpenDataException {
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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, null,
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullDiagnosticsLogLevel() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, null);

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownDiagnosticsLogLevel() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "VERBOSE");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diagnosticsLogLevel");
    }

    @Test
    void rejectsBlankRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "   ",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    @Test
    void rejectsEmptyRuntimeInstanceId() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runtimeInstanceId");
    }

    @Test
    void rejectsNegativeAcceptedSequence() throws OpenDataException {
        CompositeData cd = buildValidWith(
                -1L, "rid-1",
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptedSequence");
    }

    @Test
    void rejectsNaNSamplingRatio() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, Double.NaN, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsInfiniteSamplingRatio() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, Double.POSITIVE_INFINITY, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsSamplingRatioAboveOne() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 1.0001d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("samplingRatio");
    }

    @Test
    void rejectsSamplingRatioBelow0() throws OpenDataException {
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, -0.001d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedRouteTable() throws OpenDataException {
        java.util.LinkedHashMap<String, Double> bigRoutes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            bigRoutes.put("/route-" + i, 0.5d);
        }
        CompositeData cd = buildValidWith(
                1L, "rid-1",
                true, 0.5d, buildRouteTable(bigRoutes),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedRuntimeInstanceId() throws OpenDataException {
        String longId = "x".repeat(40_960);
        CompositeData cd = buildValidWith(
                1L, longId,
                true, 0.5d, emptyRouteTable(),
                true, false, true, true, "INFO");

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logical payload exceeds");
    }

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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected CompositeType");
    }

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

        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(cd))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CompositeData buildValidWith(long seq, String rid,
                                         boolean sampEn, double sampRatio, TabularData routes,
                                         boolean valEn, boolean valStrict, boolean expEn,
                                         boolean tcHeaders, String logLevel) throws OpenDataException {

        String[] keys = acceptedSnapshotFieldNames();
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.STRING,
                SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, routes.getTabularType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        return new CompositeDataSupport(ct, keys, new Object[]{
                seq, rid, "accepted-policy-digest", "APPLIED", "attempt-id", "",
                sampEn, sampRatio, routes,
                valEn, valStrict, expEn, tcHeaders, logLevel
        });
    }

    private static String[] acceptedSnapshotFieldNames() {
        return new String[]{
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_RUNTIME_INSTANCE_ID,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_POLICY_DIGEST,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_LAST_APPLY_OUTCOME,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_LAST_ATTEMPT_ID,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_FAILURE_CODE,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_STRICT,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED,
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL
        };
    }

    private CompositeData buildWith(String[] keys, OpenType<?>[] types, Object[] values)
            throws OpenDataException {
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types
        );
        return new CompositeDataSupport(ct, keys, values);
    }

    private TabularType makeEmptyRouteTableType() throws OpenDataException {
        CompositeType rowType = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING}
        );

        return new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                rowType,
                new String[]{"routePrefix"});
    }

    private TabularData emptyRouteTable() throws OpenDataException {
        return new TabularDataSupport(makeEmptyRouteTableType());
    }

    private TabularData buildRouteTable(Map<String, Double> routes) throws OpenDataException {
        CompositeType rowType = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING}
        );

        TabularType tabType = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                rowType,
                new String[]{"routePrefix"}
        );

        TabularDataSupport table = new TabularDataSupport(tabType);
        for (var e : routes.entrySet()) {
            table.put(new CompositeDataSupport(rowType,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }
        return table;
    }
}
