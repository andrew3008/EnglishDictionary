package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

import java.util.Map;

/**
 * SPI for applying a normalised, domain-validated control-protocol payload
 * to the platform runtime state.
 *
 * <p>Implementations live in {@code platform-tracing-otel-javaagent-extension} (or any
 * other runtime module) and are injected into
 * {@link RuntimePolicyControlHandler}.  This inversion of control keeps
 * {@code platform-tracing-otel} free from any dependency on the extension
 * module.
 *
 * <p><b>Contract:</b> this method is called by
 * {@link RuntimePolicyControlHandler} <em>only</em> after both decode-layer
 * and domain-layer validation have succeeded.  Implementations may therefore
 * assume that every value in {@code normalizedPayload} is structurally correct
 * and domain-valid; no re-validation is required or expected.
 *
 * <p>Implementations must be thread-safe: JMX threads may call
 * {@code apply} concurrently with other management operations.
 */
public interface RuntimePolicyApplier {

    /**
     * Applies the decoded, domain-validated payload to the live runtime state.
     *
     * @param operation       the resolved protocol operation
     *                        (always {@link TracingControlProtocolOperation#APPLY_RUNTIME_POLICY}
     *                        in practice; read-only operations are short-circuited
     *                        before this call)
     * @param normalizedPayload normalised map produced by the decoder and
     *                          accepted by the domain validator; never {@code null},
     *                          never modified after this call
     * @param source          human-readable origin label (e.g. {@code "JMX"},
     *                        {@code "REST"}, {@code "test"})
     */
    void apply(TracingControlProtocolOperation operation,
               Map<String, Object> normalizedPayload,
               String source);
}
