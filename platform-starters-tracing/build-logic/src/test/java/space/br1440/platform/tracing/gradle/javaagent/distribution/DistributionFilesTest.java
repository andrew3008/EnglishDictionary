package space.br1440.platform.tracing.gradle.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DistributionFilesTest {

    @Test
    void replaces_existing_target_and_keeps_staged_file(@TempDir Path directory) throws IOException {
        // given
        byte[] content = {0, 1, 2, -1};
        Path staged = Files.write(directory.resolve("staged.jar"), content);
        Path target = Files.writeString(directory.resolve("agent.jar"), "existing", StandardCharsets.UTF_8);

        // when
        DistributionFiles.publishAtomically(staged, target);

        // then
        assertThat(Files.readAllBytes(target)).isEqualTo(content);
        assertThat(Files.readAllBytes(staged)).isEqualTo(content);

        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("staged.jar", "agent.jar");
        }
    }

    @Test
    void keeps_existing_target_when_staged_file_is_missing(@TempDir Path directory) throws IOException {
        // given
        Path target = Files.writeString(directory.resolve("agent.jar"), "existing", StandardCharsets.UTF_8);
        Path missing = directory.resolve("missing.jar");

        // when
        Throwable failure = catchThrowable(() -> DistributionFiles.publishAtomically(missing, target));

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("Cannot atomically replace")
                .hasCauseInstanceOf(NoSuchFileException.class);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("existing");

        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("agent.jar");
        }
    }

    @Test
    void cleans_temporary_file_when_target_directory_cannot_be_replaced(@TempDir Path directory) throws IOException {
        // given
        Path staged = Files.writeString(directory.resolve("staged.jar"), "replacement", StandardCharsets.UTF_8);
        Path target = Files.createDirectory(directory.resolve("agent.jar"));
        Path marker = Files.writeString(target.resolve("marker"), "existing", StandardCharsets.UTF_8);

        // when
        Throwable failure = catchThrowable(() -> DistributionFiles.publishAtomically(staged, target));

        // then
        assertThat(failure)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("Cannot atomically replace")
                .hasCauseInstanceOf(IOException.class);
        assertThat(Files.readString(staged, StandardCharsets.UTF_8)).isEqualTo("replacement");
        assertThat(Files.readString(marker, StandardCharsets.UTF_8)).isEqualTo("existing");

        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("staged.jar", "agent.jar");
        }

        try (var files = Files.list(target)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("marker");
        }
    }
}
