package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;


import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тест для верификации SPI-расширяемости и детерминированности приоритетов правил.
 */
class ServiceLoaderSpanAttributeScrubbingRuleTest {

    @Test
    void serviceLoader_finds_rule_from_test_classpath_via_autoservice() {
        // given
        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        // when
        ServiceLoader.load(SpanAttributeScrubbingRule.class, getClass().getClassLoader())
                .forEach(rules::add);

        // then
        boolean foundExample = rules.stream()
                .anyMatch(r -> r instanceof ExampleMerchantAccountRule);

        assertThat(foundExample)
                .as("Пример кастомного правила (ExampleMerchantAccountRule) должен быть загружен ServiceLoader-ом " +
                        "благодаря @AutoService генерации META-INF/services")
                .isTrue();
    }

    @Test
    void rules_are_sorted_by_priority_independently_of_load_order() {
        // given
        SpanAttributeScrubbingRule highPriorityRule = new TestRule("high-priority", 5);
        SpanAttributeScrubbingRule mediumPriorityRule = new TestRule("medium-priority", 50);
        SpanAttributeScrubbingRule lowPriorityRule = new ExampleMerchantAccountRule(); // priority = 2000

        List<SpanAttributeScrubbingRule> unorderedList = new ArrayList<>(List.of(
                lowPriorityRule,
                highPriorityRule,
                mediumPriorityRule
        ));

        // ScrubbingSpanProcessor производит именно такую сортировку в конструкторе
        // when
        unorderedList.sort(Comparator.comparingInt(SpanAttributeScrubbingRule::priority));

        // then
        assertThat(unorderedList).containsExactly(
                highPriorityRule,
                mediumPriorityRule,
                lowPriorityRule
        );
    }

    private record TestRule(String name, int priority) implements SpanAttributeScrubbingRule {
        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            return ScrubbingDecision.keep();
        }
    }
}
