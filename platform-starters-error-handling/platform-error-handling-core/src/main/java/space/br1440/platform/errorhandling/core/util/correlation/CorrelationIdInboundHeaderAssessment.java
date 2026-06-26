package space.br1440.platform.errorhandling.core.util.correlation;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.regex.Pattern;

/**
 * Валидация «сырой» строки заголовка в формат платформенного correlation-id:
 * управляющие символы STOMP → граничная длина → whitelist-регулярка.
 *
 * <p>HTTP-слои обычно передают {@code rejectCrlfAndNul=false} (парсер запроса отсекает
 * CR/LF в значении заголовка). STOMP пробрасывает CR/LF/NUL — там {@code true}
 * обязателен до любого pattern-match.
 */
@UtilityClass
public final class CorrelationIdInboundHeaderAssessment {

    /** Зафиксированные причины отказа; bounded cardinality для метрик/STOMP-слоя. */
    public enum RejectReason {
        /** CRLF или NUL до regex — защита CWE-113/117 в канале STOMP/Spring Messaging. */
        CRLF_CONTROL,
        /** Превышен {@code maxLength}. */
        LENGTH,
        /** Символы вне {@code allowedPattern}. */
        CHARSET
    }

    /** Либо принято значение без изменений, либо отказ без утечки сырья наружу. */
    public record Outcome(@Nullable String acceptedValue, @Nullable RejectReason rejectReason) {

        /** Успешно прошёл все этапы. */
        static Outcome accepted(@NonNull String value) {
            return new Outcome(value, null);
        }

        /** Жёсткий отказ до использования строки downstream. */
        static Outcome rejected(@NonNull RejectReason reason) {
            return new Outcome(null, reason);
        }
    }

    /**
     * Применить политику к непустой строке заголовка.
     *
     * @param raw               значение уже проверенное upstream на непустоту (trim — на усмотрение вызывающего)
     * @param rejectCrlfAndNul  при {@code true} отклоняет {@code CR},{@code LF},{@code NUL}
     */
    @NonNull
    public static Outcome assess(
            @NonNull String raw,
            int maxLength,
            @NonNull Pattern allowedPattern,
            boolean rejectCrlfAndNul) {
        if (rejectCrlfAndNul && containsForbiddenControlChar(raw)) {
            return Outcome.rejected(RejectReason.CRLF_CONTROL);
        }

        if (raw.length() > maxLength) {
            return Outcome.rejected(RejectReason.LENGTH);
        }

        if (!allowedPattern.matcher(raw).matches()) {
            return Outcome.rejected(RejectReason.CHARSET);
        }

        return Outcome.accepted(raw);
    }

    public static boolean containsForbiddenControlChar(@NonNull String value) {
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\u0000') {
                return true;
            }
        }

        return false;
    }
}
