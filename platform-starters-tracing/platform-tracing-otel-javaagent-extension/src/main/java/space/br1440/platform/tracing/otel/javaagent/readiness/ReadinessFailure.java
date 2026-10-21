package space.br1440.platform.tracing.otel.javaagent.readiness;

import java.util.regex.Pattern;

public record ReadinessFailure(String primaryCode, String effectiveCode) {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final String FALLBACK_CODE = "EXTENSION_INITIALIZATION_FAILED";

    public ReadinessFailure {
        requireWellFormed(primaryCode, "primaryCode");
        requireWellFormed(effectiveCode, "effectiveCode");
    }

    static ReadinessFailure normalized(String primaryCode, String effectiveCode) {
        return new ReadinessFailure(normalize(primaryCode), normalize(effectiveCode));
    }

    private static String normalize(String code) {
        return isWellFormed(code) ? code : FALLBACK_CODE;
    }

    private static void requireWellFormed(String code, String name) {
        if (!isWellFormed(code)) {
            throw new IllegalArgumentException(name + " must match " + CODE_PATTERN.pattern());
        }
    }

    private static boolean isWellFormed(String code) {
        return (code != null) && CODE_PATTERN.matcher(code).matches();
    }
}
