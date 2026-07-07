package space.br1440.platform.tracing.otel.extension.scrubbing;


import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для SSH/TLS-материалов: {@code passphrase}, {@code private-key}, {@code sender_cert}.
 * Key-based, DROP.
 */
final class SshCredentialRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {"passphrase", "privatekey", "sendercert"};

    SshCredentialRule() {
        super(BuiltInSensitiveDataRules.SSH_CREDENTIAL);
    }

    @Override
    public ScrubbingDecision evaluate(String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("ssh-credential");
        }
        return ScrubbingDecision.keep();
    }
}
