package space.br1440.platform.tracing.gradle;

import java.util.List;

final class JavaArgFileFormat {

    private static final String LINE_SEPARATOR = "\n";

    private JavaArgFileFormat() {
    }

    static String render(List<String> arguments) {
        StringBuilder content = new StringBuilder();
        for (String argument : arguments) {
            content.append(quoteIfNeeded(argument)).append(LINE_SEPARATOR);
        }

        return content.toString();
    }

    private static String quoteIfNeeded(String argument) {
        if (!containsWhitespace(argument)) {
            return argument;
        }

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

    private static boolean containsWhitespace(String argument) {
        for (int i = 0; i < argument.length(); i++) {
            if (Character.isWhitespace(argument.charAt(i))) {
                return true;
            }
        }

        return false;
    }
}
