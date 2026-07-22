package space.br1440.platform.tracing.otel.extension.control;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerState;
import space.br1440.platform.tracing.otel.extension.sampler.SamplerStateHolder;
import space.br1440.platform.tracing.otel.extension.processor.ValidatingSpanProcessor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the current live runtime state from {@link SamplerStateHolder} and
 * {@link ValidatingSpanProcessor} and returns it as a normalised
 * {@code Map<String, Object>} that mirrors the
 * {@code APPLY_RUNTIME_POLICY} wire schema.
 *
 * <p>The returned map uses the same key constants as
 * {@link TracingControlProtocolKeys} so callers can round-trip:
 * <pre>
 *   Map&lt;String,Object&gt; state = readHandler.read();
 *   // state keys are identical to what APPLY_RUNTIME_POLICY accepts
 * </pre>
 *
 * <p>This class is used by the JMX control endpoint to implement the
 * {@code READ_APPLIED_STATE} operation without coupling the protocol
 * handler to specific runtime types.
 *
 * <h2>Thread safety</h2>
 * All reads are performed via the lock-free snapshot APIs of both holders;
 * the returned map is a fresh immutable snapshot per call.
 */
public final class ReadAppliedStateHandler {

    private final SamplerStateHolder     samplerHolder;
    private final ValidatingSpanProcessor validatingProcessor;

    public ReadAppliedStateHandler(SamplerStateHolder samplerHolder,
                                   ValidatingSpanProcessor validatingProcessor) {
        this.samplerHolder       = Objects.requireNonNull(samplerHolder,       "samplerHolder");
        this.validatingProcessor = Objects.requireNonNull(validatingProcessor, "validatingProcessor");
    }

    /**
     * Returns an immutable snapshot of the current applied runtime policy.
     *
     * <p>The map always contains the following keys (all from
     * {@link TracingControlProtocolKeys}):
     * <ul>
     *   <li>{@code sampling.ratio} – current default sampling ratio</li>
     *   <li>{@code sampling.killSwitch.enabled} – {@code true} when sampler is disabled</li>
     *   <li>{@code sampling.routeRatios} – unmodifiable copy of route-ratio map</li>
     *   <li>{@code sampling.dropPathPrefixes} – unmodifiable list of drop-path prefixes</li>
     *   <li>{@code sampling.forceHeader.values} – unmodifiable list of force-header values</li>
     *   <li>{@code validation.enabled}</li>
     *   <li>{@code validation.strict}</li>
     *   <li>{@code validation.mode} – {@code "STRICT"} or {@code "LOG_ONLY"}</li>
     *   <li>{@code _meta.samplingConfigVersion}</li>
     *   <li>{@code _meta.samplingConfigSource}</li>
     *   <li>{@code _meta.validationConfigVersion}</li>
     *   <li>{@code _meta.validationConfigSource}</li>
     * </ul>
     *
     * @return immutable state snapshot; never {@code null}
     */
    public Map<String, Object> read() {
        SamplerState sampler = samplerHolder.current();
        Map<String, Object> map = new LinkedHashMap<>();

        // --- Sampling ---
        map.put(TracingControlProtocolKeys.SAMPLING_RATIO,
                sampler.defaultRatio());
        // Kill-switch is the logical inverse of sampler-enabled.
        map.put(TracingControlProtocolKeys.SAMPLING_KILL_SWITCH_ENABLED,
                !sampler.enabled());
        map.put(TracingControlProtocolKeys.SAMPLING_ROUTE_RATIOS,
                Collections.unmodifiableMap(new LinkedHashMap<>(sampler.routeRatios())));
        map.put(TracingControlProtocolKeys.SAMPLING_DROP_PATH_PREFIXES,
                Collections.unmodifiableList(sampler.droppedRoutes()));
        map.put(TracingControlProtocolKeys.SAMPLING_FORCE_HEADER_VALUES,
                Collections.unmodifiableList(
                        sampler.forceRecordValues() == null
                                ? java.util.List.of()
                                : java.util.List.copyOf(sampler.forceRecordValues())));

        // --- Validation ---
        boolean validationEnabled = validatingProcessor.isEnabled();
        boolean validationStrict  = validatingProcessor.isStrict();
        map.put(TracingControlProtocolKeys.VALIDATION_ENABLED, validationEnabled);
        map.put(TracingControlProtocolKeys.VALIDATION_STRICT,  validationStrict);
        map.put(TracingControlProtocolKeys.VALIDATION_MODE,
                validationStrict ? "STRICT" : "LOG_ONLY");

        // --- Metadata ---
        map.put("_meta.samplingConfigVersion",   sampler.version());
        map.put("_meta.samplingConfigSource",    sampler.source());
        map.put("_meta.validationConfigVersion", validatingProcessor.getPolicyVersion());
        map.put("_meta.validationConfigSource",  validatingProcessor.getPolicySource());

        return Collections.unmodifiableMap(map);
    }
}
