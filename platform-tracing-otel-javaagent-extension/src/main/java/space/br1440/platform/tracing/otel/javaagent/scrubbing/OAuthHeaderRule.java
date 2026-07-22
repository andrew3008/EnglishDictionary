package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для заголовков аутентификации/сессии: {@code authorization}, {@code x-access-token},
 * {@code cookie}, {@code set-cookie}.
 * <p>
 * Key-based, наивысший приоритет (DROP): захваченные HTTP-заголовки приходят как
 * {@code http.request.header.authorization} и т.п., их значения недопустимы в trace целиком.
 */
final class OAuthHeaderRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {
            "authorization", "xaccesstoken", "cookie", "setcookie"
    };

    OAuthHeaderRule() {
        super(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("oauth-header");
        }
        return ScrubbingDecision.keep();
    }
}
