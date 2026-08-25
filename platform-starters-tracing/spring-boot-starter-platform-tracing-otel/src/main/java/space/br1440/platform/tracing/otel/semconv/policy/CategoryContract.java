package space.br1440.platform.tracing.otel.semconv.policy;

import io.opentelemetry.api.common.AttributeKey;

import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.List;
import java.util.Set;

/**
 * Immutable semantic-контракт одной {@link SpanCategory}:
 * какие атрибуты разрешены, какие обязательны, какие запрещены.
 * <p>
 * Внутренний тип: используется только {@link CategoryContracts} и {@link AttributePolicy}.
 * Не является частью публичного API платформы.
 */
record CategoryContract(SpanCategory category,
                        Set<AttributeKey<?>> allowlist,
                        Set<AttributeKey<?>> required,
                        List<Set<AttributeKey<?>>> requiredAnyOf,
                        Set<AttributeKey<?>> forbidden) {

    CategoryContract {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }

        allowlist = Set.copyOf(allowlist);
        required = Set.copyOf(required);
        requiredAnyOf = requiredAnyOf.stream().map(Set::copyOf).toList();
        forbidden = Set.copyOf(forbidden);

        if (!allowlist.containsAll(required)) {
            throw new IllegalArgumentException(category + ": required is not a subset of allowlist");
        }

        for (Set<AttributeKey<?>> anyOf : requiredAnyOf) {
            if (anyOf.isEmpty()) {
                throw new IllegalArgumentException(category + ": empty alternative in requiredAnyOf");
            }

            if (!allowlist.containsAll(anyOf)) {
                throw new IllegalArgumentException(category + ": requiredAnyOf alternative is outside allowlist");
            }
        }

        for (AttributeKey<?> f : forbidden) {
            if (allowlist.contains(f)) {
                throw new IllegalArgumentException(category + ": forbidden intersects allowlist: " + f.getKey());
            }
        }
    }
}
