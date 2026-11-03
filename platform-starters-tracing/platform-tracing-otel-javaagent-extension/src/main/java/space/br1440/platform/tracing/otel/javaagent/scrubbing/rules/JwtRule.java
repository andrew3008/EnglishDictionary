package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.runtime.scrubbing.SensitiveValueDetector;

/**
 * Правило обнаружения JWT-токенов формата {@code header.payload.signature}.
 * <p>
 * Действие — {@link ScrubbingDecision#drop}: усечение токена всё равно оставляет валидный секрет,
 * поэтому значение удаляется целиком.
 */
final class JwtRule extends AbstractValueScanRule {

    JwtRule() {
        super(BuiltInSpanAttributeScrubbingRules.JWT, ScrubbingDecision.drop("jwt"));
    }

    @Override
    protected boolean matches(String text) {
        return SensitiveValueDetector.containsJwt(text);
    }
}
