package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для webhook-токенов: {@code webhook_token}, {@code HARBOR_WEBHOOK_TOKEN_*}.
 * Key-based, DROP.
 */
final class WebhookTokenRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {"webhooktoken", "harborwebhook"};

    WebhookTokenRule() {
        super(BuiltInSpanAttributeScrubbingRules.WEBHOOK_TOKEN);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("webhook-token");
        }
        return ScrubbingDecision.keep();
    }
}
