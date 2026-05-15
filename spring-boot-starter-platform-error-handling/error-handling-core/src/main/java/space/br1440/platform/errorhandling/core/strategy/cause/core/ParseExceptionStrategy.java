package space.br1440.platform.errorhandling.core.strategy.cause.core;

import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.extension.order.RootCauseExceptionStrategyOrder;
import space.br1440.platform.errorhandling.extension.base.AbstractRootCauseExceptionStrategy;

import space.br1440.platform.errorhandling.core.util.DiagnosticMetadataBuilder;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.text.ParseException;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Root-cause стратегия для ошибок разбора числовых, временны́х и текстовых значений.
 *
 * <h2>Поддерживаемые типы</h2>
 * <ul>
 *   <li>{@link NumberFormatException} → {@code NUMBER_FORMAT} — невалидное числовое значение
 *       (строка, которую нельзя преобразовать в {@code int}, {@code long}, {@code BigDecimal} и т.п.)</li>
 *   <li>{@link DateTimeParseException} → {@code DATE_TIME_FORMAT} — невалидный формат даты/времени
 *       ({@code LocalDate}, {@code LocalDateTime}, {@code ZonedDateTime} и т.п.)</li>
 *   <li>{@link ParseException java.text.ParseException} → {@code TEXT_FORMAT} — ошибка legacy-парсинга
 *       ({@code SimpleDateFormat}, {@code DecimalFormat}, {@code MessageFormat} и т.п.)</li>
 * </ul>
 *
 * <h2>HTTP-маппинг</h2>
 * <p>
 * По умолчанию — {@code 500 Internal Server Error}. Это root-cause стратегия: она срабатывает
 * <b>только после</b> top-level стратегий. Web-binding ошибки ({@link NumberFormatException} из
 * {@code @PathVariable}/{@code @RequestParam}, {@code DateTimeParseException} из Jackson-десериализации)
 * перехватываются {@code SpringMvcExceptionStrategy} / {@code JacksonExceptionStrategy} <b>раньше</b>
 * и корректно маппятся на {@code 400 Bad Request}.
 * <p>
 * Если эта стратегия достигнута — parse-ошибка возникла глубоко в сервисном слое (парсинг
 * конфига, фонового задания, межсервисного payload и т.п.). Это почти всегда серверный дефект:
 * разработчик забыл обернуть парсинг в валидацию. Возвращать {@code 400} здесь — значит
 * маскировать серверный баг под ошибку клиента (искажение SLO / availability burn-rate).
 *
 * <h2>Безопасность (CWE-209 / CWE-312)</h2>
 * <p>
 * Оригинальное сообщение исключения (содержащее входное значение) намеренно
 * <b>не включается</b> в клиентский ответ: может содержать токены, персональные данные,
 * внутренние форматы.
 */
public class ParseExceptionStrategy extends AbstractRootCauseExceptionStrategy {

    private static final int MAX_CAUSE_MESSAGE_LENGTH = 128;

    private static final String MESSAGE_NUMBER = "Invalid numeric value: expected a number in the required format.";
    private static final String MESSAGE_DATE_TIME = "Invalid date/time value: expected a value in the required format.";
    private static final String MESSAGE_TEXT = "Invalid textual value: expected a value in the required format.";

    private static final String REASON_NUMBER = "NUMBER_FORMAT";
    private static final String REASON_DATE_TIME = "DATE_TIME_FORMAT";
    private static final String REASON_TEXT = "TEXT_FORMAT";

    private static final String DOMAIN = "parsing";

    public ParseExceptionStrategy(StackHintExtractor stackHintExtractor) {
        super(RootCauseExceptionStrategyOrder.PARSE, stackHintExtractor);
    }

    /**
     * Parse-ошибки на root-cause уровне — индикатор отсутствия валидации на входе сервиса.
     * {@link LogPolicy#WARN}: требуют внимания SRE (потенциальный баг или неожиданный входной
     * поток), но не являются инфраструктурным сбоем уровня ERROR.
     */
    @Override
    @NonNull
    public @NotNull LogPolicy logPolicy(@NonNull Exception exception,
                                        @NonNull ErrorEntry errorEntry) {
        return LogPolicy.WARN;
    }

    @Override
    public boolean supportsCause(@NonNull Throwable cause) {
        return cause instanceof NumberFormatException
                || cause instanceof DateTimeParseException
                || cause instanceof ParseException;
    }

    @Override
    @NonNull
    public RootCauseResolution extractCauseDetails(@NonNull Throwable cause) {
        ParseErrorType type = classify(cause);

        ErrorInfoDetail info = ErrorInfoDetail.builder()
                .reason(type.reason)
                .domain(DOMAIN)
                .diagnosticMetadata(buildDiagnosticMetadataForCause(cause, type))
                .build();

        return RootCauseResolution.of(HttpStatus.INTERNAL_SERVER_ERROR, type.message, List.of(info));
    }

    @NonNull
    private ParseErrorType classify(@NonNull Throwable cause) {
        return switch (cause) {
            case DateTimeParseException ignored -> ParseErrorType.DATE_TIME;
            case ParseException ignored -> ParseErrorType.TEXT;
            case NumberFormatException ignored -> ParseErrorType.NUMBER;
            default -> throw new IllegalStateException(
                    "ParseExceptionStrategy received unsupported cause type: " + cause.getClass().getName()
                            + " — supportsCause(...) contract violation");
        };
    }

    @NonNull
    private Map<String, String> buildDiagnosticMetadataForCause(@NonNull Throwable cause,
                                                                @NonNull ParseErrorType type) {
        DiagnosticMetadataBuilder builder = DiagnosticMetadataBuilder.forCause(cause, stackHintExtractor);

        switch (type) {
            case NUMBER -> {
                // нет дополнительных безопасных полей: NumberFormatException.getMessage()
                // всегда содержит сырой input ("For input string: \"<raw>\"")
            }
            case DATE_TIME -> enrichDateTime(builder, (DateTimeParseException) cause);
            case TEXT -> enrichText(builder, (ParseException) cause);
        }

        return builder.withStackHint().build();
    }

    private void enrichDateTime(@NonNull DiagnosticMetadataBuilder builder,
                                @NonNull DateTimeParseException cause) {
        if (cause.getErrorIndex() >= 0) {
            builder.put("errorIndex", String.valueOf(cause.getErrorIndex()));
        }

        String parsed = cause.getParsedString();
        if (parsed != null) {
            builder.putIfPresent("parsedString", StringUtils.truncate(sanitizeControlChars(parsed), MAX_CAUSE_MESSAGE_LENGTH));
        }
    }

    private void enrichText(@NonNull DiagnosticMetadataBuilder builder,
                            @NonNull ParseException cause) {
        if (cause.getErrorOffset() >= 0) {
            builder.put("errorOffset", String.valueOf(cause.getErrorOffset()));
        }

        builder.putIfPresent("causeMessage", StringUtils.truncate(sanitizeControlChars(cause.getMessage()), MAX_CAUSE_MESSAGE_LENGTH));
    }

    private static String sanitizeControlChars(String value) {
        if (value == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c == '\0') {
                sb.append(' ');
            } else if (c < 0x20) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private enum ParseErrorType {
        NUMBER(REASON_NUMBER, MESSAGE_NUMBER),
        DATE_TIME(REASON_DATE_TIME, MESSAGE_DATE_TIME),
        TEXT(REASON_TEXT, MESSAGE_TEXT);

        final String reason;
        final String message;

        ParseErrorType(String reason, String message) {
            this.reason = reason;
            this.message = message;
        }
    }
}
