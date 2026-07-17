package space.br1440.platform.tracing.core.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolDecodeResult;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolKeys;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;

import java.util.Map;
import java.util.Objects;

/**
 * Façade that closes the full control-protocol pipeline:
 * <pre>
 *   decode  →  domain validate  →  apply
 * </pre>
 *
 * <p>The caller is responsible for running the decode step first (via
 * {@code TracingControlProtocolDecoder}) and passing the resulting
 * {@link TracingControlProtocolDecodeResult} to {@link #handle}.  This class
 * then performs domain/policy validation and, on success, delegates to the
 * injected {@link RuntimePolicyApplier} for the actual state mutation.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li>{@link RuntimePolicyApplier#apply} is called <em>only</em> when
 *       both {@code decodeResult.valid()} and
 *       {@code domainResult.valid()} are {@code true}.</li>
 *   <li>Read-only operations ({@link TracingControlProtocolOperation#READ_APPLIED_STATE})
 *       are short-circuited before domain validation and never reach the
 *       applier.</li>
 *   <li>The method never throws for expected protocol errors; all rejection
 *       reasons are encoded in the returned
 *       {@link RuntimePolicyControlHandleResult}.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * This class is stateless after construction and therefore inherently
 * thread-safe, provided that the injected {@link RuntimePolicyApplier}
 * implementation is also thread-safe.
 */
public final class RuntimePolicyControlHandler {

    private final RuntimePolicyApplier applier;

    public RuntimePolicyControlHandler(RuntimePolicyApplier applier) {
        this.applier = Objects.requireNonNull(applier, "applier");
    }

    /**
     * Handles a single decoded protocol request.
     *
     * @param decodeResult the result produced by
     *                     {@code TracingControlProtocolDecoder.decode(Map)}
     * @return a result indicating success or the rejection reason;
     *         never {@code null}
     */
    public RuntimePolicyControlHandleResult handle(
            TracingControlProtocolDecodeResult decodeResult) {
        Objects.requireNonNull(decodeResult, "decodeResult");

        // --- Step 1: structural / wire decode ---
        if (!decodeResult.valid()) {
            return RuntimePolicyControlHandleResult.decodeRejected(decodeResult.violations());
        }

        TracingControlProtocolOperation operation = decodeResult.operation()
                .orElseThrow(() -> new IllegalStateException(
                        "Decode result is valid but operation is absent – decoder contract violation"));

        // --- Step 2: read-only short-circuit ---
        if (operation == TracingControlProtocolOperation.READ_APPLIED_STATE) {
            return RuntimePolicyControlHandleResult.success(operation);
        }

        // --- Step 3: domain / policy validation ---
        Map<String, Object> payload = decodeResult.normalizedPayload();
        TracingControlDomainValidationResult domainResult =
                RuntimePolicyControlDomainValidator.validate(payload);

        if (!domainResult.valid()) {
            return RuntimePolicyControlHandleResult.domainRejected(operation, domainResult.violations());
        }

        // --- Step 4: apply (mutating operations only) ---
        String source = resolveSource(payload);
        applier.apply(operation, payload, source);

        return RuntimePolicyControlHandleResult.success(operation);
    }

    private static String resolveSource(Map<String, Object> payload) {
        Object raw = payload.get(TracingControlProtocolKeys.SOURCE);
        return (raw instanceof String s && !s.isBlank()) ? s : "JMX";
    }
}
