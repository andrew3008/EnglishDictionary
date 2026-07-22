package space.br1440.platform.tracing.core.control.protocol;

import java.util.List;

public record TracingControlDomainValidationResult(boolean valid, List<String> violations) {

    public TracingControlDomainValidationResult {
        violations = (violations == null) ? List.of() : List.copyOf(violations);
        if (valid && !violations.isEmpty()) {
            throw new IllegalArgumentException("valid domain result must not contain violations");
        }
        if (!valid && violations.isEmpty()) {
            throw new IllegalArgumentException("invalid domain result must contain violations");
        }
    }

    public static TracingControlDomainValidationResult success() {
        return new TracingControlDomainValidationResult(true, List.of());
    }

    public static TracingControlDomainValidationResult invalid(String violation) {
        return new TracingControlDomainValidationResult(false, List.of(violation));
    }
}
