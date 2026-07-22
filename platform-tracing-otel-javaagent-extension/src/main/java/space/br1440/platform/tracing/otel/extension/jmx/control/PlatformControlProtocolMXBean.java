package space.br1440.platform.tracing.otel.extension.jmx.control;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.OpenDataException;

/**
 * JMX MXBean interface for the unified tracing control protocol.
 *
 * <p>Exposes two operations:
 * <ol>
 *   <li>{@link #applyPolicy(CompositeData)} – accepts a
 *       {@code APPLY_RUNTIME_POLICY} or any other wire payload encoded as
 *       {@link CompositeData} and delegates it through the full pipeline:
 *       decode → domain-validate → apply.</li>
 *   <li>{@link #readAppliedState()} – returns the current live runtime
 *       state as a {@link CompositeData} snapshot.</li>
 * </ol>
 *
 * <p>The interface is intentionally thin: all business logic lives in
 * {@link space.br1440.platform.tracing.core.control.protocol.RuntimePolicyControlHandler}
 * (core) and {@link space.br1440.platform.tracing.otel.extension.control.JmxRuntimePolicyApplier}
 * (otel-extension). This MXBean is purely a wire-transport adapter.
 *
 * <h2>Classloader neutrality</h2>
 * {@link CompositeData} is part of {@code javax.management.openmbean} in the
 * JDK and is visible across all classloaders without additional bridging.
 * The implementation maps CompositeData → {@code Map<String,Object>} before
 * handing off to the core layer.
 */
public interface PlatformControlProtocolMXBean {

    /**
     * Applies a runtime-policy payload encoded as JMX {@link CompositeData}.
     *
     * <p>The {@code CompositeData} must contain at least the envelope keys
     * ({@code contractVersion}, {@code operation}) and zero or more
     * operation-specific keys as defined by
     * {@link space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys}.
     *
     * @param payload JMX payload; may be {@code null} – treated as a
     *                structural decode failure
     * @return human-readable result string; starts with {@code "SUCCESS"},
     *         {@code "DECODE_REJECTED"} or {@code "DOMAIN_REJECTED"} followed
     *         by optional violation messages
     */
    String applyPolicy(CompositeData payload);

    /**
     * Returns the current live runtime state as a {@link CompositeData}
     * snapshot.
     *
     * <p>The returned composite uses the same key names as the
     * {@code APPLY_RUNTIME_POLICY} operation so callers can inspect and
     * round-trip the live configuration.
     *
     * @return non-{@code null} immutable snapshot
     * @throws OpenDataException if the snapshot cannot be encoded (should
     *                           never happen with the current type set)
     */
    CompositeData readAppliedState() throws OpenDataException;
}
