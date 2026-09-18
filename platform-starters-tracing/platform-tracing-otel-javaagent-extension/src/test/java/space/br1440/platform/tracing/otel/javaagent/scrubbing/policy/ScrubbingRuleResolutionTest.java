package space.br1440.platform.tracing.otel.javaagent.scrubbing.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

class ScrubbingRuleResolutionTest {

    @Test
    void known_names_resolve_to_canonical_config_names() {
        // when
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", "JWT", "email"});

        // then
        assertThat(result.resolvedConfigNames()).containsExactly("password", "jwt", "email");
        assertThat(result.skippedUnknownNames()).isEmpty();
    }

    @Test
    void unknown_names_are_skipped() {
        // when
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", "not-a-real-rule", "jwt"});

        // then
        assertThat(result.resolvedConfigNames()).containsExactly("password", "jwt");
        assertThat(result.skippedUnknownNames()).containsExactly("not-a-real-rule");
    }

    @Test
    void null_rule_name_rejected() {
        // when/then
        assertThatThrownBy(() -> ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", null}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void validateRuleNames_null_array_allowed() {
        // given/when
        ScrubbingRuleResolution.validateRuleNames(null);
    }

    @Test
    void validateRuleNames_null_entry_rejected() {
        // when/then
        assertThatThrownBy(() -> ScrubbingRuleResolution.validateRuleNames(
                new String[]{"password", null}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_rule_names_produces_empty_resolution() {
        // when
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(new String[0]);

        // then
        assertThat(result.resolvedConfigNames()).isEmpty();
        assertThat(result.skippedUnknownNames()).isEmpty();
    }

    @Test
    void order_is_preserved() {
        // when
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"email", "password", "jwt"});

        // then
        assertThat(result.resolvedConfigNames()).containsExactly("email", "password", "jwt");
    }

    @Test
    void duplicates_are_preserved() {
        // when
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", "password", "jwt"});

        // then
        assertThat(result.resolvedConfigNames()).containsExactly("password", "password", "jwt");
    }

    @Test
    void rule_count_cap_enforced_at_validation() {
        // given
        String[] names = new String[ScrubbingRuleResolution.MAX_RULES + 1];
        java.util.Arrays.fill(names, "password");

        // when/then
        assertThatThrownBy(() -> ScrubbingRuleResolution.validateRuleNames(names))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many scrubbing rules");
    }

    @Test
    void lookup_matches_resolve_registry() {
        // when/then
        assertThat(BuiltInSpanAttributeScrubbingRules.lookup("EMAIL")).isEqualTo(BuiltInSpanAttributeScrubbingRules.EMAIL);
        assertThat(BuiltInSpanAttributeScrubbingRules.lookup("unknown")).isNull();
        assertThat(BuiltInSpanAttributeScrubbingRules.lookup(null)).isNull();
    }

    @Test
    void resolved_names_are_immutable_copies() {
        // given
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password"});

        // when/then
        assertThatThrownBy(() -> result.resolvedConfigNames().add("jwt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
