package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

class ReproducibleAgentArchiveTest {

    private static final LocalDateTime CANONICAL_TIMESTAMP = LocalDateTime.of(1980, 2, 1, 0, 0, 0);

    @Test
    void archive_uses_the_gradle_reproducible_profile() throws IOException {
        // given
        Path agent = AgentArchive.requiredPath("platformTracing.controlledAgentJar");

        // when
        try (ZipFile zip = new ZipFile(agent.toFile(), StandardCharsets.UTF_8)) {
            String comment = zip.getComment();
            List<ZipEntry> entries = AgentArchive.entries(zip);

            // then
            assertThat(comment).isNull();
            for (ZipEntry entry : entries) {
                assertReproducibleEntry(entry);
            }
        }
    }

    private static void assertReproducibleEntry(ZipEntry entry) {
        assertThat(entry.getTimeLocal()).as(entry.getName()).isEqualTo(CANONICAL_TIMESTAMP);
        assertThat(entry.getComment()).as(entry.getName()).isNull();
        assertThat(entry.getExtra()).as(entry.getName()).isNull();

        if (!entry.isDirectory()) {
            assertThat(entry.getMethod()).as(entry.getName()).isEqualTo(ZipEntry.DEFLATED);
        }
    }
}
