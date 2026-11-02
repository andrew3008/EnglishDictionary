package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;

/**
 * Правило обнаружения JWT-токенов формата {@code header.payload.signature} (base64url).
 * <p>
 * Условие срабатывания — наличие префикса {@code eyJ}, характерного для base64url-кодированного
 * заголовка JWT. Проверяет строковые значения и элементы строковых массивов.
 * Действие — {@link ScrubbingDecision#drop}: усечение токена всё равно оставляет валидный
 * секрет, поэтому значение удаляется целиком.
 */
final class JwtRule extends AbstractValueScanRule {

    JwtRule() {
        super(BuiltInSpanAttributeScrubbingRules.JWT, ScrubbingDecision.drop("jwt"));
    }

    @Override
    protected boolean matches(String text) {
        int headerStart = text.indexOf("eyJ");
        if (headerStart < 0) {
            return false;
        }

        int segmentStart = headerStart;
        boolean headerBeforeDot = false;
        for (int index = headerStart; index < text.length(); index++) {
            char character = text.charAt(index);
            if (isBase64Url(character)) {
                if (headerStart < 0 && character == 'e' && text.startsWith("eyJ", index)) {
                    headerStart = index;
                }

                continue;
            }

            if (character == '.') {
                if (headerBeforeDot && index > segmentStart) {
                    return true;
                }

                headerBeforeDot = (headerStart >= 0) && ((index - headerStart) > 3);
            } else {
                headerBeforeDot = false;
            }

            segmentStart = index + 1;
            headerStart = -1;
        }

        return false;
    }

    private static boolean isBase64Url(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9'
                || character == '_' || character == '-';
    }
}
