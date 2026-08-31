package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.AcceptedPolicySnapshotDecoder;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.read.AcceptedSettingsWithSequence;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle.PlatformSettingsStatusMBean;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingLogLevel;
import space.br1440.platform.tracing.otel.javaagent.control.settings.model.TracingSettings;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsStatusSnapshot;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

class AgentToSpringWireCodecParityTest {

    // ── Позитивные сценарии ─────────────────────────────────────────────────────

    @Test
    void defaultPolicyRoundTrips() {
        assertRoundTrip(envelope(0L, uuid(), TracingSettings.of(
                true, 1.0, Map.of(),
                true, false, true, true, TracingLogLevel.INFO)));
    }

    @Test
    void nonEmptyRouteRatiosRoundTrip() {
        assertRoundTrip(envelope(42L, uuid(), TracingSettings.of(
                true, 0.25, Map.of("/checkout", 0.5, "/search", 0.3, "/health", 0.0),
                true, true, false, false, TracingLogLevel.DEBUG)));
    }

    @Test
    void emptyRouteRatiosRoundTrip() {
        assertRoundTrip(envelope(7L, uuid(), TracingSettings.of(
                false, 0.0, Map.of(),
                false, false, false, false, TracingLogLevel.OFF)));
    }

    @Test
    void allDiagnosticsLogLevelsRoundTrip() {
        for (TracingLogLevel level : TracingLogLevel.values()) {
            assertRoundTrip(envelope(1L, uuid(), TracingSettings.of(
                    true, 0.5, Map.of("/a", 0.1),
                    true, false, true, false, level)));
        }
    }

    @Test
    void minimumSequenceRoundTrips() {
        assertRoundTrip(envelope(0L, uuid(), TracingSettings.of(
                true, 1.0, Map.of(),
                true, false, true, true, TracingLogLevel.WARN)));
    }

    @Test
    void nonZeroSequenceRoundTrips() {
        assertRoundTrip(envelope(9_223_372_036L, uuid(), TracingSettings.of(
                true, 1.0, Map.of(),
                true, false, true, true, TracingLogLevel.WARN)));
    }

    @Test
    void runtimeInstanceIdIsPreservedExactly() {
        String runtimeId = uuid();
        AcceptedSettingsWithSequence decoded = roundTrip(
                envelope(3L, runtimeId, TracingSettings.of(
                        true, 1.0, Map.of(), true, false, true, true, TracingLogLevel.INFO)));
        assertThat(decoded.runtimeInstanceId()).isEqualTo(runtimeId);
    }

    @Test
    void allEightPolicyLeavesRoundTripIndependently() {
        TracingSettings policy = TracingSettings.of(
                false, 0.73, Map.of("/x", 0.9),
                false, true, false, true, TracingLogLevel.TRACE);
        AcceptedSettingsWithSequence decoded = roundTrip(envelope(5L, uuid(), policy));
        CompleteTracingSettings snapshot = decoded.snapshot();

        assertThat(snapshot.samplingEnabled()).isEqualTo(policy.samplingEnabled());
        assertThat(snapshot.samplingRatio()).isEqualTo(policy.samplingRatio());
        assertThat(snapshot.samplingRouteRatios()).isEqualTo(policy.samplingRouteRatios());
        assertThat(snapshot.validationEnabled()).isEqualTo(policy.validationEnabled());
        assertThat(snapshot.validationStrict()).isEqualTo(policy.validationStrict());
        assertThat(snapshot.exporterEnabled()).isEqualTo(policy.exporterEnabled());
        assertThat(snapshot.traceControlHeadersEnabled()).isEqualTo(policy.traceControlHeadersEnabled());
        assertThat(snapshot.diagnosticsLogLevel()).isEqualTo(policy.diagnosticsLogLevel().name());
    }

    @Test
    void routeMappingIsDeterministic() {
        Map<String, Double> insertedAscending = new LinkedHashMap<>();
        insertedAscending.put("/a", 0.1);
        insertedAscending.put("/m", 0.5);
        insertedAscending.put("/z", 0.9);
        Map<String, Double> insertedDescending = new LinkedHashMap<>();
        insertedDescending.put("/z", 0.9);
        insertedDescending.put("/m", 0.5);
        insertedDescending.put("/a", 0.1);

        AcceptedSettingsWithSequence first = roundTrip(envelope(1L, uuid(), TracingSettings.of(
                true, 0.5, insertedAscending, true, false, true, true, TracingLogLevel.INFO)));
        AcceptedSettingsWithSequence second = roundTrip(envelope(1L, uuid(), TracingSettings.of(
                true, 0.5, insertedDescending, true, false, true, true, TracingLogLevel.INFO)));

        assertThat(first.snapshot().samplingRouteRatios())
                .isEqualTo(second.snapshot().samplingRouteRatios());
    }

    // ── Покрытие одиночных булевых значений через реальный кодировщик агента ──────

    @Test
    void samplingEnabledOneHotRoundTripsThroughRealAgentEncoder() {
        assertBooleanOneHot(true, false, false, false, false);
    }

    @Test
    void validationEnabledOneHotRoundTripsThroughRealAgentEncoder() {
        assertBooleanOneHot(false, true, false, false, false);
    }

    @Test
    void validationStrictOneHotRoundTripsThroughRealAgentEncoder() {
        assertBooleanOneHot(false, false, true, false, false);
    }

    @Test
    void exporterEnabledOneHotRoundTripsThroughRealAgentEncoder() {
        assertBooleanOneHot(false, false, false, true, false);
    }

    @Test
    void traceControlHeadersEnabledOneHotRoundTripsThroughRealAgentEncoder() {
        assertBooleanOneHot(false, false, false, false, true);
    }

    private static void assertBooleanOneHot(boolean samplingEnabled, boolean validationEnabled,
            boolean validationStrict, boolean exporterEnabled, boolean traceControlHeadersEnabled) {
        TracingSettings policy = TracingSettings.of(
                samplingEnabled, 0.5, Map.of(),
                validationEnabled, validationStrict, exporterEnabled, traceControlHeadersEnabled,
                TracingLogLevel.INFO);
        CompleteTracingSettings snapshot = roundTrip(envelope(1L, uuid(), policy)).snapshot();

        assertThat(snapshot.samplingEnabled()).isEqualTo(samplingEnabled);
        assertThat(snapshot.validationEnabled()).isEqualTo(validationEnabled);
        assertThat(snapshot.validationStrict()).isEqualTo(validationStrict);
        assertThat(snapshot.exporterEnabled()).isEqualTo(exporterEnabled);
        assertThat(snapshot.traceControlHeadersEnabled()).isEqualTo(traceControlHeadersEnabled);
    }

    // ── Негативные сценарии: Spring-декодер отклоняет некорректную нагрузку ────────

    @Test
    void rejectsWrongRootCompositeTypeName() {
        CompositeType wrongName = composite("NotAcceptedCompleteSnapshot/v1", validCompleteKeys(), validCompleteOpenTypes());
        CompositeData malformed = compositeData(wrongName, validCompleteKeys(), validCompleteValues(routeTable(Map.of())));
        assertRejected(malformed);
    }

    @Test
    void rejectsCompositeDataWithMissingField() {
        String[] keys = {"acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled"};
        // Поле diagnosticsLogLevel полностью отсутствует.
        OpenType<?>[] types = {SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, routeTableType(), SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN};
        CompositeType missingField = composite("AcceptedCompleteSnapshot/v1", keys, types);
        Object[] values = {0L, uuid(), true, 0.5, routeTable(Map.of()), true, false, true, true};
        assertRejected(compositeData(missingField, keys, values));
    }

    @Test
    void rejectsCompositeDataWithUnexpectedExtraField() {
        String[] keys = concat(validCompleteKeys(), "unexpectedField");
        OpenType<?>[] types = concat(validCompleteOpenTypes(), SimpleType.STRING);
        CompositeType extraField = composite("AcceptedCompleteSnapshot/v1", keys, types);
        Object[] values = concat(validCompleteValues(routeTable(Map.of())), "surprise");
        assertRejected(compositeData(extraField, keys, values));
    }

    @Test
    void rejectsWrongScalarOpenType() {
        // Поле samplingRatio объявлено как STRING вместо DOUBLE.
        String[] keys = validCompleteKeys();
        OpenType<?>[] types = validCompleteOpenTypes().clone();
        types[3] = SimpleType.STRING;
        CompositeType wrongScalar = composite("AcceptedCompleteSnapshot/v1", keys, types);
        Object[] values = validCompleteValues(routeTable(Map.of()));
        values[3] = "0.5";
        assertRejected(compositeData(wrongScalar, keys, values));
    }

    @Test
    void rejectsWrongRouteTabularType() {
        CompositeType wrongRouteRow = composite(
                "space.br1440.platform.tracing.control.WrongRouteRow.v1",
                new String[]{"prefixOnly"},
                new OpenType<?>[]{SimpleType.STRING});
        try {
            TabularType wrongTabular = new TabularType(
                    "space.br1440.platform.tracing.control.WrongRoutes.v1",
                    "wrong", wrongRouteRow, new String[]{"prefixOnly"});
            String[] keys = validCompleteKeys();
            OpenType<?>[] types = validCompleteOpenTypes().clone();
            types[4] = wrongTabular;
            CompositeType wrongRouteContainer = composite("AcceptedCompleteSnapshot/v1", keys, types);
            TabularDataSupport badTable = new TabularDataSupport(wrongTabular);
            badTable.put(new CompositeDataSupport(wrongRouteRow, new String[]{"prefixOnly"}, new Object[]{"/x"}));
            Object[] values = validCompleteValues(badTable);
            assertRejected(compositeData(wrongRouteContainer, keys, values));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsNullField() {
        String[] keys = validCompleteKeys();
        OpenType<?>[] types = validCompleteOpenTypes();
        CompositeType type = composite("AcceptedCompleteSnapshot/v1", keys, types);
        Object[] values = validCompleteValues(routeTable(Map.of()));
        values[1] = null; // идентификатор процесса
        assertRejected(compositeData(type, keys, values));
    }

    @Test
    void rejectsNegativeAcceptedSequence() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE,
                -1L)));
    }

    @Test
    void rejectsBlankRuntimeInstanceId() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_RUNTIME_INSTANCE_ID,
                "   ")));
    }

    @Test
    void rejectsNaNSamplingRatio() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                Double.NaN)));
    }

    @Test
    void rejectsInfiniteSamplingRatio() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                Double.POSITIVE_INFINITY)));
    }

    @Test
    void rejectsSamplingRatioAboveOne() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                1.1)));
    }

    @Test
    void rejectsSamplingRatioBelowZero() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                -0.1)));
    }

    @Test
    void rejectsRouteRatioOutsideBounds() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                contractRouteTable(Map.of("/bad", 1.5)))));
    }

    @Test
    void rejectsRouteCountAboveMaximum() {
        Map<String, Double> tooMany = new TreeMap<>();
        for (int i = 0; i < 101; i++) tooMany.put("/route" + i, 0.1);
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                contractRouteTable(tooMany))));
    }

    @Test
    void rejectsRouteKeyAboveExactConfiguredLimit() {
        String tooLong = "a".repeat(256);
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                contractRouteTable(Map.of(tooLong, 0.5)))));
    }

    @Test
    void acceptsRouteKeyAtExactConfiguredLimit() {
        String exact = "a".repeat(255);
        AcceptedSettingsWithSequence decoded =
                AcceptedPolicySnapshotDecoder.decode(
                        validCompositeDataWith(fields -> fields.put(
                                JmxWireContract
                                        .FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                                contractRouteTable(Map.of(exact, 0.5)))));
        assertThat(decoded.snapshot().samplingRouteRatios()).containsEntry(exact, 0.5);
    }

    @Test
    void rejectsInvalidDiagnosticsLogLevel() {
        assertRejected(validCompositeDataWith(fields -> fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL,
                "VERBOSE")));
    }

    // ── Вспомогательные методы ──────────────────────────────────────────────────

    private static void assertRoundTrip(TracingSettingsRevision envelope) {
        AcceptedSettingsWithSequence decoded = roundTrip(envelope);

        assertThat(decoded.acceptedSequence()).isEqualTo(envelope.acceptedSequence());
        assertThat(decoded.runtimeInstanceId()).isEqualTo(envelope.runtimeInstanceId());
        CompleteTracingSettings snapshot = decoded.snapshot();
        assertThat(snapshot.samplingEnabled()).isEqualTo(envelope.samplingEnabled());
        assertThat(snapshot.samplingRatio()).isEqualTo(envelope.samplingRatio());
        assertThat(snapshot.samplingRouteRatios()).isEqualTo(envelope.samplingRouteRatios());
        assertThat(snapshot.validationEnabled()).isEqualTo(envelope.validationEnabled());
        assertThat(snapshot.validationStrict()).isEqualTo(envelope.validationStrict());
        assertThat(snapshot.exporterEnabled()).isEqualTo(envelope.exporterEnabled());
        assertThat(snapshot.traceControlHeadersEnabled()).isEqualTo(envelope.traceControlHeadersEnabled());
        assertThat(snapshot.diagnosticsLogLevel()).isEqualTo(envelope.diagnosticsLogLevel());
    }

    private static AcceptedSettingsWithSequence roundTrip(TracingSettingsRevision envelope) {
        TracingSettingsUpdater updater = mock(TracingSettingsUpdater.class);
        when(updater.captureStatus()).thenReturn(new SettingsStatusSnapshot(
                envelope,
                new LastSettingsUpdate().snapshot()));
        PlatformSettingsStatusMBean status = new PlatformSettingsStatusMBean(updater);
        try {
            CompositeData wire = (CompositeData) status.getAttribute(
                    JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT);
            return AcceptedPolicySnapshotDecoder.decode(wire);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read encoded accepted-policy snapshot", exception);
        }
    }

    private static TracingSettingsRevision envelope(long sequence, String runtimeInstanceId, TracingSettings policy) {
        return new TracingSettingsRevision(runtimeInstanceId, sequence, policy);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static void assertRejected(CompositeData malformed) {
        assertThatThrownBy(() -> AcceptedPolicySnapshotDecoder.decode(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CompositeData validCompositeDataWith(Consumer<Map<String, Object>> override) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE,
                0L);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_RUNTIME_INSTANCE_ID,
                uuid());
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_POLICY_DIGEST,
                "a".repeat(64));
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_LAST_APPLY_OUTCOME,
                "");
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_LAST_ATTEMPT_ID,
                "");
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_FAILURE_CODE,
                "");
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED,
                true);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                0.5);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                contractRouteTable(Map.of()));
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_ENABLED,
                true);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_STRICT,
                false);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED,
                true);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED,
                true);
        fields.put(
                JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL,
                "INFO");
        override.accept(fields);
        return compositeData(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT, fields);
    }

    private static String[] validCompleteKeys() {
        return new String[]{
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled", "samplingRatio",
                "samplingRouteRatios", "validationEnabled", "validationStrict",
                "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel"
        };
    }

    private static OpenType<?>[] validCompleteOpenTypes() {
        return new OpenType<?>[]{
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN, SimpleType.DOUBLE,
                routeTableType(), SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.STRING
        };
    }

    private static Object[] validCompleteValues(TabularData routes) {
        return new Object[]{
                0L, uuid(), true, 0.5, routes, true, false, true, true, "INFO"
        };
    }

    private static TabularType routeTableType() {
        try {
            CompositeType row = new CompositeType(
                    "space.br1440.platform.tracing.control.RouteRatio.v1",
                    "row", new String[]{"ratio", "routePrefix"},
                    new String[]{"ratio", "routePrefix"},
                    new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING});
            return new TabularType(
                    "space.br1440.platform.tracing.control.RouteRatios.v1",
                    "routes", row, new String[]{"routePrefix"});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TabularData routeTable(Map<String, Double> ratios) {
        try {
            TabularType type = routeTableType();
            TabularDataSupport table = new TabularDataSupport(type);
            for (Map.Entry<String, Double> entry : ratios.entrySet()) {
                table.put(new CompositeDataSupport(
                        type.getRowType(),
                        new String[]{"ratio", "routePrefix"},
                        new Object[]{entry.getValue(), entry.getKey()}));
            }
            return table;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TabularData contractRouteTable(Map<String, Double> ratios) {
        try {
            TabularDataSupport table = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
            for (Map.Entry<String, Double> entry : ratios.entrySet()) {
                table.put(new CompositeDataSupport(
                        JmxOpenTypes.ROUTE_RATIO,
                        new String[]{
                                JmxWireContract.FIELD_ROUTE_RATIO_RATIO,
                                JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX
                        },
                        new Object[]{entry.getValue(), entry.getKey()}));
            }

            return table;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot create contract route ratio table", exception);
        }
    }

    private static CompositeType composite(String name, String[] keys, OpenType<?>[] types) {
        try {
            return new CompositeType(name, name, keys, keys, types);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompositeData compositeData(CompositeType type, String[] keys, Object[] values) {
        try {
            return new CompositeDataSupport(type, keys, values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static CompositeData compositeData(CompositeType type, Map<String, Object> fields) {
        try {
            return new CompositeDataSupport(type, fields);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static String[] concat(String[] array, String extra) {
        String[] result = new String[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = extra;
        return result;
    }

    @SuppressWarnings("SameParameterValue")
    private static OpenType<?>[] concat(OpenType<?>[] array, OpenType<?> extra) {
        OpenType<?>[] result = new OpenType<?>[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = extra;
        return result;
    }

    @SuppressWarnings("SameParameterValue")
    private static Object[] concat(Object[] array, Object extra) {
        Object[] result = new Object[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = extra;
        return result;
    }
}
