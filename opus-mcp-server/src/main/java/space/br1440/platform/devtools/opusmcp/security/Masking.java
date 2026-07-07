package space.br1440.platform.devtools.opusmcp.security;

import java.util.regex.Pattern;

/**
 * Reusable redaction utility applied before logging exceptions or provider errors.
 *
 * <p>Masks: configured API key, bearer tokens, {@code x-api-key} header values,
 * {@code password/secret/token} assignments, and private key blocks. The replacement marker never
 * reveals length or content of the original value.
 */
public final class Masking {

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "(?is)-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----.*?-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY-----");

    private static final Pattern PRIVATE_KEY_HEADER = Pattern.compile(
            "(?i)-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----");

    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{8,}");

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(x-api-key|api[_-]?key|apikey|access_token|refresh_token|id_token|client_secret"
                    + "|secret|password|passwd|token)(\\s*[:=]\\s*)([^\\s,;}\"']+)");

    private Masking() {
    }

    /** Redacts known secret shapes from arbitrary text. Null-safe. */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String masked = PRIVATE_KEY_BLOCK.matcher(input).replaceAll(REDACTED);
        masked = PRIVATE_KEY_HEADER.matcher(masked).replaceAll(REDACTED);
        masked = BEARER.matcher(masked).replaceAll("Bearer " + REDACTED);
        masked = ASSIGNMENT.matcher(masked).replaceAll("$1$2" + REDACTED);
        return masked;
    }

    /**
     * Redacts a known literal secret (e.g. the configured API key) in addition to generic shapes.
     * Null/blank secret falls back to {@link #mask(String)}.
     */
    public static String maskSecret(String input, String secret) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String result = input;
        if (secret != null && !secret.isBlank()) {
            result = result.replace(secret, REDACTED);
        }
        return mask(result);
    }
}
