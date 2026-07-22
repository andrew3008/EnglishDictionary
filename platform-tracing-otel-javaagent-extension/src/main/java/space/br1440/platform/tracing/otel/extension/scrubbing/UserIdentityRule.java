package space.br1440.platform.tracing.otel.extension.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для идентификационных данных пользователя из Keycloak JWT claims.
 * DROP для ФИО, HASH для логина/email.
 */
final class UserIdentityRule extends AbstractBuiltInRule {

    private static final String[] DROP_TOKENS = {
            "firstname", "lastname", "fullname", "givenname", "familyname"
    };
    private static final String[] HASH_TOKENS = {
            "preferredusername", "email"
    };

    UserIdentityRule() {
        super(BuiltInSpanAttributeScrubbingRules.USER_IDENTITY);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        String normalized = KeyMatcher.normalize(key);
        if (KeyMatcher.containsAny(normalized, DROP_TOKENS)) {
            return ScrubbingDecision.drop("user-identity-pii");
        }
        if (KeyMatcher.containsAny(normalized, HASH_TOKENS)) {
            return ScrubbingDecision.hash("user-identity");
        }
        return ScrubbingDecision.keep();
    }
}
