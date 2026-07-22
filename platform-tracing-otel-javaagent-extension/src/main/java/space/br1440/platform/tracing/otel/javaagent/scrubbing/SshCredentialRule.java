package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для SSH/TLS-материалов: {@code passphrase}, {@code private-key}, {@code sender_cert}.
 * Key-based, DROP.
 */
final class SshCredentialRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {"passphrase", "privatekey", "sendercert"};

    SshCredentialRule() {
        super(BuiltInSpanAttributeScrubbingRules.SSH_CREDENTIAL);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("ssh-credential");
        }
        return ScrubbingDecision.keep();
    }
}
