package space.br1440.platform.devtools.opusmcp.security;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 3 deny-list for sensitive file-name / path references.
 *
 * <p>The server never reads files, but Cursor may pass file names or snippets in context. A match
 * here causes the request to be refused before any external model call.
 *
 * <p>Security: violation messages contain only the matched category name, never the matched
 * substring.
 */
public final class DenyList {

    private record Rule(String label, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(".env file reference", Pattern.compile(
                    "(?i)(?:^|[\\s/\\\\\"'`(=:])\\.env(?:\\.[A-Za-z0-9_-]+)?\\b")),
            new Rule("private key file reference", Pattern.compile(
                    "(?i)\\bid_(?:rsa|dsa|ecdsa|ed25519)\\b")),
            new Rule("key/cert file reference", Pattern.compile(
                    "(?i)\\.(?:pem|key|p12|jks)\\b")),
            new Rule("credentials file reference", Pattern.compile(
                    "(?i)\\bcredentials[A-Za-z0-9._-]*")),
            new Rule("secrets file reference", Pattern.compile(
                    "(?i)\\bsecrets[A-Za-z0-9._-]*")),
            new Rule("ssh directory reference", Pattern.compile(
                    "(?i)\\.ssh[/\\\\]")),
            new Rule("git directory reference", Pattern.compile(
                    "(?i)\\.git[/\\\\]")),
            new Rule("kubeconfig reference", Pattern.compile(
                    "(?i)\\bkubeconfig\\b")),
            new Rule("production config reference", Pattern.compile(
                    "(?i)\\bapplication-prod\\.ya?ml\\b")));

    public Optional<String> findViolation(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of("sensitive file reference detected (" + rule.label() + ")");
            }
        }
        return Optional.empty();
    }

    public boolean isSafe(String text) {
        return findViolation(text).isEmpty();
    }
}
