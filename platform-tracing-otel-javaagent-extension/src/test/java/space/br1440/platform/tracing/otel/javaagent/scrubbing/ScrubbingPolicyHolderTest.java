package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-7A: agent-side scrubbing policy holder (CAS + last-known-good).
 */
class ScrubbingPolicyHolderTest {

    @Test
    void current_returns_initial_snapshot() {
        ScrubbingSnapshot initial = ScrubbingSnapshot.fromRules(
                true, List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")), 1, Instant.now(), "startup");
        ScrubbingPolicyHolder holder = new ScrubbingPolicyHolder(initial);

        assertThat(holder.current()).isSameAs(initial);
        assertThat(holder.version()).isEqualTo(1);
    }

    @Test
    void tryUpdate_applies_valid_snapshot_and_bumps_version() {
        ScrubbingPolicyHolder holder = new ScrubbingPolicyHolder(
                ScrubbingSnapshot.fromRules(true, List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                        1, Instant.now(), "startup"));

        boolean applied = holder.tryApplyPolicyUpdate(
                false,
                new String[]{"jwt"},
                "test-update");

        assertThat(applied).isTrue();
        assertThat(holder.version()).isEqualTo(2);
        assertThat(holder.current().enabled()).isFalse();
        assertThat(holder.current().wrappers()).hasSize(1);
        assertThat(holder.current().wrappers().get(0).getRuleName()).isEqualTo("jwt");
    }

    @Test
    void tryUpdate_rejects_invalid_and_keeps_last_known_good() {
        ScrubbingPolicyHolder holder = new ScrubbingPolicyHolder(
                ScrubbingSnapshot.fromRules(true, List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                        3, Instant.now(), "startup"));
        ScrubbingSnapshot before = holder.current();

        boolean applied = holder.tryUpdate(prev -> {
            throw new IllegalArgumentException("invalid scrubbing update");
        });

        assertThat(applied).isFalse();
        assertThat(holder.current()).isSameAs(before);
        assertThat(holder.version()).isEqualTo(3);
    }

    @Test
    void tryApplyPolicyUpdate_nullRuleNames_togglesEnabledOnly() {
        ScrubbingPolicyHolder holder = new ScrubbingPolicyHolder(
                ScrubbingSnapshot.fromRules(true, List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")),
                        5, Instant.now(), "startup"));
        List<space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.RuleExecutionWrapper> wrappersBefore =
                holder.current().wrappers();

        boolean applied = holder.tryApplyPolicyUpdate(false, null, "toggle");

        assertThat(applied).isTrue();
        assertThat(holder.version()).isEqualTo(6);
        assertThat(holder.current().enabled()).isFalse();
        assertThat(holder.current().wrappers()).isSameAs(wrappersBefore);
    }
}
