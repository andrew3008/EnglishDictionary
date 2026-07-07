package space.br1440.platform.tracing.api.control.protocol.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TracingControlProtocolValidationResult(boolean valid,
                                                     List<TracingControlProtocolViolation> violations,
                                                     Map<String, Object> normalizedPayload) {

    public TracingControlProtocolValidationResult {
        violations = (violations == null) ? List.of() : List.copyOf(violations);
        normalizedPayload = (normalizedPayload == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(normalizedPayload));
    }

    public static TracingControlProtocolValidationResult valid(Map<String, Object> normalizedPayload) {
        return new TracingControlProtocolValidationResult(true, List.of(), normalizedPayload);
    }

    public static TracingControlProtocolValidationResult invalid(List<TracingControlProtocolViolation> violations) {
        Objects.requireNonNull(violations, "violations");
        return new TracingControlProtocolValidationResult(false, violations, Map.of());
    }

    public static TracingControlProtocolValidationResult invalid(TracingControlProtocolViolation violation) {
        return invalid(List.of(violation));
    }
}
