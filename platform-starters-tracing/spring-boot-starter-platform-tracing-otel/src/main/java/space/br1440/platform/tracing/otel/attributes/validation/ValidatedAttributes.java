package space.br1440.platform.tracing.otel.attributes.validation;

import java.util.List;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolation;

import io.opentelemetry.api.common.Attributes;

public record ValidatedAttributes(Attributes attributes, List<SpanAttributeViolation> violations) {

    public ValidatedAttributes {
        violations = List.copyOf(violations);
    }
}
