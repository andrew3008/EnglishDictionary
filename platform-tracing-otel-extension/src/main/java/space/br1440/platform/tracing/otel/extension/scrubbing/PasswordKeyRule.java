package space.br1440.platform.tracing.otel.extension.scrubbing;


import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для атрибутов, чьи <b>имена</b> указывают на пароль или секрет.
 * <p>
 * Сопоставление выполняется по нормализованному имени ключа (см. {@link KeyMatcher}) — это
 * покрывает варианты {@code db.password}, {@code app.client-secret}, {@code CLIENT_SECRET}.
 * Действие — {@link ScrubbingDecision#drop} (значение целиком недопустимо в trace), так как
 * частичное маскирование секрета утекает подсказки длины/префикса.
 */
final class PasswordKeyRule extends AbstractBuiltInRule {

    /** Токены уже нормализованы (нижний регистр, без разделителей) — см. {@link KeyMatcher}. */
    private static final String[] TOKENS = {
            "password", "passwd", "secret", "token", "apikey",
            "clientsecret", "vault", "nexus", "registry", "pgp"
    };

    PasswordKeyRule() {
        super(BuiltInSensitiveDataRules.PASSWORD_KEY);
    }

    @Override
    public ScrubbingDecision evaluate(String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("password-key");
        }
        return ScrubbingDecision.keep();
    }
}
