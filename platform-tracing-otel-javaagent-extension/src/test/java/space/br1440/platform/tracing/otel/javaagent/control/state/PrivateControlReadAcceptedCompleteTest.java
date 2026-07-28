package space.br1440.platform.tracing.otel.javaagent.control.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PRIV-R07 readAcceptedComplete operation.
 *
 * <p>Covers: MBeanInfo contract, zero parameters, INFO impact, all 10 fields,
 * route-ratio TabularData, deterministic ordering, empty map, all log levels,
 * sequence semantics (no mutation from read), codec consistency.
 */
class PrivateControlReadAcceptedCompleteTest {

    // ── MBeanInfo contract ────────────────────────────────────────────────────

    @Test
    void mBeanInfo_containsReadAcceptedCompleteOperation() {
        PrivateControlWriter writer = writer("r1", 1.0d, Map.of());
        MBeanInfo info = writer.getMBeanInfo();

        assertThat(info.getOperations())
                .anyMatch(op -> op.getName().equals("readAcceptedComplete"));
    }

    @Test
    void mBeanInfo_readAcceptedComplete_hasZeroParameters() {
        MBeanOperationInfo op = findOp(writer("r1", 1.0d, Map.of()), "readAcceptedComplete");
        assertThat(op.getSignature()).isEmpty();
    }

    @Test
    void mBeanInfo_readAcceptedComplete_impactIsINFO() {
        MBeanOperationInfo op = findOp(writer("r1", 1.0d, Map.of()), "readAcceptedComplete");
        assertThat(op.getImpact()).isEqualTo(MBeanOperationInfo.INFO);
    }

    @Test
    void mBeanInfo_readAcceptedComplete_returnsCompositeData() {
        MBeanOperationInfo op = findOp(writer("r1", 1.0d, Map.of()), "readAcceptedComplete");
        assertThat(op.getReturnType()).isEqualTo(CompositeData.class.getName());
    }

    @Test
    void mBeanInfo_applySnapshot_remainsACTION() {
        MBeanOperationInfo op = findOp(writer("r1", 1.0d, Map.of()), "applySnapshot");
        assertThat(op.getImpact()).isEqualTo(MBeanOperationInfo.ACTION);
    }

    // ── All 10 fields ─────────────────────────────────────────────────────────

    @Test
    void encodes_allTenFields() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        PrivateControlWriter writer = writer(runtimeId, 0.75d, Map.of("/api", 0.5d));

        CompositeData cd = (CompositeData) writer.invoke("readAcceptedComplete", new Object[0], new String[0]);

        assertThat(cd.getCompositeType().keySet()).containsExactlyInAnyOrder(
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled", "samplingRatio",
                "samplingRouteRatios", "validationEnabled", "validationStrict",
                "exporterEnabled", "traceControlHeadersEnabled", "diagnosticsLogLevel");
    }

    @Test
    void encodes_correctTypeName() throws Exception {
        CompositeData cd = read(writer("r2", 1.0d, Map.of()));
        assertThat(cd.getCompositeType().getTypeName()).isEqualTo("AcceptedCompleteSnapshot/v1");
    }

    @Test
    void encodes_runtimeInstanceId() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(read(writer(id, 1.0d, Map.of())).get("runtimeInstanceId")).isEqualTo(id);
    }

    @Test
    void encodes_samplingRatio() throws Exception {
        assertThat(read(writer("r3", 0.42d, Map.of())).get("samplingRatio")).isEqualTo(0.42d);
    }

    @Test
    void encodes_acceptedSequenceIsZeroOnStartup() throws Exception {
        assertThat(read(writer("r4", 1.0d, Map.of())).get("acceptedSequence")).isEqualTo(0L);
    }

    // ── Route-ratio TabularData ───────────────────────────────────────────────

    @Test
    void encodes_emptyRouteMap_asEmptyTabularData() throws Exception {
        CompositeData cd = read(writer("r5", 1.0d, Map.of()));
        TabularData routes = (TabularData) cd.get("samplingRouteRatios");
        assertThat(routes.size()).isZero();
    }

    @Test
    void encodes_routeRatios_deterministicNaturalKeyOrder() throws Exception {
        Map<String, Double> input = new java.util.LinkedHashMap<>();
        input.put("/z", 0.9d);
        input.put("/a", 0.1d);
        input.put("/m", 0.5d);
        PrivateControlWriter writer = writerWithRoutes("r6", input);

        CompositeData cd = read(writer);
        TabularData routes = (TabularData) cd.get("samplingRouteRatios");
        assertThat(routes.size()).isEqualTo(3);
        // Natural order: /a, /m, /z
        java.util.List<String> prefixes = routes.values().stream()
                .map(row -> (String) ((CompositeData) row).get("routePrefix"))
                .toList();
        assertThat(prefixes).isSortedAccordingTo(java.util.Comparator.naturalOrder());
    }

    @Test
    void encodes_maximumRouteBounds() throws Exception {
        Map<String, Double> big = new TreeMap<>();
        for (int i = 0; i < EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES; i++) {
            big.put("/route" + i, 0.5d);
        }
        PrivateControlWriter writer = writerWithRoutes("r7", big);
        CompositeData cd = read(writer);
        TabularData routes = (TabularData) cd.get("samplingRouteRatios");
        assertThat(routes.size()).isEqualTo(EffectiveTracingPolicyValidator.MAX_ROUTE_ENTRIES);
    }

    // ── Log levels ────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "INFO", "WARN", "ERROR"})
    void encodes_allLogLevels(String level) throws Exception {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true, 1.0d, Map.of(), true, false, true, true,
                PolicyLogLevel.valueOf(level));
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore("r8", policy);
        PrivateControlWriter writer = new PrivateControlWriter(
                null, store, new PrivateControlOpenTypeCodec());
        CompositeData cd = read(writer);
        assertThat(cd.get("diagnosticsLogLevel")).isEqualTo(level);
    }

    // ── Sequence semantics ────────────────────────────────────────────────────

    @Test
    void readAcceptedComplete_doesNotChangeSequence() throws Exception {
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore("r9", defaultPolicy());
        PrivateControlWriter writer = new PrivateControlWriter(
                null, store, new PrivateControlOpenTypeCodec());

        long before = store.current().acceptedSequence();
        read(writer);
        read(writer);
        long after = store.current().acceptedSequence();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void consistentPolicyAndSequence_fromSingleEnvelopeLoad() throws Exception {
        String runtimeId = UUID.randomUUID().toString();
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(runtimeId, defaultPolicy());
        PrivateControlWriter writer = new PrivateControlWriter(
                null, store, new PrivateControlOpenTypeCodec());

        CompositeData cd = read(writer);
        // runtimeInstanceId and acceptedSequence must come from the same envelope
        assertThat(cd.get("runtimeInstanceId")).isEqualTo(runtimeId);
        assertThat(cd.get("acceptedSequence")).isEqualTo(0L);
    }

    // ── Codec: exact field order matches ACCEPTED_COMPLETE_KEYS ──────────────

    @Test
    void fieldOrder_matchesCanonicalKeyArray() throws Exception {
        CompositeData cd = read(writer("r10", 1.0d, Map.of()));
        assertThat(cd.getCompositeType().keySet().toArray())
                .containsExactly((Object[]) PrivateControlWireTypes.ACCEPTED_COMPLETE_KEYS);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CompositeData read(PrivateControlWriter writer) throws Exception {
        return (CompositeData) writer.invoke("readAcceptedComplete", new Object[0], new String[0]);
    }

    private static MBeanOperationInfo findOp(PrivateControlWriter writer, String name) {
        for (MBeanOperationInfo op : writer.getMBeanInfo().getOperations()) {
            if (op.getName().equals(name)) return op;
        }
        throw new AssertionError("operation not found: " + name);
    }

    private static PrivateControlWriter writer(String runtimeId, double ratio, Map<String, Double> routes) {
        EffectiveTracingPolicy policy = EffectiveTracingPolicy.of(
                true, ratio, routes, true, false, true, true, PolicyLogLevel.INFO);
        AcceptedPolicyStateStore store = new AcceptedPolicyStateStore(runtimeId, policy);
        return new PrivateControlWriter(null, store, new PrivateControlOpenTypeCodec());
    }

    private static PrivateControlWriter writerWithRoutes(
            String runtimeId, Map<String, Double> routes) {
        return writer(runtimeId, 1.0d, routes);
    }

    private static EffectiveTracingPolicy defaultPolicy() {
        return EffectiveTracingPolicy.of(
                true, 1.0d, Map.of(), true, false, true, true, PolicyLogLevel.INFO);
    }
}
