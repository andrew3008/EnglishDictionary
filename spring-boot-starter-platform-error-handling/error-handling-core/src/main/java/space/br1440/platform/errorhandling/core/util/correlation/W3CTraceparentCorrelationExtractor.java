package space.br1440.platform.errorhandling.core.util.correlation;

import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлечение 32-символьного trace-id из заголовка {@code traceparent} (W3C Trace Context).
 *
 * <p>Формат и отсечение all-zero trace-id совпадают между servlet, WebFlux и STOMP.
 * Разница стеков — только в том, кто читает байты frame/HTTP и нужен ли trim.
 */
@UtilityClass
public final class W3CTraceparentCorrelationExtractor {

    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^[0-9a-f]{2}-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$"
    );

    /** All-zero trace-id по спецификации W3C — «пустой» контекст, не используем как correlation-id. */
    private static final String INVALID_TRACE_ID = "00000000000000000000000000000000";

    /**
     * Результат разбора: либо валидный trace-id, либо класс отказа (для дифференцированных логов STOMP).
     */
    public sealed interface Attempt permits Attempt.Accepted, Attempt.BadFormat, Attempt.AllZeroTraceId {

        record Accepted(@NonNull String traceId) implements Attempt {}

        /** Не совпало с грамматикой W3C либо пустая строка после trim. */
        record BadFormat() implements Attempt {}

        /** Trace-id из 32 нулей — отбрасываем отдельно от «плохого формата». */
        record AllZeroTraceId() implements Attempt {}
    }

    /**
     * Полный разбор с классификацией ошибки; применять в HTTP при предварительной проверке
     * {@link StringUtils#hasText(CharSequence)}, иначе {@link Attempt.BadFormat()} — ожидаемо.
     */
    @NonNull
    public static Attempt parse(@Nullable String traceparentRaw, boolean trimLeadingTrailing) {
        if (!StringUtils.hasText(traceparentRaw)) {
            return new Attempt.BadFormat();
        }

        String candidate = trimLeadingTrailing ? traceparentRaw.trim() : traceparentRaw;

        Matcher matcher = TRACEPARENT_PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            return new Attempt.BadFormat();
        }

        String traceId = matcher.group(1);
        if (INVALID_TRACE_ID.equals(traceId)) {
            return new Attempt.AllZeroTraceId();
        }

        return new Attempt.Accepted(traceId);
    }

    /**
     * Упрощение для servlet/WebFlux слоёв: либо валидное значение, либо {@code null}
     * (вызов уже окружён общим WARN в резолвере).
     */
    @Nullable
    public static String traceIdOrNull(@Nullable String traceparentRaw, boolean trimLeadingTrailing) {
        Attempt attempt = parse(traceparentRaw, trimLeadingTrailing);
        if (attempt instanceof Attempt.Accepted accepted) {
            return accepted.traceId();
        }
        return null;
    }
}
