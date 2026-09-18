package space.br1440.platform.tracing.otel.attributes.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.Nonnull;

import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolation;
import space.br1440.platform.tracing.api.attributes.validation.SpanAttributeViolationException;
import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SpanAttributeValidator {

    private static final String RULE_ATTRIBUTE_FORBIDDEN = "ATTR_FORBIDDEN";
    private static final String RULE_ATTRIBUTE_NOT_ALLOWED = "ATTR_NOT_ALLOWED";
    private static final String RULE_REQUIRED_ATTRIBUTE_MISSING = "REQUIRED_MISSING";
    private static final String RULE_REQUIRED_ANY_OF_MISSING = "REQUIRED_ANY_OF_MISSING";

    private final SpanAttributeValidationLevel level;
    private final SpanAttributeValidationMetrics metrics;
    private final Set<ViolationDedupKey> warnedOnce = ConcurrentHashMap.newKeySet();

    public SpanAttributeValidator(@Nonnull SpanAttributeValidationLevel level,
                                  @Nonnull SpanAttributeValidationMetrics metrics) {
        this.level = Objects.requireNonNull(level, "level must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public SpanAttributeValidator() {
        this(SpanAttributeValidationLevel.WARNING, SpanAttributeValidationMetrics.NOOP);
    }

    @Nonnull
    public SpanAttributeValidationLevel level() {
        return level;
    }

    @Nonnull
    public ValidatedAttributes validateAndNormalize(@Nonnull SpanCategory category,
                                                    @Nonnull Attributes accumulated,
                                                    @Nonnull String builderName) {
        if (level == SpanAttributeValidationLevel.DISABLED) {
            return new ValidatedAttributes(accumulated, List.of());
        }

        CategoryContract contract = CategoryContracts.of(category);
        List<SpanAttributeViolation> violations = collectViolations(category, accumulated, builderName, contract);

        if (level == SpanAttributeValidationLevel.ERROR && !violations.isEmpty()) {
            throw new SpanAttributeViolationException(violations.getFirst());
        }

        for (SpanAttributeViolation violation : violations) {
            metrics.violation(violation.ruleId(), violation.builder());
            warnOnce(violation);
        }

        AttributesBuilder normalized = accumulated.toBuilder();
        applySafeDefaults(normalized, category, accumulated);
        return new ValidatedAttributes(normalized.build(), violations);
    }

    private List<SpanAttributeViolation> collectViolations(SpanCategory category,
                                                           Attributes accumulated,
                                                           String builderName,
                                                           CategoryContract contract) {
        List<SpanAttributeViolation> violations = new ArrayList<>();
        Set<AttributeKey<?>> present = accumulated.asMap().keySet();

        for (AttributeKey<?> key : present) {
            if (contract.forbidden().contains(key)) {
                violations.add(forbiddenAttributeViolation(category, builderName, key));
            } else if (!contract.allowlist().contains(key)) {
                violations.add(notAllowedAttributeViolation(category, builderName, key));
            }
        }

        for (AttributeKey<?> required : contract.required()) {
            if (!present.contains(required)) {
                violations.add(requiredAttributeMissingViolation(category, builderName, required));
            }
        }

        for (Set<AttributeKey<?>> anyOf : contract.requiredAnyOf()) {
            if (anyOf.stream().noneMatch(present::contains)) {
                violations.add(requiredAnyOfMissingViolation(category, builderName, anyOf));
            }
        }

        return violations;
    }

    private static SpanAttributeViolation forbiddenAttributeViolation(SpanCategory category,
                                                                      String builderName,
                                                                      AttributeKey<?> key) {
        return new SpanAttributeViolation(RULE_ATTRIBUTE_FORBIDDEN, category, builderName,
                key.getKey(),
                "forbidden attribute '%s' for category %s".formatted(key.getKey(), category)
        );
    }

    private static SpanAttributeViolation notAllowedAttributeViolation(SpanCategory category,
                                                                       String builderName,
                                                                       AttributeKey<?> key) {
        return new SpanAttributeViolation(RULE_ATTRIBUTE_NOT_ALLOWED, category, builderName,
                key.getKey(),
                "attribute '%s' is not in the allowlist for category %s".formatted(key.getKey(), category)
        );
    }

    private static SpanAttributeViolation requiredAttributeMissingViolation(SpanCategory category,
                                                                            String builderName,
                                                                            AttributeKey<?> required) {
        return new SpanAttributeViolation(RULE_REQUIRED_ATTRIBUTE_MISSING, category, builderName,
                required.getKey(),
                "required attribute '%s' is missing".formatted(required.getKey())
        );
    }

    private static SpanAttributeViolation requiredAnyOfMissingViolation(SpanCategory category,
                                                                        String builderName,
                                                                        Set<AttributeKey<?>> anyOf) {
        String names = anyOf.stream()
                .map(AttributeKey::getKey)
                .sorted()
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        return new SpanAttributeViolation(RULE_REQUIRED_ANY_OF_MISSING, category, builderName,
                null,
                "at least one of %s must be present".formatted(names)
        );
    }

    private void applySafeDefaults(AttributesBuilder normalized, SpanCategory category, Attributes accumulated) {
        if (accumulated.get(SemconvKeys.PLATFORM_TYPE) == null) {
            normalized.put(SemconvKeys.PLATFORM_TYPE, category.value());
        }
    }

    private void warnOnce(SpanAttributeViolation violation) {
        ViolationDedupKey dedupKey = ViolationDedupKey.of(violation);
        if (warnedOnce.add(dedupKey)) {
            log.warn("span attribute violation [{}] builder={} category={}: {}",
                    violation.ruleId(), violation.builder(), violation.category(), violation.message());
        } else if (log.isDebugEnabled()) {
            log.debug("span attribute violation [{}] builder={} (repeat)", violation.ruleId(), violation.builder());
        }
    }

    private record ViolationDedupKey(String ruleId, String builder, String attributeKey) {

        static ViolationDedupKey of(SpanAttributeViolation violation) {
            return new ViolationDedupKey(
                    violation.ruleId(),
                    violation.builder(),
                    violation.attributeKey()
            );
        }
    }
}
