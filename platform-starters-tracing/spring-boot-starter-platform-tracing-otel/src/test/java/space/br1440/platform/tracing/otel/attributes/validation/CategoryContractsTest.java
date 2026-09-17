package space.br1440.platform.tracing.otel.attributes.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.api.span.SpanCategory;
import space.br1440.platform.tracing.otel.semconv.SemconvKeys;

import io.opentelemetry.api.common.AttributeKey;

class CategoryContractsTest {

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void eachCategoryHasContract(SpanCategory category) {
        // given / when / then
        assertThatCode(() -> CategoryContracts.of(category)).doesNotThrowAnyException();
        assertThat(CategoryContracts.of(category).category()).isEqualTo(category);
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void requiredIsInAllowlist(SpanCategory category) {
        // given
        CategoryContract contract = CategoryContracts.of(category);

        // when / then
        assertThat(contract.allowlist())
                .as("required attributes must be part of the allowlist for category %s", category)
                .containsAll(contract.required());
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void requiredAnyOfIsInAllowlist(SpanCategory category) {
        // given
        CategoryContract contract = CategoryContracts.of(category);

        // when / then
        for (Set<AttributeKey<?>> anyOf : contract.requiredAnyOf()) {
            assertThat(contract.allowlist())
                    .as("requiredAnyOf group must be part of the allowlist for category %s", category)
                    .containsAll(anyOf);
        }
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void forbiddenDoesNotIntersectAllowlist(SpanCategory category) {
        // given
        CategoryContract contract = CategoryContracts.of(category);
        Set<AttributeKey<?>> intersection = new HashSet<>(contract.allowlist());
        intersection.retainAll(contract.forbidden());

        // when / then
        assertThat(intersection)
                .as("forbidden and allowlist must not intersect for category %s", category)
                .isEmpty();
    }

    @Test
    void registryReturnsSameInstance() {
        // given / when / then
        assertThat(CategoryContracts.of(SpanCategory.DATABASE))
                .isSameAs(CategoryContracts.of(SpanCategory.DATABASE));
    }

    @Test
    void platformTypeRequiredInAllCategories() {
        // given / when / then
        for (SpanCategory category : SpanCategory.values()) {
            assertThat(CategoryContracts.of(category).required())
                    .as("platform.trace.type is required in category %s", category)
                    .contains(SemconvKeys.PLATFORM_TYPE);
        }
    }
}
