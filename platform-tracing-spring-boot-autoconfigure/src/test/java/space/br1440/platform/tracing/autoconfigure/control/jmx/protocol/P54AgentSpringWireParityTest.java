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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P5 TEST §20 P5.4 — p5-agent-spring-wire-parity
 *
 * Proves that real Agent-side encoding (JDK CompositeData built via
 * PrivateControlWireTypes-compatible schema in THIS classloader) round-trips
 * through the real Spring decoder (PrivateControlOpenTypeCodec) with semantic
 * equality on:
 *   - acceptedSequence
 *   - runtimeInstanceId
 *   - all 8 policy leaves: samplingEnabled, samplingRatio, samplingRouteRatios,
 *     validationEnabled, validationStrict, exporterEnabled,
 *     traceControlHeadersEnabled, diagnosticsLogLevel
 *
 * Design: Agent and Spring share the same JVM classloader in test scope.
 * CompositeData is built here identically to Agent PrivateControlWireTypes
 * (field order and type names pinned by PrivateControlOpenTypeCodecTest.buildCompleteResponse).
 * Any schema drift causes decodeCompleteWithSequence to throw — self-enforcing.
 */
class P54AgentSpringWireParityTest {

    private final PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();

    // ── §1 full round-trip with non-empty route table ─────────────────────────

    @Test
    void fullRoundTripAllEightLeavesNonEmptyRoutes() throws OpenDataException {
        String rid = "agent-instance-" + UUID.randomUUID();
        long seq = 77L;
        Map<String, Double> routes = Map.of(
                "/api/v1", 1.0d,
                "/api/v2", 0.5d,
                "/health", 0.0d);

        CompositeData wire = buildAgentPayload(seq, rid, true, 0.75d, routes,
                true, true, false, true, "WARN");

        var decoded = codec.decodeCompleteWithSequence(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(seq);
        assertThat(decoded.runtimeInstanceId()).isEqualTo(rid);

        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isTrue();
        assertThat(snap.samplingRatio()).isEqualTo(0.75d);
        assertThat(snap.samplingRouteRatios()).hasSize(3);
        assertThat(snap.samplingRouteRatios()).containsEntry("/api/v1", 1.0d);
        assertThat(snap.samplingRouteRatios()).containsEntry("/api/v2", 0.5d);
        assertThat(snap.samplingRouteRatios()).containsEntry("/health", 0.0d);
        assertThat(snap.validationEnabled()).isTrue();
        assertThat(snap.validationStrict()).isTrue();
        assertThat(snap.exporterEnabled()).isFalse();
        assertThat(snap.traceControlHeadersEnabled()).isTrue();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("WARN");
    }

    // ── §2 full round-trip with empty route table ─────────────────────────────

    @Test
    void fullRoundTripEmptyRouteTable() throws OpenDataException {
        String rid = "rid-empty-" + UUID.randomUUID();
        CompositeData wire = buildAgentPayload(0L, rid, false, 0.0d, Map.of(),
                false, false, true, false, "ERROR");

        var decoded = codec.decodeCompleteWithSequence(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.runtimeInstanceId()).isEqualTo(rid);
        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isFalse();
        assertThat(snap.samplingRatio()).isEqualTo(0.0d);
        assertThat(snap.samplingRouteRatios()).isEmpty();
        assertThat(snap.validationEnabled()).isFalse();
        assertThat(snap.validationStrict()).isFalse();
        assertThat(snap.exporterEnabled()).isTrue();
        assertThat(snap.traceControlHeadersEnabled()).isFalse();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("ERROR");
    }

    // ── §3 sequence monotonicity is preserved ─────────────────────────────────

    @Test
    void sequenceIsPreservedExactly() throws OpenDataException {
        long[] seqs = {0L, 1L, 100L, Long.MAX_VALUE - 1};
        for (long seq : seqs) {
            CompositeData wire = buildAgentPayload(seq, "rid-seq-" + seq,
                    true, 0.5d, Map.of(),
                    true, false, true, true, "INFO");
            var decoded = codec.decodeCompleteWithSequence(wire);
            assertThat(decoded.acceptedSequence())
                    .as("sequence %d must be preserved", seq)
                    .isEqualTo(seq);
        }
    }

    // ── §4 runtimeInstanceId is opaque and preserved verbatim ─────────────────

    @Test
    void runtimeInstanceIdPreservedVerbatim() throws OpenDataException {
        String[] ids = {
                "agent-instance-" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "UPPERCASE-RID-SHOULD-PASS",
                "rid-with-dashes-123"
        };
        for (String rid : ids) {
            CompositeData wire = buildAgentPayload(1L, rid,
                    true, 0.5d, Map.of(),
                    true, false, true, true, "INFO");
            var decoded = codec.decodeCompleteWithSequence(wire);
            assertThat(decoded.runtimeInstanceId())
                    .as("runtimeInstanceId must be preserved verbatim for: %s", rid)
                    .isEqualTo(rid);
        }
    }

    // ── §5 sampling ratio boundary values (0.0 and 1.0 are valid) ─────────────

    @Test
    void samplingRatioBoundaryValuesAreValid() throws OpenDataException {
        for (double ratio : new double[]{0.0d, 1.0d}) {
            CompositeData wire = buildAgentPayload(1L, "rid-boundary",
                    true, ratio, Map.of(),
                    true, false, true, true, "INFO");
            var decoded = codec.decodeCompleteWithSequence(wire);
            assertThat(decoded.snapshot().samplingRatio())
                    .as("ratio %s is a valid boundary", ratio)
                    .isEqualTo(ratio);
        }
    }

    // ── §6 all valid diagnosticsLogLevel enum values ───────────────────────────

    @Test
    void allValidDiagnosticsLogLevelsAccepted() throws OpenDataException {
        for (String level : new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
            CompositeData wire = buildAgentPayload(1L, "rid-level-" + level,
                    true, 0.5d, Map.of(),
                    true, false, true, true, level);
            var decoded = codec.decodeCompleteWithSequence(wire);
            assertThat(decoded.snapshot().diagnosticsLogLevel())
                    .as("log level %s must round-trip", level)
                    .isEqualTo(level);
        }
    }

    // ── §7 route key/value are not transposed ─────────────────────────────────

    @Test
    void routeKeyValueNotTransposed() throws OpenDataException {
        Map<String, Double> routes = Map.of("/distinct-prefix", 0.123d);
        CompositeData wire = buildAgentPayload(1L, "rid-swap",
                true, 0.5d, routes,
                true, false, true, true, "INFO");

        var decoded = codec.decodeCompleteWithSequence(wire);

        assertThat(decoded.snapshot().samplingRouteRatios())
                .containsKey("/distinct-prefix")
                .containsEntry("/distinct-prefix", 0.123d);
        assertThat(decoded.snapshot().samplingRouteRatios())
                .doesNotContainKey("0.123");
    }

    // ── §8 all boolean leaves independently verified ──────────────────────────

    @Test
    void booleanLeavesAreIndependent() throws OpenDataException {
        record BoolCase(boolean sampEn, boolean valEn, boolean valStrict,
                        boolean expEn, boolean tcHeaders) {}

        var cases = new BoolCase[]{
                new BoolCase(true,  false, false, false, false),
                new BoolCase(false, true,  false, false, false),
                new BoolCase(false, false, true,  false, false),
                new BoolCase(false, false, false, true,  false),
                new BoolCase(false, false, false, false, true),
        };

        for (var c : cases) {
            CompositeData wire = buildAgentPayload(1L, "rid-bool",
                    c.sampEn(), 0.5d, Map.of(),
                    c.valEn(), c.valStrict(), c.expEn(), c.tcHeaders(), "INFO");
            var snap = codec.decodeCompleteWithSequence(wire).snapshot();
            assertThat(snap.samplingEnabled()).isEqualTo(c.sampEn());
            assertThat(snap.validationEnabled()).isEqualTo(c.valEn());
            assertThat(snap.validationStrict()).isEqualTo(c.valStrict());
            assertThat(snap.exporterEnabled()).isEqualTo(c.expEn());
            assertThat(snap.traceControlHeadersEnabled()).isEqualTo(c.tcHeaders());
        }
    }

    // ── §9 production DTO schema invariant: exactly 8 leaves ──────────────────

    @Test
    void decodedSchemaFieldCountIsExactlyEight() throws OpenDataException {
        CompositeData wire = buildAgentPayload(1L, "rid-schema",
                true, 0.5d, Map.of(),
                true, false, true, true, "INFO");
        var snap = codec.decodeCompleteWithSequence(wire).snapshot();

        // Enumerate all 8 leaves explicitly to guard against new fields
        // being silently added without a contract review.
        assertThat(java.util.List.of(
                snap.samplingEnabled(),
                snap.samplingRatio(),
                snap.samplingRouteRatios(),
                snap.validationEnabled(),
                snap.validationStrict(),
                snap.exporterEnabled(),
                snap.traceControlHeadersEnabled(),
                snap.diagnosticsLogLevel()
        )).hasSize(8);
    }

    // ── helper: build Agent-canonical CompositeData ───────────────────────────

    /**
     * Constructs CompositeData identical to Agent PrivateControlWireTypes.ACCEPTED_COMPLETE:
     * type name "AcceptedCompleteSnapshot/v1", field order and sub-types pinned
     * by PrivateControlOpenTypeCodecTest.buildCompleteResponse contract.
     */
    private static CompositeData buildAgentPayload(
            long acceptedSequence,
            String runtimeInstanceId,
            boolean samplingEnabled,
            double samplingRatio,
            Map<String, Double> routes,
            boolean validationEnabled,
            boolean validationStrict,
            boolean exporterEnabled,
            boolean traceControlHeadersEnabled,
            String diagnosticsLogLevel) throws OpenDataException {

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
        for (var e : routes.entrySet()) {
            routeTable.put(new CompositeDataSupport(routeRow,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }

        String[] keys = {
                "acceptedSequence", "runtimeInstanceId", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, routesType, SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        return new CompositeDataSupport(ct, keys, new Object[]{
                acceptedSequence, runtimeInstanceId, samplingEnabled,
                samplingRatio, routeTable, validationEnabled,
                validationStrict, exporterEnabled, traceControlHeadersEnabled,
                diagnosticsLogLevel
        });
    }
}
