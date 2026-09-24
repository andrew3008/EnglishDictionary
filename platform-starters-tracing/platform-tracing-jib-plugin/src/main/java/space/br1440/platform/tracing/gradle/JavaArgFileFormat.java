package space.br1440.platform.tracing.gradle;

import java.util.List;

import org.gradle.api.GradleException;

import lombok.experimental.UtilityClass;

@UtilityClass
final class JavaArgFileFormat {

    private static final String LINE_SEPARATOR = "\n";

    static String render(List<String> arguments) {
        StringBuilder content = new StringBuilder();
        for (String argument : arguments) {
            if (argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
                throw new GradleException("Java argument file entry must not contain a line break");
            }

            content.append(quote(argument)).append(LINE_SEPARATOR);
        }

        return content.toString();
    }

    private static String quote(String argument) {
        StringBuilder quoted = new StringBuilder(argument.length() + 2);
        quoted.append('"');

        for (int i = 0; i < argument.length(); i++) {
            char character = argument.charAt(i);
            if (character == '\\' || character == '"') {
                quoted.append('\\');
            }

            quoted.append(character);
        }

        quoted.append('"');
        return quoted.toString();
    }
}
