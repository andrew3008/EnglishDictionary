package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

class StableScrubbingRuleTest {

    @Test
    void external_metadata_is_captured_once() {
        // given
        AtomicInteger nameCalls = new AtomicInteger();
        AtomicInteger priorityCalls = new AtomicInteger();
        AtomicInteger criticalCalls = new AtomicInteger();
        SpanAttributeScrubbingRule delegate = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() {
                nameCalls.incrementAndGet();
                return "external-rule";
            }
            @Override public int priority() {
                priorityCalls.incrementAndGet();
                return 950;
            }
            @Override public boolean critical() {
                criticalCalls.incrementAndGet();
                return false;
            }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };

        // when
        StableScrubbingRule stable = StableScrubbingRule.captureExternal(delegate);

        // then
        assertThat(stable.name()).isEqualTo("external-rule");
        assertThat(stable.name()).isEqualTo("external-rule");
        assertThat(stable.priority()).isEqualTo(950);
        assertThat(stable.priority()).isEqualTo(950);
        assertThat(stable.critical()).isFalse();
        assertThat(stable.critical()).isFalse();
        assertThat(stable.implementationClass()).isEqualTo(delegate.getClass());
        assertThat(nameCalls).hasValue(1);
        assertThat(priorityCalls).hasValue(1);
        assertThat(criticalCalls).hasValue(1);
    }

    @Test
    void external_rule_cannot_claim_platform_critical_ownership() {
        // given
        SpanAttributeScrubbingRule delegate = new SpanAttributeScrubbingRule() {
            @Nonnull
            @Override public String name() { return "external-critical"; }
            @Override public boolean critical() { return true; }
            @Nonnull
            @Override public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
                return ScrubbingDecision.keep();
            }
        };

        // when/then
        assertThatThrownBy(() -> StableScrubbingRule.captureExternal(delegate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External scrubbing rule must not be critical");
    }
}
