package space.br1440.platform.tracing.otel.extension.scrubbing;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.engine.RuleExecutionWrapper;

import java.time.Instant;
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
        ScrubbingSnapshot snapshot = ScrubbingSnapshot.fromRules(
                true,
                List.of(BuiltInSensitiveDataRules.resolve("password")),
                1,
                Instant.now(),
                "test");

        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.wrappers()).hasSize(1);
        assertThat(snapshot.wrappers().get(0).getRuleName()).isEqualTo("password");
    }

    @Test
    void enabled_false_passthrough_snapshot() {
        ScrubbingSnapshot snapshot = ScrubbingSnapshot.fromRules(
                false,
                List.of(BuiltInSensitiveDataRules.resolve("password")),
                2,
                Instant.now(),
                "test");

        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.wrappers()).isNotEmpty();
    }

    @Test
    void wrappers_list_is_immutable_copy() {
        List<RuleExecutionWrapper> mutable = new ArrayList<>(
                ScrubbingSnapshot.compileWrappers(List.of(BuiltInSensitiveDataRules.resolve("jwt"))));
        ScrubbingSnapshot snapshot = new ScrubbingSnapshot(true, mutable, 1, Instant.now(), "test");

        assertThat(snapshot.wrappers()).isNotSameAs(mutable);
        assertThatThrownBy(() -> snapshot.wrappers().add(snapshot.wrappers().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mutating_source_wrapper_list_does_not_affect_snapshot() {
        List<RuleExecutionWrapper> mutable = new ArrayList<>(
                ScrubbingSnapshot.compileWrappers(List.of(BuiltInSensitiveDataRules.resolve("email"))));
        ScrubbingSnapshot snapshot = new ScrubbingSnapshot(true, mutable, 1, Instant.now(), "test");
        mutable.clear();

        assertThat(snapshot.wrappers()).hasSize(1);
    }

    @Test
    void fromRules_rejects_null_rule_entries() {
        List<SensitiveDataRule> rules = new ArrayList<>();
        rules.add(BuiltInSensitiveDataRules.resolve("password"));
        rules.add(null);

        assertThatThrownBy(() -> ScrubbingSnapshot.fromRules(true, rules, 1, Instant.now(), "test"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("null entries");
    }

    @Test
    void invalid_regex_at_rule_construction_propagates_from_fromRules() {
        assertThatThrownBy(() -> ScrubbingSnapshot.fromRules(
                true, List.of(new InvalidRegexRule()), 1, Instant.now(), "test"))
                .isInstanceOf(java.util.regex.PatternSyntaxException.class);
    }

    private static final class InvalidRegexRule implements SensitiveDataRule {
        private final java.util.regex.Pattern pattern;

        InvalidRegexRule() {
            pattern = java.util.regex.Pattern.compile("([unclosed");
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
    void compileWrappers_preserves_rule_order_after_priority_sort() {
        SensitiveDataRule high = BuiltInSensitiveDataRules.resolve("password");
        SensitiveDataRule low = BuiltInSensitiveDataRules.resolve("email");
        List<RuleExecutionWrapper> wrappers = ScrubbingSnapshot.compileWrappers(List.of(low, high));

        assertThat(wrappers.get(0).getRuleName()).isEqualTo("password");
        assertThat(wrappers.get(1).getRuleName()).isEqualTo("email");
    }
}
