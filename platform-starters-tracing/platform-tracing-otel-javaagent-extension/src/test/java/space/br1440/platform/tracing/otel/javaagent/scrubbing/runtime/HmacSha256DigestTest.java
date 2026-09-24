package space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;

class HmacSha256DigestTest {

    private static final String RFC_4231_CASE_2 = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
    private static final String NON_ASCII = "f08337703354b824d76479982edf55129c718620dbbb837795c595c19994e3e2";

    @Test
    @DisplayName("RFC 4231 case 2 и обнуление переданного ключа")
    void rfc4231Case2AndOwnedKeyIsWiped() {
        // given
        byte[] ownedKey = "Jefe".getBytes(StandardCharsets.UTF_8);

        // when
        HmacSha256Digest digest = HmacSha256Digest.from(ownedKey);
        String mac = digest.hashHex("what do ya want for nothing?");

        // then
        assertThat(ownedKey).containsOnly((byte) 0);
        assertThat(mac).isEqualTo(RFC_4231_CASE_2);
        assertThat(mac).doesNotContainPattern("[A-F]");
        assertThat(digest.hashHex("я")).isEqualTo(NON_ASCII);
    }

    @Test
    @DisplayName("RFC 4231 case 2 через MaskedSecret и фасад")
    void rfc4231Case2ThroughRuntime() {
        // given
        ScrubbingRuntime runtime = ScrubbingRuntime.create(
                List.of(), 1L, MaskedSecret.of("Jefe"), false);

        // when
        String mac = runtime.hmacHex("what do ya want for nothing?");

        // then
        assertThat(mac).isEqualTo(RFC_4231_CASE_2);
        assertThat(runtime.hmacHex("я")).isEqualTo(NON_ASCII);
    }

    @Test
    @DisplayName("повтор того же сообщения после другого даёт прежний digest")
    void repeatedMessageAfterAnotherMatchesTheFirstDigest() {
        // given
        HmacSha256Digest digest = HmacSha256Digest.from("Jefe".getBytes(StandardCharsets.UTF_8));

        // when
        String first = digest.hashHex("what do ya want for nothing?");
        String other = digest.hashHex("я");
        String repeated = digest.hashHex("what do ya want for nothing?");

        // then
        assertThat(other).isEqualTo(NON_ASCII);
        assertThat(repeated).isEqualTo(first).isEqualTo(RFC_4231_CASE_2);
    }
}
