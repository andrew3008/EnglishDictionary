package space.br1440.platform.tracing.otel.semconv.policy;

import io.opentelemetry.api.common.Attributes;
import space.br1440.platform.tracing.api.semconv.SemconvViolation;

import java.util.List;

public record ValidatedAttributes(Attributes attributes, List<SemconvViolation> violations) {

    public ValidatedAttributes {
        violations = List.copyOf(violations);
    }
}
