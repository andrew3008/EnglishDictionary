package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;

class HmacSha256HasherTest {

    private static final String RFC_4231_CASE_1 = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
    private static final String NON_ASCII = "f08337703354b824d76479982edf55129c718620dbbb837795c595c19994e3e2";

    @Test
    @DisplayName("RFC 4231 case 1 и обнуление переданного ключа")
    void rfc4231Case1AndOwnedKeyIsWiped() {
        // given
        byte[] ownedKey = "Jefe".getBytes(StandardCharsets.UTF_8);

        // when
        HmacSha256Hasher hasher = HmacSha256Hasher.from(ownedKey);
        String digest = hasher.hashHex("what do ya want for nothing?");

        // then
        assertThat(ownedKey).containsOnly((byte) 0);
        assertThat(digest).isEqualTo(RFC_4231_CASE_1);
        assertThat(digest).doesNotContainPattern("[A-F]");
        assertThat(hasher.hashHex("я")).isEqualTo(NON_ASCII);
    }

    @Test
    @DisplayName("RFC 4231 case 1 через MaskedSecret и фасад")
    void rfc4231Case1ThroughRuntime() {
        // given
        ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(), 1L, MaskedSecret.of("Jefe"), false);

        // when
        String digest = runtime.hmacHex("what do ya want for nothing?");

        // then
        assertThat(digest).isEqualTo(RFC_4231_CASE_1);
        assertThat(runtime.hmacHex("я")).isEqualTo(NON_ASCII);
    }
}
