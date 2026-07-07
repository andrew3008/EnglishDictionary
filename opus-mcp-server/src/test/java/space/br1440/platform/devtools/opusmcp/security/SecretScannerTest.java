package space.br1440.platform.devtools.opusmcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretScannerTest {

    private final SecretScanner scanner = new SecretScanner();

    @Test
    void detectsPrivateKeyBlock() {
        String text = "here is my key -----BEGIN RSA PRIVATE KEY----- abcd";
        assertThat(scanner.isSafe(text)).isFalse();
        assertThat(scanner.findViolation(text)).get()
                .asString()
                .contains("private key")
                .doesNotContain("abcd");
    }

    @Test
    void detectsOpensshPrivateKey() {
        assertThat(scanner.isSafe("-----BEGIN OPENSSH PRIVATE KEY-----")).isFalse();
    }

    @Test
    void detectsBearerToken() {
        assertThat(scanner.isSafe("Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123"))
                .isFalse();
    }

    @Test
    void detectsAwsAccessKeyId() {
        assertThat(scanner.isSafe("key AKIAIOSFODNN7EXAMPLE here")).isFalse();
        assertThat(scanner.isSafe("aws_secret_access_key = something")).isFalse();
    }

    @Test
    void detectsSecretAssignments() {
        assertThat(scanner.isSafe("password=super-secret")).isFalse();
        assertThat(scanner.isSafe("api_key=AbC123")).isFalse();
        assertThat(scanner.isSafe("client_secret: zzz")).isFalse();
    }

    @Test
    void violationMessageNeverEchoesSecret() {
        assertThat(scanner.findViolation("password=hunter2very")).get()
                .asString()
                .doesNotContain("hunter2very");
    }

    @Test
    void allowsBenignText() {
        assertThat(scanner.isSafe("Generate a Java method that adds two integers")).isTrue();
        assertThat(scanner.isSafe("Java 21, no external libraries")).isTrue();
        assertThat(scanner.isSafe(null)).isTrue();
    }
}
