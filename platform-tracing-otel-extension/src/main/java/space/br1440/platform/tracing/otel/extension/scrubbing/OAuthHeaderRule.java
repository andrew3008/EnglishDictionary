package space.br1440.platform.tracing.otel.extension.scrubbing;


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
        super(BuiltInSensitiveDataRules.OAUTH_HEADER);
    }

    @Override
    public ScrubbingDecision evaluate(String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("oauth-header");
        }
        return ScrubbingDecision.keep();
    }
}
