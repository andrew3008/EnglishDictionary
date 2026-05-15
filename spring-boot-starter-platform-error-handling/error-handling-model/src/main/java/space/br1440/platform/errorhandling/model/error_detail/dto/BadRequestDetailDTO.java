package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Singular;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Детали валидации значений полей запроса ({@code google.rpc.BadRequest}).
 *
 * <h2>Пример:</h2>
 * <pre>{@code
 * {
 *   "type": "BAD_REQUEST",
 *   "fieldViolations": [
 *     { "field": "email",          "reason": "INVALID_FORMAT",  "description": "must be a valid email address" },
 *     { "field": "items[0].qty",   "reason": "OUT_OF_RANGE",    "description": "must be between 1 and 100" }
 *   ]
 * }
 * }</pre>
 */
@Value
@EqualsAndHashCode
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"fieldViolations"})
public class BadRequestDetailDTO implements ErrorDetailDTO {

    @NotNull
    @Singular
    List<FieldViolation> fieldViolations;

    @Value
    @Builder(toBuilder = true)
    @Jacksonized
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"field", "reason", "description"})
    public static class FieldViolation {
        /**
         * Путь к полю в dot-нотации ({@code "email"}, {@code "items[0].quantity"}).
         */
        @Nullable
        String field;

        /**
         * Машиночитаемый код ошибки валидации ({@code UPPER_SNAKE_CASE}).
         * Например: {@code "INVALID_FORMAT"}, {@code "OUT_OF_RANGE"}, {@code "REQUIRED"}.
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
