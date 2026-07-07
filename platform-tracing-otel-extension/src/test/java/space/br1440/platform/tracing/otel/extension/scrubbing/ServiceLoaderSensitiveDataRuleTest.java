package space.br1440.platform.tracing.otel.extension.scrubbing;


import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тест для верификации SPI-расширяемости и детерминированности приоритетов правил.
 */
class ServiceLoaderSensitiveDataRuleTest {

    @Test
    void serviceLoader_находит_правило_с_test_classpath_через_autoservice() {
        List<SensitiveDataRule> rules = new ArrayList<>();
        ServiceLoader.load(SensitiveDataRule.class, getClass().getClassLoader())
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
        SensitiveDataRule highPriorityRule = new TestRule("high-priority", 5);
        SensitiveDataRule mediumPriorityRule = new TestRule("medium-priority", 50);
        SensitiveDataRule lowPriorityRule = new ExampleMerchantAccountRule(); // priority = 2000

        List<SensitiveDataRule> unorderedList = new ArrayList<>(List.of(
                lowPriorityRule,
                highPriorityRule,
                mediumPriorityRule
        ));

        // ScrubbingSpanProcessor производит именно такую сортировку в конструкторе
        unorderedList.sort(Comparator.comparingInt(SensitiveDataRule::priority));

        assertThat(unorderedList).containsExactly(
                highPriorityRule,
                mediumPriorityRule,
                lowPriorityRule
        );
    }

    private record TestRule(String name, int priority) implements SensitiveDataRule {
        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            return ScrubbingDecision.keep();
        }
    }
}
