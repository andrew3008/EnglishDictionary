package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaArgFileFormatTest {

    @Test
    void render_leaves_arguments_without_whitespace_unquoted() {
        // given
        List<String> arguments = List.of("-XX:+DisableAttachMechanism", "-Dkey=value");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).isEqualTo("\"-XX:+DisableAttachMechanism\"\n\"-Dkey=value\"\n");
    }

    @Test
    void render_quotes_an_argument_containing_a_space() {
        // given
        List<String> arguments = List.of("-javaagent:/opt/Work Projects/agent.jar");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).isEqualTo("\"-javaagent:/opt/Work Projects/agent.jar\"\n");
    }

    @Test
    void render_escapes_backslashes_and_quotes_inside_a_quoted_windows_path() {
        // given
        List<String> arguments = List.of("-javaagent:E:\\Work Projects\\A&B\\agent.jar");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).isEqualTo("\"-javaagent:E:\\\\Work Projects\\\\A&B\\\\agent.jar\"\n");
    }

    @Test
    void render_rejects_a_line_break() {
        // given
        List<String> arguments = List.of("-Dfoo=a\nb");

        // when / then
        assertThatThrownBy(() -> JavaArgFileFormat.render(arguments))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("line break");
    }

    @Test
    void render_quotes_a_hash_and_an_apostrophe() {
        // given
        List<String> arguments = List.of("-Dfoo=a#b", "-Dfoo=it's");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).isEqualTo("\"-Dfoo=a#b\"\n\"-Dfoo=it's\"\n");
    }

    @Test
    void real_java_launcher_reads_a_hash_and_an_apostrophe(@TempDir Path directory) throws Exception {
        // given
        String content = JavaArgFileFormat.render(List.of("-Dfoo.hash=a#b", "-Dfoo.apos=it's"));
        Path argsFile = directory.resolve("probe.args");
        Files.writeString(argsFile, content, StandardCharsets.UTF_8);
        String javaCommand = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaCommand);

        // when
        Process process = new ProcessBuilder(
                java.toString(),
                "@" + argsFile,
                "-XshowSettings:properties",
                "-version")
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(30).toSeconds(), TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        // then
        if (!finished) {
            process.destroyForcibly();
        }
        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(output).contains("foo.hash = a#b").contains("foo.apos = it's");
    }

    @Test
    void render_terminates_every_line_including_the_last_one() {
        // given
        List<String> arguments = List.of("-Da=1", "-Db=2");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).endsWith("\n").isEqualTo("\"-Da=1\"\n\"-Db=2\"\n");
    }
}
