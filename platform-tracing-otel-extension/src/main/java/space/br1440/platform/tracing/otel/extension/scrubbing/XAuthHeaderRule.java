package space.br1440.platform.tracing.otel.extension.scrubbing;


import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для платформенного заголовка {@code X-AUTH-HEADER}.
 * <p>
 * Gateway сериализует {@code UserInfoDTO} (email, ФИО, permissions) в JSON и кладёт его в этот
 * заголовок. При capture HTTP-заголовков атрибут {@code http.request.header.x-auth-header}
 * содержит полный PII-профиль пользователя → DROP.
 */
final class XAuthHeaderRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {"xauthheader"};

    XAuthHeaderRule() {
        super(BuiltInSensitiveDataRules.X_AUTH_HEADER);
    }

    @Override
    public ScrubbingDecision evaluate(String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("x-auth-header");
        }
        return ScrubbingDecision.keep();
    }
}
