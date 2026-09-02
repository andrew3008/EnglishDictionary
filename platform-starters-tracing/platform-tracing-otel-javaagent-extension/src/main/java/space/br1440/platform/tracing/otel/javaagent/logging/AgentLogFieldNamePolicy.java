package space.br1440.platform.tracing.otel.javaagent.logging;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
final class AgentLogFieldNamePolicy {

    static final int MAX_FIELDS = 16;
    static final int MAX_FIELD_NAME_CHARS = 64;

    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]*$");

    private static final Set<String> RESERVED_FIELD_NAMES = Set.of(
            "timestamp",
            "level",
            "logger",
            "message",
            "event_code",
            "fields",
            "error_stacktrace"
    );

    private static final Set<String> SENSITIVE_SOURCE_KEYS = Set.of(
            "password",
            "passwd",
            "pwd",
            "secret",
            "client_secret",
            "app_secret",
            "token",
            "access_token",
            "auth_token",
            "bearer_token",
            "tokens",
            "access_tokens",
            "auth_tokens",
            "bearer_tokens",
            "api_key",
            "apikey",
            "api-key",
            "ssn",
            "social_security_number",
            "email",
            "e-mail",
            "emails",
            "email_list",
            "authorization",
            "cookie",
            "set-cookie",
            "hmac",
            "credential"
    );

    private static final Set<String> CANONICAL_SENSITIVE_KEYS = canonicalizeSensitiveKeys();

    static int maxFields() {
        return MAX_FIELDS;
    }

    static Set<String> reservedFieldNames() {
        return RESERVED_FIELD_NAMES;
    }

    static Set<String> canonicalSensitiveKeys() {
        return CANONICAL_SENSITIVE_KEYS;
    }

    static boolean isStructurallyValidFieldName(String name) {
        if (StringUtils.isNullOrEmpty(name) || name.length() > MAX_FIELD_NAME_CHARS) {
            return false;
        }

        return FIELD_NAME_PATTERN.matcher(name).matches();
    }

    static boolean isReservedFieldName(String name) {
        if (name == null) {
            return true;
        }

        return RESERVED_FIELD_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    static boolean isSensitiveFieldName(String name) {
        if (name == null) {
            return true;
        }

        return CANONICAL_SENSITIVE_KEYS.contains(canonicalName(name));
    }

    static boolean isRejectedFieldName(String name) {
        return !isStructurallyValidFieldName(name)
                || isReservedFieldName(name)
                || isSensitiveFieldName(name);
    }

    static String canonicalName(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
    }

    private static Set<String> canonicalizeSensitiveKeys() {
        Set<String> canonical = new HashSet<>(SENSITIVE_SOURCE_KEYS.size());
        for (String key : SENSITIVE_SOURCE_KEYS) {
            canonical.add(canonicalName(key));
        }

        return Set.copyOf(canonical);
    }
}
