package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeyMatcherTest {

    @Test
    void different_client_secret_spellings_normalize_identically() {
        // given
        String expected = KeyMatcher.normalize("client-secret");
        // when/then
        assertThat(KeyMatcher.normalize("client_secret")).isEqualTo(expected);
        assertThat(KeyMatcher.normalize("client.secret")).isEqualTo(expected);
        assertThat(KeyMatcher.normalize("CLIENT_SECRET")).isEqualTo(expected);
        assertThat(KeyMatcher.normalize("clientSecret")).isEqualTo(expected);
        assertThat(expected).isEqualTo("clientsecret");
    }

    @Test
    void containsAny_finds_token_in_normalized_key() {
        // when/then
        assertThat(KeyMatcher.containsAny(KeyMatcher.normalize("http.request.header.authorization"),
                "authorization")).isTrue();
        assertThat(KeyMatcher.containsAny(KeyMatcher.normalize("user.name"), "secret", "password"))
                .isFalse();
    }

    @Test
    void empty_or_null_key_does_not_match() {
        // when/then
        assertThat(KeyMatcher.normalize(null)).isEmpty();
        assertThat(KeyMatcher.containsAny("", "secret")).isFalse();
    }
}
