package space.br1440.platform.tracing.otel.javaagent.scrubbing;


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
    void serviceLoader_находит_правило_с_test_classpath_через_autoservice() {
        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        ServiceLoader.load(SpanAttributeScrubbingRule.class, getClass().getClassLoader())
                .forEach(rules::add);

        boolean foundExample = rules.stream()
                .anyMatch(r -> r instanceof ExampleMerchantAccountRule);

        assertThat(foundExample)
                .as("Пример кастомного правила (ExampleMerchantAccountRule) должен быть загружен ServiceLoader-ом " +
                        "благодаря @AutoService генерации META-INF/services")
                .isTrue();
    }

    @Test
    void правила_корректно_сортируются_по_приоритету_независимо_от_порядка_загрузки() {
        SpanAttributeScrubbingRule highPriorityRule = new TestRule("high-priority", 5);
        SpanAttributeScrubbingRule mediumPriorityRule = new TestRule("medium-priority", 50);
        SpanAttributeScrubbingRule lowPriorityRule = new ExampleMerchantAccountRule(); // priority = 2000

        List<SpanAttributeScrubbingRule> unorderedList = new ArrayList<>(List.of(
                lowPriorityRule,
                highPriorityRule,
                mediumPriorityRule
        ));

        // ScrubbingSpanProcessor производит именно такую сортировку в конструкторе
        unorderedList.sort(Comparator.comparingInt(SpanAttributeScrubbingRule::priority));

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
