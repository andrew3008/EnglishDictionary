package space.br1440.platform.tracing.otel.control.protocol;

import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolOperation;
import space.br1440.platform.tracing.api.control.protocol.TracingControlProtocolViolation;

import java.util.List;
import java.util.Optional;

/**
 * Immutable result of a single {@link RuntimePolicyControlHandler#handle} call.
 *
 * <p>Three terminal states are possible:
 * <ul>
 *   <li>{@link HandleStatus#SUCCESS} – the payload was structurally valid,
 *       domain-valid, and (for mutating operations) successfully applied to
 *       the runtime state.</li>
 *   <li>{@link HandleStatus#DECODE_REJECTED} – the wire-level decode failed;
 *       the operation could not be determined.  {@link #violations()} carries
 *       the structural violation messages from the decode layer.</li>
 *   <li>{@link HandleStatus#DOMAIN_REJECTED} – the payload passed structural
 *       decode but failed domain/policy validation; the operation is known.
 *       {@link #violations()} carries the domain violation messages.</li>
 * </ul>
 *
 * <p>The {@link #operation()} accessor returns {@link Optional#empty()} only
 * for {@link HandleStatus#DECODE_REJECTED}, because the operation value is
 * part of the payload that failed to decode.
 */
public record RuntimePolicyControlHandleResult(
        HandleStatus status,
        Optional<TracingControlProtocolOperation> operation,
        List<String> violations
) {

    /** Terminal status of a control-protocol handle attempt. */
    public enum HandleStatus {
        /** Payload decoded, domain-validated, and applied (or read-only). */
        SUCCESS,
        /** Structural/wire decode failed; operation unknown. */
        DECODE_REJECTED,
        /** Decode succeeded but domain/policy validation failed. */
        DOMAIN_REJECTED,
        /** Decode and domain validation succeeded, but mutation is disabled. */
        MUTATION_REJECTED
    }

    public RuntimePolicyControlHandleResult {
        violations = (violations == null) ? List.of() : List.copyOf(violations);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static RuntimePolicyControlHandleResult success(
            TracingControlProtocolOperation operation) {
        return new RuntimePolicyControlHandleResult(
                HandleStatus.SUCCESS,
                Optional.of(operation),
                List.of());
    }

    public static RuntimePolicyControlHandleResult decodeRejected(
            List<TracingControlProtocolViolation> violations) {
        List<String> messages = violations.stream()
                .map(v -> v.code().name() + ": " + v.reason())
                .toList();
        return new RuntimePolicyControlHandleResult(
                HandleStatus.DECODE_REJECTED,
                Optional.empty(),
                messages);
    }

    public static RuntimePolicyControlHandleResult domainRejected(
            TracingControlProtocolOperation operation,
            List<String> domainViolations) {
        return new RuntimePolicyControlHandleResult(
                HandleStatus.DOMAIN_REJECTED,
                Optional.of(operation),
                domainViolations);
    }

    public static RuntimePolicyControlHandleResult mutationRejected(
            TracingControlProtocolOperation operation,
            String reason) {
        return new RuntimePolicyControlHandleResult(
                HandleStatus.MUTATION_REJECTED,
                Optional.of(operation),
                List.of(reason));
    }

    // -------------------------------------------------------------------------
    // Convenience predicates
    // -------------------------------------------------------------------------

    public boolean isSuccess() {
        return status == HandleStatus.SUCCESS;
    }

    public boolean isRejected() {
        return status != HandleStatus.SUCCESS;
    }
}
