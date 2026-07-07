package space.br1440.platform.devtools.opusmcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingTest {

    @Test
    void masksBearerToken() {
        String masked = Masking.mask("Authorization: Bearer abcdefghijklmnop");
        assertThat(masked).contains(Masking.REDACTED).doesNotContain("abcdefghijklmnop");
    }

    @Test
    void masksAssignments() {
        assertThat(Masking.mask("password=hunter2")).doesNotContain("hunter2");
        assertThat(Masking.mask("api_key=ABC123XYZ")).doesNotContain("ABC123XYZ");
    }

    @Test
    void masksPrivateKeyBlock() {
        String pem = "-----BEGIN PRIVATE KEY-----\nMIIBVAIBADANBg\n-----END PRIVATE KEY-----";
        String masked = Masking.mask(pem);
        assertThat(masked).contains(Masking.REDACTED).doesNotContain("MIIBVAIBADANBg");
    }

    @Test
    void masksKnownLiteralSecret() {
        String masked = Masking.maskSecret("calling with key sk-live-12345 here", "sk-live-12345");
        assertThat(masked).doesNotContain("sk-live-12345").contains(Masking.REDACTED);
    }

    @Test
    void nullSafe() {
        assertThat(Masking.mask(null)).isNull();
        assertThat(Masking.maskSecret(null, "x")).isNull();
    }
}
