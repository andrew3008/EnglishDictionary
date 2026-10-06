package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ControlledAgentPinTest {

    private static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void valid_pin_exposes_version_sha256_and_the_conventional_agent_file_name() {
        // given
        // when
        ControlledAgentPin pin = new ControlledAgentPin("1.2.3", SHA256);

        // then
        assertThat(pin.version()).isEqualTo("1.2.3");
        assertThat(pin.sha256()).isEqualTo(SHA256);
        assertThat(pin.agentFileName()).isEqualTo("platform-tracing-javaagent-1.2.3.jar");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", " 1.2.3", "1.2.3 "})
    void version_must_be_a_trimmed_non_blank_value(String version) {
        // given
        // when / then
        assertThatThrownBy(() -> new ControlledAgentPin(version, SHA256))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("agent.version");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    })
    void sha256_must_be_a_lowercase_64_character_hex_value(String sha256) {
        // given
        // when / then
        assertThatThrownBy(() -> new ControlledAgentPin("1.2.3", sha256))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("agent.sha256");
    }

    @Test
    void load_reads_the_pin_packaged_in_the_plugin_release() {
        // given
        // when
        ControlledAgentPin pin = ControlledAgentPin.load();

        // then
        assertThat(pin.version()).isEqualTo(System.getProperty("platformTracing.pluginVersion"));
        assertThat(pin.sha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void packaged_pin_resource_bytes_are_utf8_without_cr_and_end_with_lf() throws IOException {
        // given
        byte[] bytes;
        try (InputStream stream = Objects.requireNonNull(
                ControlledAgentPin.class.getResourceAsStream(
                        "/META-INF/platform-tracing/controlled-agent-pin.properties"),
                "Controlled agent pin resource is missing")) {
            bytes = stream.readAllBytes();
        }

        ControlledAgentPin pin = ControlledAgentPin.load();
        String expected = "agent.sha256=%s\nagent.version=%s\n".formatted(pin.sha256(), pin.version());

        // when
        String text = new String(bytes, StandardCharsets.UTF_8);

        // then
        assertThat(bytes).doesNotContain((byte) 0x0D);
        assertThat(bytes[bytes.length - 1]).isEqualTo((byte) 0x0A);
        assertThat(text).isEqualTo(expected);
    }
}
