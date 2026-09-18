package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-7A: immutable compiled scrubbing snapshot foundation.
 */
class ScrubbingSnapshotTest {

    @Test
    void fromRules_compiles_wrappers_at_build_time() {
        // when
        ScrubbingSnapshot snapshot = ScrubbingSnapshot.fromRules(
                true,
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                1,
                "test");

        // then
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.executableRules()).hasSize(1);
        assertThat(snapshot.executableRules().getFirst().getRuleName()).isEqualTo("password");
    }

    @Test
    void enabled_false_passthrough_snapshot() {
        // when
        ScrubbingSnapshot snapshot = ScrubbingSnapshot.fromRules(
                false,
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                2,
                "test");

        // then
        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.executableRules()).isNotEmpty();
    }

    @Test
    void executableRules_list_is_immutable_copy() {
        // given
        List<ExecutableRule> mutable = new ArrayList<>(
                ScrubbingSnapshot.toExecutableRules(List.of(BuiltInSpanAttributeScrubbingRules.resolve("jwt"))));
        ScrubbingSnapshot snapshot = new ScrubbingSnapshot(true, mutable, 1, "test");

        // then
        assertThat(snapshot.executableRules()).isNotSameAs(mutable);
        assertThatThrownBy(() -> snapshot.executableRules().add(snapshot.executableRules().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutating_source_executableRules_list_does_not_affect_snapshot() {
        // given
        List<ExecutableRule> mutable = new ArrayList<>(
                ScrubbingSnapshot.toExecutableRules(List.of(BuiltInSpanAttributeScrubbingRules.resolve("email"))));
        ScrubbingSnapshot snapshot = new ScrubbingSnapshot(true, mutable, 1, "test");
        // when
        mutable.clear();

        // then
        assertThat(snapshot.executableRules()).hasSize(1);
    }

    @Test
    void fromRules_rejects_null_rule_entries() {
        // given
        List<SpanAttributeScrubbingRule> rules = new ArrayList<>();
        rules.add(BuiltInSpanAttributeScrubbingRules.resolve("password"));
        rules.add(null);

        // when/then
        assertThatThrownBy(() -> ScrubbingSnapshot.fromRules(true, rules, 1, "test"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("null entries");
    }

    @Test
    void invalid_regex_at_rule_construction_propagates_from_fromRules() {
        // when/then
        assertThatThrownBy(() -> ScrubbingSnapshot.fromRules(
                true, List.of(new InvalidRegexRule()), 1, "test"))
                .isInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    private static final class InvalidRegexRule implements SpanAttributeScrubbingRule {
        private final java.util.regex.Pattern pattern;

        InvalidRegexRule() {
            pattern = java.util.regex.Pattern.compile(/*language=TEXT*/ "([unclosed");
        }

        @Nonnull
        @Override
        public String name() {
            return "bad-regex";
        }

        @Override
        public int priority() {
            return 500;
        }

        @Nonnull
        @Override
        public space.br1440.platform.tracing.api.spi.ScrubbingDecision evaluate(@Nonnull String key, Object value) {
            return pattern.matcher(String.valueOf(value)).find()
                    ? space.br1440.platform.tracing.api.spi.ScrubbingDecision.drop("bad-regex")
                    : space.br1440.platform.tracing.api.spi.ScrubbingDecision.keep();
        }
    }

    @Test
    void toExecutableRules_preserves_rule_order_after_priority_sort() {
        // given
        SpanAttributeScrubbingRule high = BuiltInSpanAttributeScrubbingRules.resolve("password");
        SpanAttributeScrubbingRule low = BuiltInSpanAttributeScrubbingRules.resolve("email");
        // when
        List<ExecutableRule> wrappers = ScrubbingSnapshot.toExecutableRules(List.of(low, high));

        // then
        assertThat(wrappers.get(0).getRuleName()).isEqualTo("password");
        assertThat(wrappers.get(1).getRuleName()).isEqualTo("email");
    }
}
