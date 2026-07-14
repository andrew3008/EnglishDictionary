package space.br1440.platform.tracing.core.semconv.policy;

import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import space.br1440.platform.tracing.api.semconv.SemconvKeys;
import space.br1440.platform.tracing.api.span.SpanCategory;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Проверяют структурную целостность реестра {@link CategoryContracts}
 * как единственного источника истины для {@link AttributePolicy}.
 */
class CategoryContractsTest {

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void каждаяКатегорияИмеетКонтракт(SpanCategory category) {
        assertThatCode(() -> CategoryContracts.of(category)).doesNotThrowAnyException();
        assertThat(CategoryContracts.of(category).category()).isEqualTo(category);
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void requiredВходитВAllowlist(SpanCategory category) {
        CategoryContract c = CategoryContracts.of(category);
        assertThat(c.allowlist())
                .as("required-атрибуты должны быть частью allowlist категории %s", category)
                .containsAll(c.required());
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void requiredAnyOfВходитВAllowlist(SpanCategory category) {
        CategoryContract c = CategoryContracts.of(category);
        for (Set<AttributeKey<?>> anyOf : c.requiredAnyOf()) {
            assertThat(c.allowlist())
                    .as("requiredAnyOf-группа должна быть частью allowlist категории %s", category)
                    .containsAll(anyOf);
        }
    }

    @ParameterizedTest
    @EnumSource(SpanCategory.class)
    void forbiddenНеПересекаетсяСAllowlist(SpanCategory category) {
        CategoryContract c = CategoryContracts.of(category);
        Set<AttributeKey<?>> intersection = new HashSet<>(c.allowlist());
        intersection.retainAll(c.forbidden());
        assertThat(intersection)
                .as("forbidden и allowlist не должны пересекаться у категории %s", category)
                .isEmpty();
    }

    @Test
    void реестрВозвращаетОдинИТотЖеЭкземпляр() {
        assertThat(CategoryContracts.of(SpanCategory.DATABASE))
                .isSameAs(CategoryContracts.of(SpanCategory.DATABASE));
    }

    @Test
    void platformTypeОбязателенВовсехКатегориях() {
        for (SpanCategory category : SpanCategory.values()) {
            assertThat(CategoryContracts.of(category).required())
                    .as("platform.trace.type обязателен в категории %s", category)
                    .contains(SemconvKeys.PLATFORM_TYPE);
        }
    }
}
