package space.br1440.platform.tracing.gradle;

import java.util.Optional;

record SystemPropertyFlag(String key, String value) {

    private static final String PREFIX = "-D";

    static Optional<SystemPropertyFlag> parse(String flag) {
        if (!flag.startsWith(PREFIX)) {
            return Optional.empty();
        }

        String body = flag.substring(PREFIX.length());
        int separator = body.indexOf('=');
        if (separator < 0) {
            SystemPropertyFlag propertyFlag = new SystemPropertyFlag(body, null);
            return Optional.of(propertyFlag);
        }

        SystemPropertyFlag propertyFlag = new SystemPropertyFlag(
                body.substring(0, separator),
                body.substring(separator + 1)
        );
        return Optional.of(propertyFlag);
    }
}
