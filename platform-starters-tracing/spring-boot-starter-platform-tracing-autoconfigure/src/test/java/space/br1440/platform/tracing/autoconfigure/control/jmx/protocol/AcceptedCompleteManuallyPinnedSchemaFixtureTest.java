package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.Test;

/**
 * Независимый тестовый стенд с вручную закреплённой JMX-схемой.
 */
class AcceptedCompleteManuallyPinnedSchemaFixtureTest {

    @Test
    void fullRoundTripAllSevenLeavesNonEmptyRoutes() throws OpenDataException {
        String rid = agentInstanceId();
        long seq = 77L;
        Map<String, Double> routes = Map.of(
                "/api/v1", 1.0d,
                "/api/v2", 0.5d,
                "/health", 0.0d);

        CompositeData wire = buildPinnedPayload(seq, rid, true, 0.75d, routes, "ERROR", false, true, "WARN");

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(seq);
        assertThat(decoded.agentInstanceId()).isEqualTo(rid);

        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isTrue();
        assertThat(snap.samplingRatio()).isEqualTo(0.75d);
        assertThat(snap.samplingRouteRatios()).hasSize(3);
        assertThat(snap.samplingRouteRatios()).containsEntry("/api/v1", 1.0d);
        assertThat(snap.samplingRouteRatios()).containsEntry("/api/v2", 0.5d);
        assertThat(snap.samplingRouteRatios()).containsEntry("/health", 0.0d);
        assertThat(snap.validationLevel()).isEqualTo("ERROR");
        assertThat(snap.exporterEnabled()).isFalse();
        assertThat(snap.traceControlHeadersEnabled()).isTrue();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("WARN");
    }

    @Test
    void fullRoundTripEmptyRouteTable() throws OpenDataException {
        String rid = agentInstanceId();
        CompositeData wire = buildPinnedPayload(0L, rid, false, 0.0d, Map.of(),
                "DISABLED", true, false, "ERROR"
        );

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

        assertThat(decoded.acceptedSequence()).isEqualTo(0L);
        assertThat(decoded.agentInstanceId()).isEqualTo(rid);
        var snap = decoded.snapshot();
        assertThat(snap.samplingEnabled()).isFalse();
        assertThat(snap.samplingRatio()).isEqualTo(0.0d);
        assertThat(snap.samplingRouteRatios()).isEmpty();
        assertThat(snap.validationLevel()).isEqualTo("DISABLED");
        assertThat(snap.exporterEnabled()).isTrue();
        assertThat(snap.traceControlHeadersEnabled()).isFalse();
        assertThat(snap.diagnosticsLogLevel()).isEqualTo("ERROR");
    }

    @Test
    void sequenceIsPreservedExactly() throws OpenDataException {
        long[] seqs = {0L, 1L, 100L, Long.MAX_VALUE - 1};
        for (long seq : seqs) {
            CompositeData wire = buildPinnedPayload(seq, agentInstanceId(),
                    true, 0.5d, Map.of(),
                    "WARNING", true, true, "INFO");
            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.acceptedSequence())
                    .as("sequence %d must be preserved", seq)
                    .isEqualTo(seq);
        }
    }

    @Test
    void canonicalAgentInstanceIdPreservedVerbatim() throws OpenDataException {
        String[] ids = {
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString()
        };
        for (String rid : ids) {
            CompositeData wire = buildPinnedPayload(1L, rid,
                    true, 0.5d, Map.of(),
                    "WARNING", true, true, "INFO");
            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.agentInstanceId())
                    .as("agentInstanceId must be preserved verbatim for: %s", rid)
                    .isEqualTo(rid);
        }
    }

    @Test
    void samplingRatioBoundaryValuesAreValid() throws OpenDataException {
        for (double ratio : new double[]{0.0d, 1.0d}) {
            CompositeData wire = buildPinnedPayload(
                    1L, agentInstanceId(),
                    true, ratio, Map.of(),
                    "WARNING", true, true, "INFO"
            );

            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.snapshot().samplingRatio())
                    .as("ratio %s is a valid boundary", ratio)
                    .isEqualTo(ratio);
        }
    }

    @Test
    void allValidDiagnosticsLogLevelsAccepted() throws OpenDataException {
        for (String level : new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
            CompositeData wire = buildPinnedPayload(
                    1L, agentInstanceId(),
                    true, 0.5d, Map.of(),
                    "WARNING", true, true, level
            );

            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.snapshot().diagnosticsLogLevel())
                    .as("log level %s must round-trip", level)
                    .isEqualTo(level);
        }
    }

    @Test
    void routeKeyValueNotTransposed() throws OpenDataException {
        Map<String, Double> routes = Map.of("/distinct-prefix", 0.123d);
        CompositeData wire = buildPinnedPayload(
                1L, agentInstanceId(),
                true, 0.5d, routes,
                "WARNING", true, true, "INFO"
        );

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

        assertThat(decoded.snapshot().samplingRouteRatios())
                .containsKey("/distinct-prefix")
                .containsEntry("/distinct-prefix", 0.123d);
        assertThat(decoded.snapshot().samplingRouteRatios())
                .doesNotContainKey("0.123");
    }

    @Test
    void validationLevelsAreIndependent() throws OpenDataException {
        for (String level : new String[]{"WARNING", "ERROR", "DISABLED"}) {
            CompositeData wire = buildPinnedPayload(
                    1L, agentInstanceId(),
                    false, 0.5d, Map.of(),
                    level, false, false, "INFO"
            );

            var snap = AcceptedPolicySnapshotDecoder.decode(wire).snapshot();
            assertThat(snap.validationLevel()).isEqualTo(level);
            assertThat(snap.samplingEnabled()).isFalse();
            assertThat(snap.exporterEnabled()).isFalse();
            assertThat(snap.traceControlHeadersEnabled()).isFalse();
        }
    }

    @Test
    void decodedSchemaFieldCountIsExactlySeven() throws OpenDataException {
        CompositeData wire = buildPinnedPayload(
                1L, agentInstanceId(),
                true, 0.5d, Map.of(),
                "WARNING", true, true, "INFO"
        );

        var snap = AcceptedPolicySnapshotDecoder.decode(wire).snapshot();

        assertThat(java.util.List.of(
                snap.samplingEnabled(),
                snap.samplingRatio(),
                snap.samplingRouteRatios(),
                snap.validationLevel(),
                snap.exporterEnabled(),
                snap.traceControlHeadersEnabled(),
                snap.diagnosticsLogLevel()
        )).hasSize(7);
    }

    private static String agentInstanceId() {
        return UUID.randomUUID().toString();
    }

    private static CompositeData buildPinnedPayload(long acceptedSequence,
                                                    String agentInstanceId,
                                                    boolean samplingEnabled,
                                                    double samplingRatio,
                                                    Map<String, Double> routes,
                                                    String validationLevel,
                                                    boolean exporterEnabled,
                                                    boolean traceControlHeadersEnabled,
                                                    String diagnosticsLogLevel) throws OpenDataException {
        CompositeType routeRow = new CompositeType(
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                "space.br1440.platform.tracing.control.RouteRatio.v1",
                new String[]{"ratio", "routePrefix"},
                new String[]{"ratio", "routePrefix"},
                new OpenType<?>[]{SimpleType.DOUBLE, SimpleType.STRING}
        );

        TabularType routesType = new TabularType(
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                "space.br1440.platform.tracing.control.RouteRatios.v1",
                routeRow,
                new String[]{"routePrefix"}
        );

        TabularDataSupport routeTable = new TabularDataSupport(routesType);
        for (var e : routes.entrySet()) {
            routeTable.put(new CompositeDataSupport(routeRow,
                    new String[]{"ratio", "routePrefix"},
                    new Object[]{e.getValue(), e.getKey()}));
        }

        String[] keys = {
                "acceptedSequence", "agentInstanceId", "acceptedPolicyDigest",
                "lastApplyOutcome", "lastAttemptId", "failureCode", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationLevel",
                "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };

        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.STRING,
                SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, routesType, SimpleType.STRING,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };

        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types
        );

        return new CompositeDataSupport(ct, keys, new Object[]{
                acceptedSequence, agentInstanceId, "accepted-policy-digest",
                "APPLIED", "attempt-id", "", samplingEnabled,
                samplingRatio, routeTable, validationLevel,
                exporterEnabled, traceControlHeadersEnabled,
                diagnosticsLogLevel
        });
    }
}
