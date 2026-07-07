package space.br1440.platform.tracing.api.semconv;

import io.opentelemetry.api.common.AttributeKey;

import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.List;
import java.util.Set;

/**
 * Immutable semantic-контракт одной {@link SpanCategory}:
 * какие атрибуты разрешены, какие обязательны, какие запрещены.
 */
public record CategoryContract(SpanCategory category,
                               Set<AttributeKey<?>> allowlist,
                               Set<AttributeKey<?>> required,
                               List<Set<AttributeKey<?>>> requiredAnyOf,
                               Set<AttributeKey<?>> forbidden) {

    public CategoryContract {
        if (category == null) {
            throw new IllegalArgumentException("category не должен быть null");
        }

        allowlist = Set.copyOf(allowlist);
        required = Set.copyOf(required);
        requiredAnyOf = requiredAnyOf.stream().map(Set::copyOf).toList();
        forbidden = Set.copyOf(forbidden);

        if (!allowlist.containsAll(required)) {
            throw new IllegalArgumentException(category + ": required не является подмножеством allowlist");
        }

        for (Set<AttributeKey<?>> anyOf : requiredAnyOf) {
            if (anyOf.isEmpty()) {
                throw new IllegalArgumentException(category + ": пустая альтернатива в requiredAnyOf");
            }

            if (!allowlist.containsAll(anyOf)) {
                throw new IllegalArgumentException(category + ": альтернатива requiredAnyOf вне allowlist");
            }
        }

        for (AttributeKey<?> f : forbidden) {
            if (allowlist.contains(f)) {
                throw new IllegalArgumentException(category + ": forbidden пересекается с allowlist: " + f.getKey());
            }
        }
    }
}
