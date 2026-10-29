package space.br1440.platform.tracing.otel.javaagent.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BaggageKeyPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"tenant_class", "Tenant_Class", "TENANT_CLASS"})
    void allows_keys_independently_of_case(String key) {
        // given
        BaggageKeyPolicy policy = new BaggageKeyPolicy(Set.of("Tenant_Class"), List.of());

        // when
        boolean allowed = policy.allows(key);

        // then
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"access_token", "ACCESS_TOKEN", "Token_suffix", "prefix_token_suffix"})
    void deny_pattern_matches_substrings_and_overrides_allowlist(String key) {
        // given
        BaggageKeyPolicy policy = new BaggageKeyPolicy(Set.of(key),
                List.of(Pattern.compile("TOKEN", Pattern.CASE_INSENSITIVE)));

        // when
        boolean allowed = policy.allows(key);

        // then
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "unlisted", " tenant_class "})
    void rejects_null_and_unlisted_keys(String key) {
        // given
        BaggageKeyPolicy policy = new BaggageKeyPolicy(Set.of("tenant_class"), List.of());

        // when
        boolean allowed = policy.allows(key);

        // then
        assertThat(allowed).isFalse();
    }

    @Test
    void keeps_an_immutable_snapshot_of_allowlist_and_deny_patterns() {
        // given
        Set<String> allowedKeys = new HashSet<>(Set.of("tenant_class", "access_token"));
        List<Pattern> denyPatterns = new ArrayList<>(List.of(Pattern.compile("token")));
        BaggageKeyPolicy policy = new BaggageKeyPolicy(allowedKeys, denyPatterns);

        // when
        allowedKeys.clear();
        allowedKeys.add("unlisted");
        denyPatterns.clear();

        // then
        assertThat(policy.allows("tenant_class")).isTrue();
        assertThat(policy.allows("access_token")).isFalse();
        assertThat(policy.allows("unlisted")).isFalse();
        assertThat(policy.isEmpty()).isFalse();
    }

    @Test
    void empty_allowlist_rejects_every_key() {
        // given
        BaggageKeyPolicy policy = new BaggageKeyPolicy(Set.of(), List.of());

        // when
        boolean allowed = policy.allows("tenant_class");

        // then
        assertThat(allowed).isFalse();
        assertThat(policy.isEmpty()).isTrue();
    }
}
