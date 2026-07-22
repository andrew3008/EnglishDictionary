package space.br1440.platform.tracing.otel.javaagent.scrubbing.policy;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.BuiltInSpanAttributeScrubbingRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-9D: characterizes current pure scrubbing rule-name resolution behavior before core extraction.
 */
class ScrubbingRuleResolutionTest {

    @Test
    void known_names_resolve_to_canonical_config_names() {
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", "JWT", "email"});

        assertThat(result.resolvedConfigNames()).containsExactly("password", "jwt", "email");
        assertThat(result.skippedUnknownNames()).isEmpty();
    }

    @Test
    void unknown_names_are_skipped() {
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", "not-a-real-rule", "jwt"});

        assertThat(result.resolvedConfigNames()).containsExactly("password", "jwt");
        assertThat(result.skippedUnknownNames()).containsExactly("not-a-real-rule");
    }

    @Test
    void null_rule_name_rejected() {
        assertThatThrownBy(() -> ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", null}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void validateRuleNames_null_array_allowed() {
        ScrubbingRuleResolution.validateRuleNames(null);
    }

    @Test
    void validateRuleNames_null_entry_rejected() {
        assertThatThrownBy(() -> ScrubbingRuleResolution.validateRuleNames(
                new String[]{"password", null}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_rule_names_produces_empty_resolution() {
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(new String[0]);

        assertThat(result.resolvedConfigNames()).isEmpty();
        assertThat(result.skippedUnknownNames()).isEmpty();
    }

    @Test
    void order_is_preserved() {
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"email", "password", "jwt"});

        assertThat(result.resolvedConfigNames()).containsExactly("email", "password", "jwt");
    }

    @Test
    void duplicates_are_preserved() {
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password", "password", "jwt"});

        assertThat(result.resolvedConfigNames()).containsExactly("password", "password", "jwt");
    }

    @Test
    void rule_count_cap_enforced_at_validation() {
        String[] names = new String[ScrubbingRuleResolution.MAX_RULES + 1];
        java.util.Arrays.fill(names, "password");

        assertThatThrownBy(() -> ScrubbingRuleResolution.validateRuleNames(names))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many scrubbing rules");
    }

    @Test
    void lookup_matches_resolve_registry() {
        assertThat(BuiltInSpanAttributeScrubbingRules.lookup("EMAIL")).isEqualTo(BuiltInSpanAttributeScrubbingRules.EMAIL);
        assertThat(BuiltInSpanAttributeScrubbingRules.lookup("unknown")).isNull();
        assertThat(BuiltInSpanAttributeScrubbingRules.lookup(null)).isNull();
    }

    @Test
    void resolved_names_are_immutable_copies() {
        ScrubbingRuleResolutionResult result = ScrubbingRuleResolution.resolveRuleNames(
                new String[]{"password"});

        assertThatThrownBy(() -> result.resolvedConfigNames().add("jwt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
