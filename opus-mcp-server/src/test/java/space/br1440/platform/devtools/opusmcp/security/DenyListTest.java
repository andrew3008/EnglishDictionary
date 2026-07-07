package space.br1440.platform.devtools.opusmcp.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DenyListTest {

    private final DenyList denyList = new DenyList();

    @Test
    void detectsDotEnvReference() {
        assertThat(denyList.isSafe("please read .env for config")).isFalse();
        assertThat(denyList.isSafe("see .env.production")).isFalse();
    }

    @Test
    void detectsIdRsaReference() {
        assertThat(denyList.isSafe("copy ~/.ssh/id_rsa to server")).isFalse();
        assertThat(denyList.isSafe("id_ed25519 file")).isFalse();
    }

    @Test
    void detectsKeyAndCertFiles() {
        assertThat(denyList.isSafe("server.pem")).isFalse();
        assertThat(denyList.isSafe("keystore.p12")).isFalse();
        assertThat(denyList.isSafe("private.key")).isFalse();
    }

    @Test
    void detectsCredentialsAndSecretsAndKubeconfig() {
        assertThat(denyList.isSafe("credentials.json")).isFalse();
        assertThat(denyList.isSafe("secrets.yaml")).isFalse();
        assertThat(denyList.isSafe("my kubeconfig file")).isFalse();
        assertThat(denyList.isSafe("application-prod.yml")).isFalse();
        assertThat(denyList.isSafe(".ssh/config")).isFalse();
        assertThat(denyList.isSafe(".git/config")).isFalse();
    }

    @Test
    void allowsBenignText() {
        assertThat(denyList.isSafe("Generate a Java method that adds two integers")).isTrue();
        assertThat(denyList.isSafe("no repository context")).isTrue();
        assertThat(denyList.isSafe(null)).isTrue();
    }

    @Test
    void violationMessageIsSafe() {
        assertThat(denyList.findViolation("read .env now")).get()
                .asString().contains("sensitive file reference");
    }
}
