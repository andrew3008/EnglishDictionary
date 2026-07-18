package space.br1440.platform.tracing.api.control.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record TracingControlProtocolDecodeResult(boolean valid,
                                                 Optional<TracingControlProtocolOperation> operation,
                                                 Map<String, Object> normalizedPayload,
                                                 List<TracingControlProtocolViolation> violations) {

    public TracingControlProtocolDecodeResult {
        normalizedPayload = (normalizedPayload == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(normalizedPayload));

        violations = (violations == null) ? List.of() : List.copyOf(violations);

        if (valid && operation.isEmpty()) {
            throw new IllegalArgumentException("valid decode result must contain operation");
        }

        if (valid && !violations.isEmpty()) {
            throw new IllegalArgumentException("valid decode result must not contain violations");
        }

        if (!valid && violations.isEmpty()) {
            throw new IllegalArgumentException("invalid decode result must contain violations");
        }

        if (!valid && !normalizedPayload.isEmpty()) {
            throw new IllegalArgumentException("invalid decode result must not expose normalized payload");
        }
    }

    public static TracingControlProtocolDecodeResult success(TracingControlProtocolOperation operation,
                                                             Map<String, Object> normalizedPayload) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(normalizedPayload, "normalizedPayload");

        return new TracingControlProtocolDecodeResult(true, Optional.of(operation), normalizedPayload, List.of());
    }

    public static TracingControlProtocolDecodeResult failure(TracingControlProtocolOperation operation,
                                                             List<TracingControlProtocolViolation> violations) {
        Objects.requireNonNull(violations, "violations");

        if (violations.isEmpty()) {
            throw new IllegalArgumentException("invalid decode result must contain violations");
        }

        return new TracingControlProtocolDecodeResult(false, Optional.ofNullable(operation), Map.of(), violations);
    }

    public static TracingControlProtocolDecodeResult failure(TracingControlProtocolOperation operation,
                                                             TracingControlProtocolViolation violation) {
        return failure(operation, List.of(violation));
    }
}
