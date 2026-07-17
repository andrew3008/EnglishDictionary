package space.br1440.platform.tracing.otel.extension.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControlMBean;
import space.br1440.platform.tracing.otel.extension.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.extension.jmx.validation.PlatformValidationControlMBean;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JmxRuntimePolicyApplier}.
 *
 * <p>Uses hand-written stub implementations of
 * {@link PlatformSamplingControl} and {@link PlatformValidationControl}
 * rather than a mocking framework, keeping the test compile-time-safe and
 * independent of any mock library.
 *
 * <p>Each stub records the arguments of the most recent
 * {@code updateXxxPolicy()} call so assertions can inspect them directly.
 */
class JmxRuntimePolicyApplierTest {

    // =========================================================================
    // Stubs
    // =========================================================================

    /**
     * In-memory stub for {@link PlatformSamplingControl}.
     *
     * <p>Tracks live state so partial-update fall-back logic in
     * {@link JmxRuntimePolicyApplier} works correctly.
     */
    private static final class StubSamplingControl
            extends PlatformSamplingControl {

        // --- live state (initial values) ---
        private boolean samplerEnabled  = true;
        private double  defaultRatio    = 0.25d;
        private Map<String, Double> routeRatios = new LinkedHashMap<>();
        private String[] dropPaths      = new String[0];
        private String[] forceValues    = new String[0];

        // --- last recorded call ---
        record SamplingPolicyCall(
                boolean enabled,
                double defaultRatio,
                String[] droppedRoutes,
                String[] forceRecordValues,
                String[] routeRatioPrefixes,
                double[] routeRatioValues,
                String source) {}

        final List<SamplingPolicyCall> calls = new ArrayList<>();

        StubSamplingControl() {
            super(null, null, new LongAdder());
        }

        // --- MBean getters used by applier for partial-update fall-back ---
        @Override public boolean isSamplerEnabled()            { return samplerEnabled; }
        @Override public double  getSamplingRatio()            { return defaultRatio; }
        @Override public Map<String, Double> getRouteRatios()  { return routeRatios; }
        @Override public String[] getDropPathPrefixes()        { return dropPaths; }
        @Override public String[] getForceRecordValues()       { return forceValues; }

        // --- The method the applier actually calls ---
        @Override
        public void updateSamplingPolicy(
                boolean enabled,
                double dr,
                String[] dropped,
                String[] force,
                String[] prefixes,
                double[] ratioValues,
                String src) {
            samplerEnabled = enabled;
            defaultRatio   = dr;
            calls.add(new SamplingPolicyCall(
                    enabled, dr, dropped, force, prefixes, ratioValues, src));
        }

        SamplingPolicyCall lastCall() {
            return calls.isEmpty() ? null : calls.getLast();
        }
    }

    /**
     * In-memory stub for {@link PlatformValidationControl}.
     */
    private static final class StubValidationControl
            extends PlatformValidationControl {

        private boolean enabled = false;
        private boolean strict  = false;

        record ValidationPolicyCall(boolean enabled, boolean strict, String source) {}

        final List<ValidationPolicyCall> calls = new ArrayList<>();

        StubValidationControl() {
            super(null, new LongAdder());
        }

        @Override public boolean isValidationEnabled() { return enabled; }
        @Override public boolean isValidationStrict()  { return strict; }

        @Override
        public void updateValidationPolicy(boolean en, boolean st, String src) {
            enabled = en;
            strict  = st;
            calls.add(new ValidationPolicyCall(en, st, src));
        }

        ValidationPolicyCall lastCall() {
            return calls.isEmpty() ? null : calls.getLast();
        }
    }

    // =========================================================================
    // Test setup
    // =========================================================================

    private StubSamplingControl  sampling;
    private StubValidationControl validation;
    private JmxRuntimePolicyApplier applier;

    @BeforeEach
    void setUp() {
        sampling   = new StubSamplingControl();
        validation = new StubValidationControl();
        applier    = new JmxRuntimePolicyApplier(sampling, validation);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Map<String, Object> basePayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        map.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue());
        return map;
    }

    private void apply(Map<String, Object> payload) {
        applier.apply(
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY,
                payload,
                "test");
    }

    // =========================================================================
    // No-op: empty payload touches neither sampling nor validation
    // =========================================================================

    @Test
    void emptyPayloadDoesNotCallEitherMBean() {
        apply(new LinkedHashMap<>());

        assertThat(sampling.calls).isEmpty();
        assertThat(validation.calls).isEmpty();
    }

    // =========================================================================
    // Sampling
    // =========================================================================

    @Nested
    class SamplingApply {

        @Test
        void appliesSamplingRatioAndPreservesOtherLiveFields() {
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.75d);

            apply(payload);

            assertThat(sampling.calls).hasSize(1);
            StubSamplingControl.SamplingPolicyCall call = sampling.lastCall();
            assertThat(call.defaultRatio()).isEqualTo(0.75d);
            // enabled not in payload → live value preserved (true)
            assertThat(call.enabled()).isTrue();
        }

        @Test
        void killSwitchTrueDisablesSampler() {
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, true);

            apply(payload);

            assertThat(sampling.lastCall().enabled()).isFalse();
        }

        @Test
        void killSwitchFalseEnablesSampler() {
            // Start with sampler disabled
            sampling.samplerEnabled = false;

            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, false);

            apply(payload);

            assertThat(sampling.lastCall().enabled()).isTrue();
        }

        @Test
        void absentKillSwitchPreservesLiveEnabledState() {
            sampling.samplerEnabled = false;

            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
            // kill-switch key is absent → keep live disabled state

            apply(payload);

            assertThat(sampling.lastCall().enabled()).isFalse();
        }

        @Test
        void routeRatiosAppliedCorrectly() {
            Map<String, Double> ratios = new LinkedHashMap<>();
            ratios.put("/api", 0.3d);
            ratios.put("/health", 0.0d);

            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, ratios);

            apply(payload);

            StubSamplingControl.SamplingPolicyCall call = sampling.lastCall();
            assertThat(call.routeRatioPrefixes()).containsExactlyInAnyOrder("/api", "/health");

            Map<String, Double> rebuiltRatios = new LinkedHashMap<>();
            for (int i = 0; i < call.routeRatioPrefixes().length; i++) {
                rebuiltRatios.put(call.routeRatioPrefixes()[i], call.routeRatioValues()[i]);
            }
            assertThat(rebuiltRatios).containsEntry("/api", 0.3d)
                    .containsEntry("/health", 0.0d);
        }

        @Test
        void absentRouteRatiosFallBackToLiveState() {
            Map<String, Double> live = new LinkedHashMap<>();
            live.put("/live", 0.9d);
            sampling.routeRatios = live;

            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.2d);
            // route_ratios absent → live state preserved

            apply(payload);

            StubSamplingControl.SamplingPolicyCall call = sampling.lastCall();
            assertThat(call.routeRatioPrefixes()).contains("/live");
        }

        @Test
        void sourcePassedThroughToMBean() {
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
            payload.put(TracingControlProtocolKeys.SOURCE, "CONTROL_PLANE_V2");

            applier.apply(
                    TracingControlProtocolOperation.APPLY_RUNTIME_POLICY,
                    payload,
                    "CONTROL_PLANE_V2");

            assertThat(sampling.lastCall().source()).isEqualTo("CONTROL_PLANE_V2");
        }
    }

    // =========================================================================
    // Validation
    // =========================================================================

    @Nested
    class ValidationApply {

        @Test
        void appliesValidationEnabledFlagAlone() {
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);

            apply(payload);

            assertThat(validation.calls).hasSize(1);
            assertThat(validation.lastCall().enabled()).isTrue();
            // strict absent → falls back to live false
            assertThat(validation.lastCall().strict()).isFalse();
        }

        @Test
        void validationModeStrictSetsStrictTrue() {
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "STRICT");

            apply(payload);

            assertThat(validation.lastCall().strict()).isTrue();
        }

        @Test
        void validationModeLogOnlySetsStrictFalse() {
            // Prime live state as strict=true so we verify it gets overridden.
            validation.strict = true;

            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");

            apply(payload);

            assertThat(validation.lastCall().strict()).isFalse();
        }

        @Test
        void validationModeTakesPriorityOverStrictBooleanFlag() {
            // VALIDATION_MODE=LOG_ONLY wins even if VALIDATION_STRICT=true is also present.
            // (domain validator would have rejected this combination, but applier must be
            //  consistent: MODE is the authoritative source of 'strict' when present.)
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE,   "LOG_ONLY");
            payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

            apply(payload);

            // MODE wins: LOG_ONLY → strict=false
            assertThat(validation.lastCall().strict()).isFalse();
        }

        @Test
        void validationStrictFlagUsedWhenModeAbsent() {
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

            apply(payload);

            assertThat(validation.lastCall().strict()).isTrue();
        }

        @Test
        void absentValidationFieldsDoNotCallMBean() {
            // Only sampling field present
            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.1d);

            apply(payload);

            assertThat(validation.calls).isEmpty();
        }

        @Test
        void enabledFallsBackToLiveStateWhenAbsent() {
            validation.enabled = true; // live=true

            Map<String, Object> payload = basePayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, false);
            // VALIDATION_ENABLED absent → preserve live true

            apply(payload);

            assertThat(validation.lastCall().enabled()).isTrue();
        }
    }

    // =========================================================================
    // Unknown operation is a no-op (defensive guard)
    // =========================================================================

    @Test
    void unknownOperationProducesNoSideEffects() {
        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);

        // Passing READ_APPLIED_STATE: the handler normally short-circuits this
        // before reaching the applier, but we test the applier's own guard.
        applier.apply(
                TracingControlProtocolOperation.READ_APPLIED_STATE,
                payload,
                "test");

        assertThat(sampling.calls).isEmpty();
        assertThat(validation.calls).isEmpty();
    }
}
