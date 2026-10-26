package space.br1440.platform.tracing.gradle.javaagent.extension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.gradle.api.GradleException;

record LogSchemaIdentity(int version, String sha256) {

    private static final String HASH_ALGORITHM = "schema.hashAlgorithm";
    private static final String NAME = "schema.name";
    private static final String VERSION = "schema.version";
    private static final String SHA256 = "schema.sha256";
    private static final List<String> REQUIRED_PROPERTIES = List.of(HASH_ALGORITHM, NAME, VERSION, SHA256);
    private static final Pattern LOWERCASE_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CANONICAL_POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");

    static LogSchemaIdentity parse(String content, String source) {
        Map<String, String> values = new HashMap<>();
        for (String line : content.split("\\R", -1)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            int separator = line.indexOf('=');
            if (separator <= 0 || line.indexOf('\\') >= 0 || !line.equals(line.strip())) {
                throw new GradleException(
                        "%s: expected strict 'key=value' line without escapes or surrounding whitespace, got: %s"
                                .formatted(source, line)
                );
            }

            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!REQUIRED_PROPERTIES.contains(key)) {
                throw new GradleException("%s: unexpected property %s".formatted(source, key));
            }

            if (value.isBlank() || !value.equals(value.strip())) {
                throw new GradleException(
                        "%s: %s must be a trimmed non-blank value, got: %s".formatted(source, key, value)
                );
            }

            if (values.put(key, value) != null) {
                throw new GradleException("%s: duplicate property %s".formatted(source, key));
            }
        }

        for (String key : REQUIRED_PROPERTIES) {
            if (!values.containsKey(key)) {
                throw new GradleException("%s: missing required property %s".formatted(source, key));
            }
        }

        String sha256 = values.get(SHA256);
        if (!LOWERCASE_SHA256.matcher(sha256).matches()) {
            throw new GradleException(
                    "%s: %s must be 64 lowercase hex characters, got: %s".formatted(source, SHA256, sha256)
            );
        }

        return new LogSchemaIdentity(parseVersion(values.get(VERSION), source), sha256);
    }

    private static int parseVersion(String rawVersion, String source) {
        String message = "%s: %s must be a positive integer, got: %s".formatted(source, VERSION, rawVersion);

        if (!CANONICAL_POSITIVE_INTEGER.matcher(rawVersion).matches()) {
            throw new GradleException(message);
        }

        try {
            return Integer.parseInt(rawVersion);
        } catch (NumberFormatException exception) {
            throw new GradleException(message, exception);
        }
    }
}
