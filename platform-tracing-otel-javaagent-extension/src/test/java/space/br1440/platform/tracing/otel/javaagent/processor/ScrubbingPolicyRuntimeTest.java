package space.br1440.platform.tracing.otel.javaagent.processor;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.BuiltInSpanAttributeScrubbingRules;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime-политика scrubbing'а (Фаза 14): enable/disable, reload правил, last-known-good при
 * невалидном апдейте — без зависимости от построения реальных span'ов.
 */
class ScrubbingPolicyRuntimeTest {

    private static ScrubbingSpanProcessor processorWith(String... ruleNames) {
        List<SpanAttributeScrubbingRule> rules = new java.util.ArrayList<>();
        for (String name : ruleNames) {
            rules.add(BuiltInSpanAttributeScrubbingRules.resolve(name));
        }
        return new ScrubbingSpanProcessor(rules);
    }

    @Test
    void disable_не_меняет_правила_и_бампает_версию() {
        ScrubbingSpanProcessor processor = processorWith("password", "jwt");
        long versionBefore = processor.getPolicyVersion();
        long rulesBefore = processor.getRuleCount();

        boolean applied = processor.updateScrubbingPolicy(false, null);

        assertThat(applied).isTrue();
        assertThat(processor.isEnabled()).isFalse();
        assertThat(processor.getRuleCount()).isEqualTo(rulesBefore);
        assertThat(processor.getPolicyVersion()).isEqualTo(versionBefore + 1);
    }

    @Test
    void reload_правил_меняет_число_активных_правил() {
        ScrubbingSpanProcessor processor = processorWith("password");

        boolean applied = processor.updateScrubbingPolicy(true, List.of("password", "jwt", "email"));

        assertThat(applied).isTrue();
        assertThat(processor.isEnabled()).isTrue();
        assertThat(processor.getRuleCount()).isEqualTo(3);
    }

    @Test
    void превышение_лимита_правил_сохраняет_last_known_good() {
        ScrubbingSpanProcessor processor = processorWith("password");
        long rulesBefore = processor.getRuleCount();
        long versionBefore = processor.getPolicyVersion();

        boolean applied = processor.updateScrubbingPolicy(true, Collections.nCopies(201, "password"));

        assertThat(applied).isFalse();
        assertThat(processor.getRuleCount()).isEqualTo(rulesBefore);
        assertThat(processor.getPolicyVersion()).isEqualTo(versionBefore);
    }
}
