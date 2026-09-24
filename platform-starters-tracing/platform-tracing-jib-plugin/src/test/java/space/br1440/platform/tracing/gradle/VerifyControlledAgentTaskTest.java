package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyControlledAgentTaskTest {

    @TempDir
    Path temporaryDirectory;

    private VerifyControlledAgentTask task;
    private Path agentDirectory;
    private Path agentA;
    private Path agentB;

    @BeforeEach
    void setUp() throws IOException {
        Project project = ProjectBuilder.builder().withProjectDir(temporaryDirectory.resolve("project").toFile()).build();
        task = project.getTasks().register("verifyControlledAgent", VerifyControlledAgentTask.class).get();
        agentDirectory = temporaryDirectory.resolve("agent");
        task.getAgentDirectory().set(agentDirectory.toFile());
        task.getAgentVersion().set("1.0.0");

        agentA = Files.writeString(temporaryDirectory.resolve("a.jar"), "agent-a", StandardCharsets.UTF_8);
        agentB = Files.writeString(temporaryDirectory.resolve("b.jar"), "agent-b", StandardCharsets.UTF_8);
    }

    @Test
    void switching_the_pinned_agent_leaves_only_the_current_jar_in_the_directory() throws IOException {
        // given
        configure(agentA, "platform-tracing-javaagent-A.jar");
        task.verify();

        // when
        configure(agentB, "platform-tracing-javaagent-B.jar");
        task.verify();

        // then
        assertThat(directoryEntries()).containsExactly("platform-tracing-javaagent-B.jar");
        assertThat(Files.readAllBytes(agentDirectory.resolve("platform-tracing-javaagent-B.jar")))
                .isEqualTo(Files.readAllBytes(agentB));
    }

    @Test
    void switching_back_restores_the_previous_jar_and_removes_the_intermediate_one() throws IOException {
        // given
        configure(agentA, "platform-tracing-javaagent-A.jar");
        task.verify();
        configure(agentB, "platform-tracing-javaagent-B.jar");
        task.verify();

        // when
        configure(agentA, "platform-tracing-javaagent-A.jar");
        task.verify();

        // then
        assertThat(directoryEntries()).containsExactly("platform-tracing-javaagent-A.jar");
    }

    @Test
    void foreign_files_in_the_owned_directory_are_removed_on_execution() throws IOException {
        // given
        Files.createDirectories(agentDirectory);
        Files.writeString(agentDirectory.resolve("stale.jar"), "stale", StandardCharsets.UTF_8);
        configure(agentA, "platform-tracing-javaagent-A.jar");

        // when
        task.verify();

        // then
        assertThat(directoryEntries()).containsExactly("platform-tracing-javaagent-A.jar");
    }

    @Test
    void sha_mismatch_fails_before_the_directory_is_touched() throws IOException {
        // given
        Files.createDirectories(agentDirectory);
        Files.writeString(agentDirectory.resolve("previous.jar"), "previous", StandardCharsets.UTF_8);
        task.getControlledAgent().setFrom(agentA.toFile());
        task.getAgentFileName().set("platform-tracing-javaagent-A.jar");
        task.getExpectedSha256().set(Sha256Digest.hexOf(agentB));

        // when / then
        assertThatThrownBy(task::verify)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("does not match the SHA-256 pinned");
        assertThat(directoryEntries()).isEmpty();
    }

    @Test
    void more_than_one_artifact_fails_closed() throws IOException {
        // given
        task.getControlledAgent().setFrom(agentA.toFile(), agentB.toFile());
        task.getAgentFileName().set("platform-tracing-javaagent-A.jar");
        task.getExpectedSha256().set(Sha256Digest.hexOf(agentA));

        // when / then
        assertThatThrownBy(task::verify)
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("exactly one artifact, got 2");
        assertThat(agentDirectory).doesNotExist();
    }

    private void configure(Path source, String fileName) throws IOException {
        task.getControlledAgent().setFrom(source.toFile());
        task.getAgentFileName().set(fileName);
        task.getExpectedSha256().set(Sha256Digest.hexOf(source));
    }

    private List<String> directoryEntries() throws IOException {
        try (Stream<Path> entries = Files.list(agentDirectory)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
