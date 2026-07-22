package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionWrapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-9D: ScrubbingPolicyUpdate buildNext parity after pure rule resolution extraction.
 */
class ScrubbingPolicyUpdateTest {

    @Test
    void buildNext_nullRuleNames_keepsCompiledWrappers() {
        ScrubbingSnapshot previous = ScrubbingSnapshot.fromRules(
                true,
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                3,
                Instant.EPOCH,
                "startup");
        List<RuleExecutionWrapper> wrappersBefore = previous.wrappers();

        ScrubbingSnapshot next = ScrubbingPolicyUpdate.buildNext(previous, false, null, "toggle-only");

        assertThat(next.enabled()).isFalse();
        assertThat(next.version()).isEqualTo(4);
        assertThat(next.wrappers()).isSameAs(wrappersBefore);
        assertThat(next.source()).isEqualTo("toggle-only");
    }

    @Test
    void buildNext_emptyRuleNames_producesEmptyWrappers() {
        ScrubbingSnapshot previous = ScrubbingSnapshot.fromRules(
                true,
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                1,
                Instant.now(),
                "startup");

        ScrubbingSnapshot next = ScrubbingPolicyUpdate.buildNext(previous, true, new String[0], "empty");

        assertThat(next.wrappers()).isEmpty();
        assertThat(next.version()).isEqualTo(2);
    }

    @Test
    void buildNext_unknownNames_skipped() {
        ScrubbingSnapshot previous = ScrubbingSnapshot.fromRules(
                true,
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                1,
                Instant.now(),
                "startup");

        ScrubbingSnapshot next = ScrubbingPolicyUpdate.buildNext(
                previous, true, new String[]{"not-a-real-rule"}, "unknown");

        assertThat(next.wrappers()).isEmpty();
    }

    @Test
    void resolveRules_matches_direct_builtin_resolution() {
        List<space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule> resolved =
                ScrubbingPolicyUpdate.resolveRules(new String[]{"password", "jwt", "unknown"});

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).name()).isEqualTo("password");
        assertThat(resolved.get(1).name()).isEqualTo("jwt");
    }
}
