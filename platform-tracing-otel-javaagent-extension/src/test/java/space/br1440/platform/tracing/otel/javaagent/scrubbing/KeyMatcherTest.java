package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нормализация имён ключей: разные стили записи одного логического ключа должны совпадать.
 */
class KeyMatcherTest {

    @Test
    void разные_стили_записи_client_secret_нормализуются_одинаково() {
        String expected = KeyMatcher.normalize("client-secret");
        assertThat(KeyMatcher.normalize("client_secret")).isEqualTo(expected);
        assertThat(KeyMatcher.normalize("client.secret")).isEqualTo(expected);
        assertThat(KeyMatcher.normalize("CLIENT_SECRET")).isEqualTo(expected);
        assertThat(KeyMatcher.normalize("clientSecret")).isEqualTo(expected);
        assertThat(expected).isEqualTo("clientsecret");
    }

    @Test
    void containsAny_находит_токен_в_нормализованном_ключе() {
        assertThat(KeyMatcher.containsAny(KeyMatcher.normalize("http.request.header.authorization"),
                "authorization")).isTrue();
        assertThat(KeyMatcher.containsAny(KeyMatcher.normalize("user.name"), "secret", "password"))
                .isFalse();
    }

    @Test
    void пустой_или_null_ключ_не_матчится() {
        assertThat(KeyMatcher.normalize(null)).isEmpty();
        assertThat(KeyMatcher.containsAny("", "secret")).isFalse();
    }
}
