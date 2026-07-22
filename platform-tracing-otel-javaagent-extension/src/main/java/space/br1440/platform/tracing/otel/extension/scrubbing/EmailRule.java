package space.br1440.platform.tracing.otel.extension.scrubbing;


import jakarta.annotation.Nonnull;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

import java.util.regex.Pattern;

/**
 * Правило обнаружения email-адресов в строковых атрибутах.
 * <p>
 * Использует консервативный regex (RFC 5322 lite): latin-буквы, цифры, разделители
 * {@code . _ % + -}, домен с TLD длиной не менее 2. Действие — {@link ScrubbingDecision#hash}:
 * HMAC-SHA256 позволяет коррелировать пользователя в backend (Tempo) без раскрытия email.
 */
final class EmailRule extends AbstractBuiltInRule {

    private static final Pattern PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    EmailRule() {
        super(BuiltInSpanAttributeScrubbingRules.EMAIL);
    }

    @Nonnull
    @Override
    public ScrubbingDecision evaluate(@Nonnull String key, Object value) {
        if (value instanceof String s && PATTERN.matcher(s).find()) {
            return ScrubbingDecision.hash("email");
        }
        return ScrubbingDecision.keep();
    }
}
