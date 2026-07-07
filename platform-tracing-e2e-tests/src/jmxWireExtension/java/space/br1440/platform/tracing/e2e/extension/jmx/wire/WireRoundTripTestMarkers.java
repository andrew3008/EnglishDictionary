package space.br1440.platform.tracing.e2e.extension.jmx.wire;

/**
 * Test-only machine-readable markers for the JMX wire round-trip E2E harness.
 * <p>
 * Emitted to {@code System.err} so the parent E2E test can parse them from the child JVM output.
 * This replaces the former production {@code SPIKE_JMX_WIRE:} marker, which has been removed from
 * production code. Lives only under the test-only {@code jmxWireExtension} source set.
 */
public final class WireRoundTripTestMarkers {

    /** Prefix of every marker emitted by the test-only wire harness. */
    public static final String LINE_PREFIX = "WIRE_ROUND_TRIP_TEST:";

    private WireRoundTripTestMarkers() {
    }

    public static void emit(String payload) {
        System.err.println(LINE_PREFIX + payload);
    }

    public static void emitScenarioResult(String scenario, ScenarioResult result) {
        emit("scenario=" + scenario);
        emit("valid=" + result.valid());
        emit("violationCount=" + result.violationCount());
        if (result.firstViolationKey() != null && !result.firstViolationKey().isEmpty()) {
            emit("firstViolationKey=" + result.firstViolationKey());
        }
        if (result.errorClass() != null && !result.errorClass().isEmpty()) {
            emit("errorClass=" + result.errorClass());
        }
        emit("scenarioEnd=" + scenario);
    }

    /** Parsed scenario outcome for E2E markers. */
    public record ScenarioResult(
            boolean valid,
            int violationCount,
            String firstViolationKey,
            String errorClass
    ) {
    }
}
