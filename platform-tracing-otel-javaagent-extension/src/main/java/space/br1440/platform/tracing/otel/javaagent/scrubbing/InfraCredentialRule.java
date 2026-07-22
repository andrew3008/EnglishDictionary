package space.br1440.platform.tracing.otel.javaagent.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило для инфраструктурных credentials: пароли БД и брокеров.
 * Key-based, DROP.
 */
final class InfraCredentialRule extends AbstractBuiltInRule {

    private static final String[] TOKENS = {
            "postgrespassword", "pgpassword", "rabbitpassword",
            "kafkapassword", "clickhousepassword"
    };

    InfraCredentialRule() {
        super(BuiltInSpanAttributeScrubbingRules.INFRA_CREDENTIAL);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (KeyMatcher.containsAny(KeyMatcher.normalize(key), TOKENS)) {
            return ScrubbingDecision.drop("infra-credential");
        }
        return ScrubbingDecision.keep();
    }
}
