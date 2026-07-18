package space.br1440.platform.tracing.otel.extension.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.otel.extension.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.extension.jmx.validation.PlatformValidationControl;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет прикладной слой control-protocol на реальных in-memory holder'ах.
 */
class JmxRuntimePolicyApplierTest {

    private SamplerStateHolder samplerHolder;
    private ValidatingSpanProcessor validatingSpanProcessor;
    private JmxRuntimePolicyApplier applier;

    @BeforeEach
    void setUp() {
        samplerHolder = new SamplerStateHolder(
                true,
                List.of(),
                List.of(),
                Collections.emptyMap(),
                0.25d);
        validatingSpanProcessor = new ValidatingSpanProcessor(false, true);

        PlatformSamplingControl samplingControl =
                new PlatformSamplingControl(samplerHolder, null, new LongAdder());
        PlatformValidationControl validationControl =
                new PlatformValidationControl(validatingSpanProcessor, new LongAdder());
        applier = new JmxRuntimePolicyApplier(samplingControl, validationControl);
    }

    private static Map<String, Object> basePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TracingControlProtocolKeys.CONTRACT_VERSION, 1);
        payload.put(TracingControlProtocolKeys.OPERATION,
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY.wireValue());
        return payload;
    }

    private void apply(Map<String, Object> payload) {
        applier.apply(
                TracingControlProtocolOperation.APPLY_RUNTIME_POLICY,
                payload,
                "test");
    }

    @Test
    void emptyPayloadDoesNotChangeState() {
        long samplerVersion = samplerHolder.current().version();
        long validationVersion = validatingSpanProcessor.getPolicyVersion();

        apply(new LinkedHashMap<>());

        assertThat(samplerHolder.current().version()).isEqualTo(samplerVersion);
        assertThat(validatingSpanProcessor.getPolicyVersion()).isEqualTo(validationVersion);
    }

    @Test
    void appliesSamplingRatioAndPreservesEnabledState() {
        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.75d);

        apply(payload);

        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.75d);
        assertThat(samplerHolder.current().enabled()).isTrue();
        assertThat(samplerHolder.current().source()).isEqualTo("test");
    }

    @Test
    void killSwitchTrueDisablesSampler() {
        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED, true);

        apply(payload);

        assertThat(samplerHolder.current().enabled()).isFalse();
    }

    @Test
    void routeRatiosAreAppliedAsOneSnapshot() {
        Map<String, Double> ratios = new LinkedHashMap<>();
        ratios.put("/api", 0.3d);
        ratios.put("/health", 0.0d);

        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS, ratios);

        apply(payload);

        assertThat(samplerHolder.current().routeRatios())
                .containsEntry("/api", 0.3d)
                .containsEntry("/health", 0.0d);
    }

    @Test
    void appliesDropAndForceValues() {
        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES,
                new String[]{"/internal", "/health"});
        payload.put(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                new String[]{"qa", "debug"});

        apply(payload);

        assertThat(samplerHolder.current().droppedRoutes())
                .containsExactly("/internal", "/health");
        assertThat(samplerHolder.current().forceRecordValues())
                .containsExactlyInAnyOrder("qa", "debug");
    }

    @Test
    void validationModeStrictEnablesStrictRuntimePolicy() {
        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "STRICT");

        apply(payload);

        assertThat(validatingSpanProcessor.isEnabled()).isTrue();
        assertThat(validatingSpanProcessor.isStrict()).isTrue();
        assertThat(validatingSpanProcessor.getPolicySource()).isEqualTo("test");
    }

    @Test
    void validationModeLogOnlyOverridesStrictFlag() {
        validatingSpanProcessor.tryApplyPolicyUpdate(true, true, "prime");

        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.VALIDATION_MODE, "LOG_ONLY");
        payload.put(TracingControlProtocolKeys.VALIDATION_STRICT, true);

        apply(payload);

        assertThat(validatingSpanProcessor.isStrict()).isFalse();
    }

    @Test
    void readOperationProducesNoSideEffects() {
        Map<String, Object> payload = basePayload();
        payload.put(TracingControlProtocolKeys.SAMPLING_RATIO, 0.5d);
        long samplerVersion = samplerHolder.current().version();

        applier.apply(
                TracingControlProtocolOperation.READ_APPLIED_STATE,
                payload,
                "test");

        assertThat(samplerHolder.current().version()).isEqualTo(samplerVersion);
        assertThat(samplerHolder.current().defaultRatio()).isEqualTo(0.25d);
    }
}
