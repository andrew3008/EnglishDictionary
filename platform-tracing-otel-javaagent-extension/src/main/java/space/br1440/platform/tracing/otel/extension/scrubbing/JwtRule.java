package space.br1440.platform.tracing.otel.extension.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import java.util.regex.Pattern;

/**
 * Правило обнаружения JWT-токенов формата {@code header.payload.signature} (base64url).
 * <p>
 * Условие срабатывания — наличие префикса {@code eyJ}, характерного для base64url-кодированного
 * заголовка JWT. Это value-based правило: проверяет значение, только если оно {@link String}.
 * Действие — {@link ScrubbingDecision#drop}: усечение токена всё равно оставляет валидный
 * секрет, поэтому значение удаляется целиком.
 */
final class JwtRule extends AbstractBuiltInRule {

    private static final Pattern PATTERN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");

    JwtRule() {
        super(BuiltInSpanAttributeScrubbingRules.JWT);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (value instanceof String s && PATTERN.matcher(s).find()) {
            return ScrubbingDecision.drop("jwt");
        }
        return ScrubbingDecision.keep();
    }
}
