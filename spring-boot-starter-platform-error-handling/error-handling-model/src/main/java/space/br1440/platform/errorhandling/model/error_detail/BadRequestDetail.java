package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Доменная модель детали ошибки {@code BAD_REQUEST}
 * (см. <a href="https://cloud.google.com/apis/design/errors#standard_error_payloads">google.rpc.BadRequest</a>).
 * <p>
 * Кастомный shortcut {@code addFieldViolation(field, reason, description)} в
 * {@link BadRequestDetailBuilder} позволяет писать триплеты без явного создания {@link FieldViolation}:
 *
 * <pre>{@code
 * throw new ConstraintViolationException(MY_ERROR_CODE, extra,
 *     List.of(BadRequestDetail.builder()
 *         .addFieldViolation("email", "INVALID", "must be a valid email address")
 *         .addFieldViolation("items[0].qty", "OUT_OF_RANGE", "must be between 1 and 100")
 *         .build()));
 * }</pre>
 */
@Value
@Builder(toBuilder = true)
public class BadRequestDetail implements ErrorDetail {

    @NotNull
    @Singular
    List<FieldViolation> fieldViolations;

    /**
     * Расширение Lombok-сгенерированного builder-а: shortcut для добавления
     * {@link FieldViolation} тремя строковыми аргументами без явного построения объекта.
     *
     * <p>Lombok видит уже объявленный inner-класс {@code BadRequestDetailBuilder}
     * и дописывает в него все остальные сгенерированные методы (включая
     * {@code fieldViolation(FieldViolation)}, {@code fieldViolations(Collection)},
     * {@code clearFieldViolations()}, {@code build()}).
     */
    public static class BadRequestDetailBuilder {

        public BadRequestDetailBuilder addFieldViolation(@Nullable String field,
                                                         @NotNull String reason,
                                                         @NotNull String description) {
            return fieldViolation(new FieldViolation(field, reason, description));
        }
    }

    @Value
    public static class FieldViolation {

        /**
         * Путь к полю в dot-нотации ({@code "email"}, {@code "items[0].quantity"}).
         * Может быть {@code null} для object-level нарушений (cross-field валидация,
         * class-level constraints).
         */
        @Nullable
        String field;

        /**
         * Машиночитаемый код ошибки валидации в {@code UPPER_SNAKE_CASE}.
         * Например: {@code "INVALID"}, {@code "INVALID_FORMAT"}, {@code "OUT_OF_RANGE"}, {@code "REQUIRED"}.
         */
        @NotNull
        String reason;

        /**
         * Человеко-читаемое описание ошибки.
         */
        @NotNull
        String description;
    }
}
