package space.br1440.platform.tracing.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class JavaArgFileFormatTest {

    @Test
    void render_leaves_arguments_without_whitespace_unquoted() {
        // given
        List<String> arguments = List.of("-XX:+DisableAttachMechanism", "-Dkey=value");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).isEqualTo("-XX:+DisableAttachMechanism\n-Dkey=value\n");
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
    void render_terminates_every_line_including_the_last_one() {
        // given
        List<String> arguments = List.of("-Da=1", "-Db=2");

        // when
        String content = JavaArgFileFormat.render(arguments);

        // then
        assertThat(content).endsWith("\n").isEqualTo("-Da=1\n-Db=2\n");
    }
}
