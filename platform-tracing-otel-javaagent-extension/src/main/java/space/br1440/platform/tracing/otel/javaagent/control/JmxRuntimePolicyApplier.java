package space.br1440.platform.tracing.otel.javaagent.control;

import lombok.extern.slf4j.Slf4j;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.otel.control.protocol.RuntimePolicyApplier;
import space.br1440.platform.tracing.otel.javaagent.jmx.sampling.PlatformSamplingControl;
import space.br1440.platform.tracing.otel.javaagent.jmx.validation.PlatformValidationControl;

import java.util.Map;
import java.util.Objects;

/**
 * {@link RuntimePolicyApplier} implementation that routes a normalised,
 * domain-validated control-protocol payload to the JMX management beans.
 *
 * <p>This class is the final step in the pipeline:
 * <pre>
 *   TracingControlProtocolDecoder
 *       → RuntimePolicyControlHandler
 *           → RuntimePolicyControlDomainValidator
 *               → JmxRuntimePolicyApplier  ← this class
 * </pre>
 *
 * <p><b>Pre-conditions (guaranteed by the handler):</b>
 * <ul>
 *   <li>The payload has already passed structural decode (all keys are
 *       known, all types match the schema).</li>
 *   <li>The payload has already passed domain/policy validation (ratios are
 *       in range, validation-mode values are recognised and consistent).</li>
 *   <li>{@code operation} is always {@link TracingControlProtocolOperation#APPLY_RUNTIME_POLICY}
 *       when this applier is reached; read-only operations are
 *       short-circuited before the applier is called.</li>
 * </ul>
 *
 * <p>Fields absent from the payload are treated as "no change" – only
 * fields that are explicitly present in the normalised map are forwarded
 * to the respective MBean.  This allows partial policy updates without
 * requiring callers to repeat unchanged values.
 */
@Slf4j
public final class JmxRuntimePolicyApplier implements RuntimePolicyApplier {

    private final PlatformSamplingControl  sampling;
    private final PlatformValidationControl validation;

    public JmxRuntimePolicyApplier(PlatformSamplingControl sampling,
                                   PlatformValidationControl validation) {
        this.sampling   = Objects.requireNonNull(sampling,   "sampling");
        this.validation = Objects.requireNonNull(validation, "validation");
    }

    @Override
    public void apply(TracingControlProtocolOperation operation,
                      Map<String, Object> normalizedPayload,
                      String source) {
        Objects.requireNonNull(operation,        "operation");
        Objects.requireNonNull(normalizedPayload, "normalizedPayload");
        Objects.requireNonNull(source,            "source");

        if (operation != TracingControlProtocolOperation.APPLY_RUNTIME_POLICY) {
            // Defensive guard: the handler contract guarantees this never fires.
            log.warn("JmxRuntimePolicyApplier received unexpected operation '{}' – ignored", operation);
            return;
        }

        applySamplingFields(normalizedPayload, source);
        applyValidationFields(normalizedPayload, source);
    }

    // -------------------------------------------------------------------------
    // Sampling
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void applySamplingFields(Map<String, Object> payload, String source) {
        boolean hasSamplingField =
                payload.containsKey(TracingControlProtocolKeys.SAMPLING_RATIO)
                || payload.containsKey(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS)
                || payload.containsKey(TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES)
                || payload.containsKey(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES)
                || payload.containsKey(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED)
                || payload.containsKey(TracingControlProtocolKeys.SAMPLING_QA_TRACE_ENABLED)
                || payload.containsKey(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_ENABLED);

        if (!hasSamplingField) {
            return;
        }

        // Resolve values, falling back to current live state for absent fields.
        boolean enabled = resolveBoolean(
                payload, TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED,
                !sampling.isSamplerEnabled());
        // Note: kill-switch semantics – KILL_SWITCH_ENABLED=true means sampler disabled.
        // We store sampler-enabled as the inverse of the kill-switch flag.
        // If the key is absent, preserve the current live sampler-enabled state.
        boolean samplerEnabled = payload.containsKey(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED)
                ? !enabled
                : sampling.isSamplerEnabled();

        double defaultRatio = resolveDouble(
                payload, TracingControlProtocolKeys.SAMPLING_RATIO,
                sampling.getSamplingRatio());

        Map<String, Double> routeRatios =
                (Map<String, Double>) payload.get(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS);
        // Route ratios: if absent from payload keep live state via MBean getter.
        Map<String, Double> effectiveRouteRatios =
                routeRatios != null ? routeRatios : sampling.getRouteRatios();

        String[] dropPaths = resolveStringArray(
                payload, TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES,
                sampling.getDropPathPrefixes());

        String[] forceValues = resolveStringArray(
                payload, TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                sampling.getForceRecordValues());

        // Convert Map<String,Double> → parallel prefix/value arrays for the MBean.
        String[] routePrefixes;
        double[] ratioValues;
        if (effectiveRouteRatios == null || effectiveRouteRatios.isEmpty()) {
            routePrefixes = new String[0];
            ratioValues   = new double[0];
        } else {
            routePrefixes = effectiveRouteRatios.keySet().toArray(new String[0]);
            ratioValues   = new double[routePrefixes.length];
            for (int i = 0; i < routePrefixes.length; i++) {
                ratioValues[i] = effectiveRouteRatios.get(routePrefixes[i]);
            }
        }

        sampling.updateSamplingPolicy(
                samplerEnabled,
                defaultRatio,
                dropPaths,
                forceValues,
                routePrefixes,
                ratioValues,
                source);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void applyValidationFields(Map<String, Object> payload, String source) {
        boolean hasValidationField =
                payload.containsKey(TracingControlProtocolKeys.VALIDATION_ENABLED)
                || payload.containsKey(TracingControlProtocolKeys.VALIDATION_MODE)
                || payload.containsKey(TracingControlProtocolKeys.VALIDATION_STRICT);

        if (!hasValidationField) {
            return;
        }

        // Resolve enabled: explicit key wins; otherwise preserve live state.
        boolean enabled = resolveBoolean(
                payload, TracingControlProtocolKeys.VALIDATION_ENABLED,
                validation.isValidationEnabled());

        // Resolve strict: VALIDATION_MODE takes precedence over VALIDATION_STRICT
        // (domain validator already guaranteed they are consistent when both present).
        boolean strict = resolveStrictFromPayload(payload);

        validation.updateValidationPolicy(enabled, strict, source);

        log.debug("Validation policy applied via protocol handler: enabled={}, strict={}, source={}",
                enabled, strict, source);
    }

    /**
     * Derives the effective {@code strict} value from the payload.
     *
     * <p>Priority order (highest first):
     * <ol>
     *   <li>{@code validation.mode} – {@code STRICT} → {@code true},
     *       {@code LOG_ONLY} → {@code false}.</li>
     *   <li>{@code validation.strict} boolean key.</li>
     *   <li>Current live state from the MBean.</li>
     * </ol>
     */
    private boolean resolveStrictFromPayload(Map<String, Object> payload) {
        Object rawMode = payload.get(TracingControlProtocolKeys.VALIDATION_MODE);
        if (rawMode instanceof String mode) {
            // Domain validator already ensured mode is one of the allowed values.
            return "STRICT".equals(mode);
        }
        Object rawStrict = payload.get(TracingControlProtocolKeys.VALIDATION_STRICT);
        if (rawStrict instanceof Boolean b) {
            return b;
        }
        return validation.isValidationStrict();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean resolveBoolean(Map<String, Object> payload,
                                          String key,
                                          boolean fallback) {
        Object raw = payload.get(key);
        return raw instanceof Boolean b ? b : fallback;
    }

    private static double resolveDouble(Map<String, Object> payload,
                                        String key,
                                        double fallback) {
        Object raw = payload.get(key);
        return raw instanceof Double d ? d : fallback;
    }

    private static String[] resolveStringArray(Map<String, Object> payload,
                                               String key,
                                               String[] fallback) {
        Object raw = payload.get(key);
        return raw instanceof String[] arr ? arr : fallback;
    }
}
