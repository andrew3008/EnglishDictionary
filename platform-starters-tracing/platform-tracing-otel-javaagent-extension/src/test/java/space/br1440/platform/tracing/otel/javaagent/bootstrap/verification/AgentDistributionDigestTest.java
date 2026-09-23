package space.br1440.platform.tracing.otel.javaagent.bootstrap.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentDistributionDigestTest {

    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir
    Path temporaryDirectory;

    @Test
    void empty_file_has_the_known_sha256() throws IOException {
        // given
        Path empty = temporaryDirectory.resolve("empty.bin");
        Files.write(empty, new byte[0]);

        // when
        AgentDistributionDigest digest = AgentDistributionDigest.of(empty);

        // then
        assertThat(digest.hex()).isEqualTo(EMPTY_SHA256);
    }

    @Test
    void parse_accepts_uppercase_and_equals_the_computed_digest() throws IOException {
        // given
        Path file = temporaryDirectory.resolve("payload.bin");
        Files.writeString(file, "agent");
        AgentDistributionDigest computed = AgentDistributionDigest.of(file);

        // when
        Optional<AgentDistributionDigest> parsed = AgentDistributionDigest.parse(
                computed.hex().toUpperCase(Locale.ROOT));

        // then
        assertThat(parsed).contains(computed);
    }

    @Test
    void parse_rejects_absent_and_malformed_candidates() {
        // given
        // when
        // then
        assertThat(AgentDistributionDigest.parse(null)).isEmpty();
        assertThat(AgentDistributionDigest.parse("a".repeat(63))).isEmpty();
        assertThat(AgentDistributionDigest.parse("a".repeat(65))).isEmpty();
        assertThat(AgentDistributionDigest.parse("g".repeat(64))).isEmpty();
        assertThat(AgentDistributionDigest.parse(" " + "a".repeat(64))).isEmpty();
    }

    @Test
    void missing_file_throws_no_such_file_exception() {
        // given
        Path missing = temporaryDirectory.resolve("missing.bin");

        // when
        // then
        assertThatThrownBy(() -> AgentDistributionDigest.of(missing))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void constructor_rejects_uppercase_hex() {
        // given
        String uppercase = EMPTY_SHA256.toUpperCase(Locale.ROOT);

        // when
        // then
        assertThatThrownBy(() -> new AgentDistributionDigest(uppercase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SHA-256 digest must be 64 lowercase hex characters");
    }
}
