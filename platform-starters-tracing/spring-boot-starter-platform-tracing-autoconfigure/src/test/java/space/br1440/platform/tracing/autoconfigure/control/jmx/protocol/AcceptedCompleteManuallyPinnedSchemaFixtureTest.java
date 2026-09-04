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

    // ── §1 полный цикл с непустой таблицей маршрутов ──────────────────────────

    @Test
    void fullRoundTripAllEightLeavesNonEmptyRoutes() throws OpenDataException {
        String rid = "agent-instance-" + UUID.randomUUID();
        long seq = 77L;
        Map<String, Double> routes = Map.of(
                "/api/v1", 1.0d,
                "/api/v2", 0.5d,
                "/health", 0.0d);

        CompositeData wire = buildPinnedPayload(seq, rid, true, 0.75d, routes,
                true, true, false, true, "WARN");

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

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

    // ── §2 полный цикл с пустой таблицей маршрутов ────────────────────────────

    @Test
    void fullRoundTripEmptyRouteTable() throws OpenDataException {
        String rid = "rid-empty-" + UUID.randomUUID();
        CompositeData wire = buildPinnedPayload(0L, rid, false, 0.0d, Map.of(),
                false, false, true, false, "ERROR");

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

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

    // ── §3 монотонность последовательности сохраняется ────────────────────────

    @Test
    void sequenceIsPreservedExactly() throws OpenDataException {
        long[] seqs = {0L, 1L, 100L, Long.MAX_VALUE - 1};
        for (long seq : seqs) {
            CompositeData wire = buildPinnedPayload(seq, "rid-seq-" + seq,
                    true, 0.5d, Map.of(),
                    true, false, true, true, "INFO");
            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.acceptedSequence())
                    .as("sequence %d must be preserved", seq)
                    .isEqualTo(seq);
        }
    }

    // ── §4 runtimeInstanceId непрозрачен и сохраняется без изменений ──────────

    @Test
    void runtimeInstanceIdPreservedVerbatim() throws OpenDataException {
        String[] ids = {
                "agent-instance-" + UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "UPPERCASE-RID-SHOULD-PASS",
                "rid-with-dashes-123"
        };
        for (String rid : ids) {
            CompositeData wire = buildPinnedPayload(1L, rid,
                    true, 0.5d, Map.of(),
                    true, false, true, true, "INFO");
            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.runtimeInstanceId())
                    .as("runtimeInstanceId must be preserved verbatim for: %s", rid)
                    .isEqualTo(rid);
        }
    }

    // ── §5 граничные значения коэффициента выборки 0.0 и 1.0 допустимы ────────

    @Test
    void samplingRatioBoundaryValuesAreValid() throws OpenDataException {
        for (double ratio : new double[]{0.0d, 1.0d}) {
            CompositeData wire = buildPinnedPayload(1L, "rid-boundary",
                    true, ratio, Map.of(),
                    true, false, true, true, "INFO");
            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.snapshot().samplingRatio())
                    .as("ratio %s is a valid boundary", ratio)
                    .isEqualTo(ratio);
        }
    }

    // ── §6 все допустимые значения перечисления diagnosticsLogLevel ───────────

    @Test
    void allValidDiagnosticsLogLevelsAccepted() throws OpenDataException {
        for (String level : new String[]{"OFF", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"}) {
            CompositeData wire = buildPinnedPayload(1L, "rid-level-" + level,
                    true, 0.5d, Map.of(),
                    true, false, true, true, level);
            var decoded = AcceptedPolicySnapshotDecoder.decode(wire);
            assertThat(decoded.snapshot().diagnosticsLogLevel())
                    .as("log level %s must round-trip", level)
                    .isEqualTo(level);
        }
    }

    // ── §7 ключ и значение маршрута не переставлены местами ───────────────────

    @Test
    void routeKeyValueNotTransposed() throws OpenDataException {
        Map<String, Double> routes = Map.of("/distinct-prefix", 0.123d);
        CompositeData wire = buildPinnedPayload(1L, "rid-swap",
                true, 0.5d, routes,
                true, false, true, true, "INFO");

        var decoded = AcceptedPolicySnapshotDecoder.decode(wire);

        assertThat(decoded.snapshot().samplingRouteRatios())
                .containsKey("/distinct-prefix")
                .containsEntry("/distinct-prefix", 0.123d);
        assertThat(decoded.snapshot().samplingRouteRatios())
                .doesNotContainKey("0.123");
    }

    // ── §8 все булевы листья проверены независимо ─────────────────────────────

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
            CompositeData wire = buildPinnedPayload(1L, "rid-bool",
                    c.sampEn(), 0.5d, Map.of(),
                    c.valEn(), c.valStrict(), c.expEn(), c.tcHeaders(), "INFO");
            var snap = AcceptedPolicySnapshotDecoder.decode(wire).snapshot();
            assertThat(snap.samplingEnabled()).isEqualTo(c.sampEn());
            assertThat(snap.validationEnabled()).isEqualTo(c.valEn());
            assertThat(snap.validationStrict()).isEqualTo(c.valStrict());
            assertThat(snap.exporterEnabled()).isEqualTo(c.expEn());
            assertThat(snap.traceControlHeadersEnabled()).isEqualTo(c.tcHeaders());
        }
    }

    // ── §9 инвариант производственной DTO-схемы: ровно восемь листьев ─────────

    @Test
    void decodedSchemaFieldCountIsExactlyEight() throws OpenDataException {
        CompositeData wire = buildPinnedPayload(1L, "rid-schema",
                true, 0.5d, Map.of(),
                true, false, true, true, "INFO");
        var snap = AcceptedPolicySnapshotDecoder.decode(wire).snapshot();

        // Все восемь листьев перечислены явно, чтобы новые поля нельзя было
        // незаметно добавить без пересмотра контракта.
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

    // ── вспомогательная сборка CompositeData без сгенерированных констант полей ──

    /**
     * Собирает {@code CompositeData} для {@code "AcceptedCompleteSnapshot/v1"} по независимо
     * закреплённым литералам, чтобы обнаруживать синхронное расхождение сгенерированного кода
     * и потребителя.
     */
    private static CompositeData buildPinnedPayload(
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
                "acceptedSequence", "runtimeInstanceId", "acceptedPolicyDigest",
                "lastApplyOutcome", "lastAttemptId", "failureCode", "samplingEnabled",
                "samplingRatio", "samplingRouteRatios", "validationEnabled",
                "validationStrict", "exporterEnabled", "traceControlHeadersEnabled",
                "diagnosticsLogLevel"
        };
        OpenType<?>[] types = {
                SimpleType.LONG, SimpleType.STRING, SimpleType.STRING,
                SimpleType.STRING, SimpleType.STRING, SimpleType.STRING, SimpleType.BOOLEAN,
                SimpleType.DOUBLE, routesType, SimpleType.BOOLEAN,
                SimpleType.BOOLEAN, SimpleType.BOOLEAN, SimpleType.BOOLEAN,
                SimpleType.STRING
        };
        CompositeType ct = new CompositeType(
                "AcceptedCompleteSnapshot/v1", "AcceptedCompleteSnapshot/v1",
                keys, keys, types);
        return new CompositeDataSupport(ct, keys, new Object[]{
                acceptedSequence, runtimeInstanceId, "accepted-policy-digest",
                "APPLIED", "attempt-id", "", samplingEnabled,
                samplingRatio, routeTable, validationEnabled,
                validationStrict, exporterEnabled, traceControlHeadersEnabled,
                diagnosticsLogLevel
        });
    }
}
