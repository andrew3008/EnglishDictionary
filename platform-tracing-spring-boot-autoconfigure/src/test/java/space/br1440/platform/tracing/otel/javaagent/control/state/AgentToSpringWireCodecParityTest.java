package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.autoconfigure.configuration.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.configuration.CompleteTracingPolicySnapshot;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real cross-module Agent-to-Spring wire codec parity test (P4 audit checklist item #2,
 * remaining-blocker closure item #1).
 * <p>
 * Deliberately declared in the Agent-internal package name {@code control.state} (a
 * package-local test bridge, option 2 of the preferred list) so this test-scope-only class
 * can call the real, package-private Agent encoder
 * {@code PrivateControlOpenTypeCodec.encodeAcceptedComplete(AcceptedPolicyView)} directly —
 * no Agent production API is widened, no wire schema is hand-duplicated for the positive
 * path. This relies on the existing
 * {@code testImplementation project(':platform-tracing-otel-javaagent-extension')}
 * dependency declared in this module's build.gradle (same technique as
 * {@link CandidateDigestAgentParityTest}).
 * <p>
 * Flow under test for every positive scenario:
 * <pre>
 *   AcceptedPolicyEnvelope (Agent accepted view)
 *   -&gt; Agent PrivateControlOpenTypeCodec.encodeAcceptedComplete()   [REAL Agent encoder]
 *   -&gt; javax.management.openmbean.CompositeData                    [wire]
 *   -&gt; Spring PrivateControlOpenTypeCodec.decodeCompleteWithSequence() [REAL Spring decoder]
 *   -&gt; semantic field-by-field equality
 * </pre>
 * Negative scenarios construct malformed {@code CompositeData} directly (there is no "Agent
 * encoder" for a contract violation — the point is to prove the Spring decoder independently
 * rejects payloads it must not trust, since it sits on a cross-process, cross-classloader
 * boundary and cannot assume the sender was a well-behaved Agent).
 */
class AgentToSpringWireCodecParityTest {

    private static final PrivateControlOpenTypeCodec AGENT_CODEC = new PrivateControlOpenTypeCodec();
    private static final space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec
            SPRING_CODEC = new space.br1440.platform.tracing.autoconfigure.jmx.privatecontrol.PrivateControlOpenTypeCodec();

    // ── positive scenarios ──────────────────────────────────────────────────────

    @Test
    void defaultPolicyRoundTrips() {
        assertRoundTrip(envelope(0L, uuid(), EffectiveTracingPolicy.of(
                true, 1.0, Map.of(),
                true, false, true, true, PolicyLogLevel.INFO)));
    }

    @Test
    void nonEmptyRouteRatiosRoundTrip() {
        assertRoundTrip(envelope(42L, uuid(), EffectiveTracingPolicy.of(
                true, 0.25, Map.of("/checkout", 0.5, "/search", 0.3, "/health", 0.0),
                true, true, false, false, PolicyLogLevel.DEBUG)));
    }

    @Test
    void emptyRouteRatiosRoundTrip() {
        assertRoundTrip(envelope(7L, uuid(), EffectiveTracingPolicy.of(
                false, 0.0, Map.of(),
                false, false, false, false, PolicyLogLevel.OFF)));
    }

    @Test
    void allDiagnosticsLogLevelsRoundTrip() {
        for (PolicyLogLevel level : PolicyLogLevel.values()) {
            assertRoundTrip(envelope(1L, uuid(), EffectiveTracingPolicy.of(
                    true, 0.5, Map.of("/a", 0.1),
                    true, false, true, false, level)));
        }
    }

    @Test
    void minimumSequenceRoundTrips() {
        assertRoundTrip(envelope(0L, uuid(), EffectiveTracingPolicy.of(
                true, 1.0, Map.of(),
                true, false, true, true, PolicyLogLevel.WARN)));
    }

    @Test
    void nonZeroSequenceRoundTrips() {
        assertRoundTrip(envelope(9_223_372_036L, uuid(), EffectiveTracingPolicy.of(
                true, 1.0, Map.of(),
                true, false, true, true, PolicyLogLevel.WARN)));
    }

    @Test
    void runtimeInstanceIdIsPreservedExactly() {
        String runtimeId = uuid();
        AcceptedSnapshotWithSequence decoded = roundTrip(
                envelope(3L, runtimeId, EffectiveTracingPolicy.of(
                        true, 1.0, Map.of(), true, false, true, true, PolicyLogLevel.INFO)));
        assertThat(decoded.runtimeInstanceId()).isEqualTo(runtimeId);
    }

    @Test
    void allEightPolicyLeavesRoundTripIndependently() {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                false, 0.73, Map.of("/x", 0.9),
                false, true, false, true, PolicyLogLevel.TRACE);
        AcceptedSnapshotWithSequence decoded = roundTrip(envelope(5L, uuid(), policy));
        CompleteTracingPolicySnapshot snapshot = decoded.snapshot();

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
        // NavigableMap (TreeMap) natural ordering on the Agent side must produce the same
        // logical map regardless of insertion order — proven by comparing two envelopes
        // built with reversed insertion order.
        Map<String, Double> insertedAscending = new LinkedHashMap<>();
        insertedAscending.put("/a", 0.1);
        insertedAscending.put("/m", 0.5);
        insertedAscending.put("/z", 0.9);
        Map<String, Double> insertedDescending = new LinkedHashMap<>();
        insertedDescending.put("/z", 0.9);
        insertedDescending.put("/m", 0.5);
        insertedDescending.put("/a", 0.1);

        AcceptedSnapshotWithSequence first = roundTrip(envelope(1L, uuid(), EffectiveTracingPolicy.of(
                true, 0.5, insertedAscending, true, false, true, true, PolicyLogLevel.INFO)));
        AcceptedSnapshotWithSequence second = roundTrip(envelope(1L, uuid(), EffectiveTracingPolicy.of(
                true, 0.5, insertedDescending, true, false, true, true, PolicyLogLevel.INFO)));

        assertThat(first.snapshot().samplingRouteRatios())
                .isEqualTo(second.snapshot().samplingRouteRatios());
    }

    // ── negative scenarios: Spring decoder must independently reject malformed wire payloads ──

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
        // diagnosticsLogLevel omitted entirely
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
        // samplingRatio declared as STRING instead of DOUBLE
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
        values[1] = null; // runtimeInstanceId
        assertRejected(compositeData(type, keys, values));
    }

    @Test
    void rejectsNegativeAcceptedSequence() {
        assertRejected(validCompositeDataWith(v -> v[0] = -1L));
    }

    @Test
    void rejectsBlankRuntimeInstanceId() {
        assertRejected(validCompositeDataWith(v -> v[1] = "   "));
    }

    @Test
    void rejectsNaNSamplingRatio() {
        assertRejected(validCompositeDataWith(v -> v[3] = Double.NaN));
    }

    @Test
    void rejectsInfiniteSamplingRatio() {
        assertRejected(validCompositeDataWith(v -> v[3] = Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsSamplingRatioAboveOne() {
        assertRejected(validCompositeDataWith(v -> v[3] = 1.1));
    }

    @Test
    void rejectsSamplingRatioBelowZero() {
        assertRejected(validCompositeDataWith(v -> v[3] = -0.1));
    }

    @Test
    void rejectsRouteRatioOutsideBounds() {
        assertRejected(validCompositeDataWith(v -> v[4] = routeTable(Map.of("/bad", 1.5))));
    }

    @Test
    void rejectsRouteCountAboveMaximum() {
        Map<String, Double> tooMany = new TreeMap<>();
        for (int i = 0; i < 101; i++) tooMany.put("/route" + i, 0.1);
        assertRejected(validCompositeDataWith(v -> v[4] = routeTable(tooMany)));
    }

    @Test
    void rejectsRouteKeyAboveExactConfiguredLimit() {
        String tooLong = "a".repeat(256);
        assertRejected(validCompositeDataWith(v -> v[4] = routeTable(Map.of(tooLong, 0.5))));
    }

    @Test
    void acceptsRouteKeyAtExactConfiguredLimit() {
        String exact = "a".repeat(255);
        AcceptedSnapshotWithSequence decoded =
                SPRING_CODEC.decodeCompleteWithSequence(validCompositeDataWith(v -> v[4] = routeTable(Map.of(exact, 0.5))));
        assertThat(decoded.snapshot().samplingRouteRatios()).containsEntry(exact, 0.5);
    }

    @Test
    void rejectsInvalidDiagnosticsLogLevel() {
        assertRejected(validCompositeDataWith(v -> v[9] = "VERBOSE"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void assertRoundTrip(AcceptedPolicyEnvelope envelope) {
        AcceptedSnapshotWithSequence decoded = roundTrip(envelope);

        assertThat(decoded.acceptedSequence()).isEqualTo(envelope.acceptedSequence());
        assertThat(decoded.runtimeInstanceId()).isEqualTo(envelope.runtimeInstanceId());
        CompleteTracingPolicySnapshot snapshot = decoded.snapshot();
        assertThat(snapshot.samplingEnabled()).isEqualTo(envelope.samplingEnabled());
        assertThat(snapshot.samplingRatio()).isEqualTo(envelope.samplingRatio());
        assertThat(snapshot.samplingRouteRatios()).isEqualTo(envelope.samplingRouteRatios());
        assertThat(snapshot.validationEnabled()).isEqualTo(envelope.validationEnabled());
        assertThat(snapshot.validationStrict()).isEqualTo(envelope.validationStrict());
        assertThat(snapshot.exporterEnabled()).isEqualTo(envelope.exporterEnabled());
        assertThat(snapshot.traceControlHeadersEnabled()).isEqualTo(envelope.traceControlHeadersEnabled());
        assertThat(snapshot.diagnosticsLogLevel()).isEqualTo(envelope.diagnosticsLogLevel());
    }

    private static AcceptedSnapshotWithSequence roundTrip(AcceptedPolicyEnvelope envelope) {
        CompositeData wire = AGENT_CODEC.encodeAcceptedComplete(envelope); // REAL Agent encoder
        return SPRING_CODEC.decodeCompleteWithSequence(wire);             // REAL Spring decoder
    }

    private static AcceptedPolicyEnvelope envelope(long sequence, String runtimeInstanceId, EffectiveTracingPolicy policy) {
        return new AcceptedPolicyEnvelope(runtimeInstanceId, sequence, policy);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static void assertRejected(CompositeData malformed) {
        assertThatThrownBy(() -> SPRING_CODEC.decodeCompleteWithSequence(malformed))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CompositeData validCompositeDataWith(java.util.function.Consumer<Object[]> mutator) {
        Object[] values = validCompleteValues(routeTable(Map.of()));
        mutator.accept(values);
        return compositeData(composite("AcceptedCompleteSnapshot/v1", validCompleteKeys(), validCompleteOpenTypes()),
                validCompleteKeys(), values);
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

    private static String[] concat(String[] array, String extra) {
        String[] result = new String[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = extra;
        return result;
    }

    private static OpenType<?>[] concat(OpenType<?>[] array, OpenType<?> extra) {
        OpenType<?>[] result = new OpenType<?>[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = extra;
        return result;
    }

    private static Object[] concat(Object[] array, Object extra) {
        Object[] result = new Object[array.length + 1];
        System.arraycopy(array, 0, result, 0, array.length);
        result[array.length] = extra;
        return result;
    }
}
