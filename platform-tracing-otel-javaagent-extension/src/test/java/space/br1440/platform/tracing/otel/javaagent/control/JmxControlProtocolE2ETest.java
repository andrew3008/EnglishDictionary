package space.br1440.platform.tracing.otel.javaagent.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocol;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.otel.control.protocol.RuntimePolicyControlHandleResult;
import space.br1440.platform.tracing.otel.control.protocol.RuntimePolicyControlHandler;
import space.br1440.platform.tracing.otel.control.protocol.RuntimeControlMutationPolicy;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.javaagent.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.sampler.SamplerStateHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the full JMX control-protocol pipeline:
 *
 * <pre>
 * raw Map (wire payload)
 *   --[TracingControlProtocol]--> TracingControlProtocolDecodeResult
 *       --[RuntimePolicyControlHandler]--> RuntimePolicyControlHandleResult
 *           (domain validate inside handler)
 *               --[JmxRuntimePolicyApplier]-->
 *                   SamplerStateHolder / ValidatingSpanProcessor  (live state)
 * </pre>
 *
 * <p>All components are wired with <em>real</em> production implementations;
 * no mocking framework is used.  The test verifies observable side-effects
 * (live state changes) and the returned handle result rather than internal
 * interactions.
 */
class JmxControlProtocolE2ETest {

    // =========================================================================
    // Wired components (real implementations)
    // =========================================================================

    private SamplerStateHolder       samplerHolder;
    private ValidatingSpanProcessor  validatingProcessor;
    private PlatformSamplingControl  samplingControl;
    private PlatformValidationControl validationControl;
    private JmxRuntimePolicyApplier  applier;
    private RuntimePolicyControlHandler handler;

    @BeforeEach
    void wireComponents() {
        // --- SamplerStateHolder: start with enabled=true, ratio=0.1 ---
        samplerHolder = new SamplerStateHolder(
                true,
                List.of(),
                List.of(),
                Collections.emptyMap(),
                0.1d);

        // --- ValidatingSpanProcessor: start with enabled=false, strict=false ---
        validatingProcessor = new ValidatingSpanProcessor(false, true);
        validatingProcessor.tryApplyPolicyUpdate(false, false, "startup");

        // --- JMX MBeans backed by real holders ---
        LongAdder invalidCounter = new LongAdder();
        samplingControl  = new PlatformSamplingControl(samplerHolder, null, invalidCounter);
        validationControl = new PlatformValidationControl(validatingProcessor, invalidCounter);

        // --- Applier and handler ---
        applier  = new JmxRuntimePolicyApplier(samplingControl, validationControl);
        handler  = new RuntimePolicyControlHandler(
                applier, RuntimeControlMutationPolicy.startupConfigured(true));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private RuntimePolicyControlHandleResult handle(Map<String, Object> rawPayload) {
        TracingControlProtocolDecodeResult decoded = TracingControlProtocol.current().decode(rawPayload);
        return handler.handle(decoded);
    }

    private static Map<String, Object> applyPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        map.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue());
        return map;
    }

    private static Map<String, Object> readPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        map.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.READ_APPLIED_STATE.wireValue());
        return map;
    }

    // =========================================================================
    // APPLY_RUNTIME_POLICY — sampling
    // =========================================================================

    @Nested
    class ApplySampling {

        @Test
        void updatesDefaultSamplingRatio() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.42d);

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.isSuccess()).isTrue();
            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.42d);
        }

        @Test
        void killSwitchDisablesSampler() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, true);

            handle(payload);

            assertThat(samplerHolder.current().enabled()).isFalse();
        }

        @Test
        void killSwitchOffReEnablesSampler() {
            // First disable via kill-switch
            Map<String, Object> disable = applyPayload();
            disable.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, true);
            handle(disable);
            assertThat(samplerHolder.current().enabled()).isFalse();

            // Then re-enable
            Map<String, Object> enable = applyPayload();
            enable.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, false);
            handle(enable);

            assertThat(samplerHolder.current().enabled()).isTrue();
        }

        @Test
        void appliesRouteRatios() {
            Map<String, Double> ratios = new LinkedHashMap<>();
            ratios.put("/api/v1", 0.8d);
            ratios.put("/health", 0.0d);

            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, ratios);

            handle(payload);

            assertThat(samplerHolder.current().routeRatios())
                    .containsEntry("/api/v1", 0.8d)
                    .containsEntry("/health", 0.0d);
        }

        @Test
        void subsequentApplyPreservesUnchangedRoutesRatios() {
            // First apply: set route ratios
            Map<String, Double> ratios = new LinkedHashMap<>();
            ratios.put("/svc", 0.5d);
            Map<String, Object> first = applyPayload();
            first.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, ratios);
            handle(first);

            // Second apply: only update ratio (route ratios absent in payload)
            Map<String, Object> second = applyPayload();
            second.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.9d);
            handle(second);

            // Route ratios must still be present (partial update)
            assertThat(samplerHolder.current().routeRatios()).containsKey("/svc");
            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.9d);
        }
    }

    // =========================================================================
    // APPLY_RUNTIME_POLICY — validation
    // =========================================================================

    @Nested
    class ApplyValidation {

        @Test
        void enablesValidation() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);

            handle(payload);

            assertThat(validatingProcessor.isEnabled()).isTrue();
        }

        @Test
        void setsValidationModeStrict() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE,    "STRICT");

            handle(payload);

            assertThat(validatingProcessor.isStrict()).isTrue();
            assertThat(validatingProcessor.isEnabled()).isTrue();
        }

        @Test
        void setsValidationModeLogOnly() {
            // First enable strict, then switch to LOG_ONLY
            Map<String, Object> strict = applyPayload();
            strict.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);
            strict.put(TracingControlProtocolKeys.VALIDATION_MODE,    "STRICT");
            handle(strict);
            assertThat(validatingProcessor.isStrict()).isTrue();

            Map<String, Object> logOnly = applyPayload();
            logOnly.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");
            handle(logOnly);

            assertThat(validatingProcessor.isStrict()).isFalse();
        }

        @Test
        void validationStrictBooleanAloneUpdatesStrict() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

            handle(payload);

            assertThat(validatingProcessor.isStrict()).isTrue();
        }
    }

    // =========================================================================
    // APPLY_RUNTIME_POLICY — combined payload
    // =========================================================================

    @Test
    void combinedPayloadUpdatesBothSamplingAndValidation() {
        Map<String, Object> payload = applyPayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO,     0.6d);
        payload.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE,    "STRICT");

        RuntimePolicyControlHandleResult result = handle(payload);

        assertThat(result.isSuccess()).isTrue();
        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.6d);
        assertThat(validatingProcessor.isEnabled()).isTrue();
        assertThat(validatingProcessor.isStrict()).isTrue();
    }

    // =========================================================================
    // DECODE_REJECTED
    // =========================================================================

    @Nested
    class DecodeRejected {

        @Test
        void rejectsMissingOperation() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
            // operation absent

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.status())
                    .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DECODE_REJECTED);
            assertThat(result.operation()).isEmpty();
        }

        @Test
        void rejectsUnsupportedContractVersion() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 999);
            payload.put(TracingControlProtocolKeys.OPERATION,
                    TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue());

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.status())
                    .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DECODE_REJECTED);
        }

        @Test
        void rejectsUnknownOperation() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.OPERATION, "DO_SOMETHING_WEIRD");

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.status())
                    .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DECODE_REJECTED);
        }

        @Test
        void decodeRejectionLeavesLiveStateUnchanged() {
            double ratioBefore = samplerHolder.current().defaultRatio();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 999);
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.99d);
            handle(payload);

            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(ratioBefore);
        }
    }

    // =========================================================================
    // DOMAIN_REJECTED
    // =========================================================================

    @Nested
    class DomainRejected {

        @Test
        void rejectsOutOfRangeSamplingRatio() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 1.5d);

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.status())
                    .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
            assertThat(result.operation())
                    .contains(TracingControlProtocolOperation.APPLY_RUNTIME_POLICY);
        }

        @Test
        void rejectsUnknownValidationMode() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "EXPLOSIVE");

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.status())
                    .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
            assertThat(result.violations())
                    .anyMatch(v -> v.contains("not a recognised mode"));
        }

        @Test
        void rejectsConflictingModeAndStrictFlag() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE,   "LOG_ONLY");
            payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.status())
                    .isEqualTo(RuntimePolicyControlHandleResult.HandleStatus.DOMAIN_REJECTED);
            assertThat(result.violations())
                    .anyMatch(v -> v.contains("conflicts with"));
        }

        @Test
        void domainRejectionLeavesLiveStateUnchanged() {
            double ratioBefore        = samplerHolder.current().defaultRatio();
            boolean validBefore       = validatingProcessor.isEnabled();

            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO,    2.0d);   // violation
            payload.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);  // would change state

            handle(payload);

            // Neither sampling nor validation state must change
            assertThat(samplerHolder.current().defaultRatio()).isEqualTo(ratioBefore);
            assertThat(validatingProcessor.isEnabled()).isEqualTo(validBefore);
        }

        @Test
        void collectsMultipleViolationsInSinglePass() {
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO,    -0.1d);   // violation 1
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE,   "LOG_ONLY");
            payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);    // violation 2

            RuntimePolicyControlHandleResult result = handle(payload);

            assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // READ_APPLIED_STATE — short-circuit
    // =========================================================================

    @Test
    void readAppliedStateReturnsSuccessWithoutMutatingState() {
        double ratioBefore  = samplerHolder.current().defaultRatio();
        long versionBefore  = samplerHolder.current().version();

        RuntimePolicyControlHandleResult result = handle(readPayload());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.operation())
                .contains(TracingControlProtocolOperation.READ_APPLIED_STATE);
        // Live state must be completely unchanged
        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(ratioBefore);
        assertThat(samplerHolder.current().version()).isEqualTo(versionBefore);
    }

    // =========================================================================
    // ReadAppliedStateHandler — snapshot correctness
    // =========================================================================

    @Nested
    class ReadAppliedStateHandlerTest {

        private ReadAppliedStateHandler readHandler;

        @BeforeEach
        void setUpReadHandler() {
            readHandler = new ReadAppliedStateHandler(samplerHolder, validatingProcessor);
        }

        @Test
        void snapshotReflectsInitialState() {
            Map<String, Object> state = readHandler.read();

            assertThat(state).containsKey(TracingControlProtocolKeys.SAMPLING_RATIO);
            assertThat((double) state.get(TracingControlProtocolKeys.SAMPLING_RATIO))
                    .isEqualTo(0.1d);
            assertThat((boolean) state.get(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED))
                    .isFalse(); // sampler is enabled → kill-switch is false
            assertThat((boolean) state.get(TracingControlProtocolKeys.VALIDATION_ENABLED))
                    .isFalse();
            assertThat(state.get(TracingControlProtocolKeys.VALIDATION_MODE))
                    .isEqualTo("LOG_ONLY");
        }

        @Test
        void snapshotUpdatesAfterApply() {
            // Apply a combined payload
            Map<String, Object> payload = applyPayload();
            payload.put(TracingControlProtocolKeys.SAMPLING_RATIO,     0.77d);
            payload.put(TracingControlProtocolKeys.VALIDATION_ENABLED, true);
            payload.put(TracingControlProtocolKeys.VALIDATION_MODE,    "STRICT");
            handle(payload);

            Map<String, Object> state = readHandler.read();

            assertThat((double) state.get(TracingControlProtocolKeys.SAMPLING_RATIO))
                    .isEqualTo(0.77d);
            assertThat((boolean) state.get(TracingControlProtocolKeys.VALIDATION_ENABLED))
                    .isTrue();
            assertThat(state.get(TracingControlProtocolKeys.VALIDATION_MODE))
                    .isEqualTo("STRICT");
            assertThat((boolean) state.get(TracingControlProtocolKeys.VALIDATION_STRICT))
                    .isTrue();
        }

        @Test
        void snapshotIsImmutable() {
            Map<String, Object> state = readHandler.read();

            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class,
                    () -> state.put("should.not.work", "value"));
        }

        @Test
        void metadataKeysPresent() {
            Map<String, Object> state = readHandler.read();

            assertThat(state).containsKey("_meta.samplingConfigVersion")
                    .containsKey("_meta.samplingConfigSource")
                    .containsKey("_meta.validationConfigVersion")
                    .containsKey("_meta.validationConfigSource");
        }
    }
}
