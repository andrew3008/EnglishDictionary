package space.br1440.platform.devtools.opusmcp.security;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 3 outbound secret detection.
 *
 * <p>Scans {@code task}, {@code context}, and {@code constraints} before any external model call.
 * Detects likely secret <em>material</em> (private keys, tokens, AWS keys, secret assignments,
 * bearer authorization). File-name / path references are handled by {@link DenyList}.
 *
 * <p>Security: violation messages contain only the matched category name and never the matched
 * substring, so raw secret values are never echoed back or logged.
 */
public final class SecretScanner {

    /** A named detection rule. The label is safe to log; the pattern result is never logged. */
    private record Rule(String label, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("private key block", Pattern.compile(
                    "-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----", Pattern.CASE_INSENSITIVE)),
            new Rule("authorization bearer token", Pattern.compile(
                    "(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{20,}")),
            new Rule("aws access key id", Pattern.compile(
                    "\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b")),
            new Rule("aws credential identifier", Pattern.compile(
                    "(?i)\\baws_(?:access_key_id|secret_access_key)\\b")),
            new Rule("secret assignment", Pattern.compile(
                    "(?i)\\b(?:x-api-key|api[_-]?key|apikey|access_token|refresh_token|id_token"
                            + "|client_secret|secret|password|passwd|token)\\b\\s*[:=]\\s*\\S")));

    /**
     * Returns a safe description of the first detected secret category, or empty if none.
     * The returned message never contains the offending substring.
     */
    public Optional<String> findViolation(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of("possible secret detected (" + rule.label() + ")");
            }
        }
        return Optional.empty();
    }

    public boolean isSafe(String text) {
        return findViolation(text).isEmpty();
    }
}
