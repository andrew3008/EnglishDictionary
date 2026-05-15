package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Singular;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@Value
@EqualsAndHashCode
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"violations"})
public class PreconditionFailureDetailDTO implements ErrorDetailDTO {

    /**
     * Список нарушенных предусловий.
     */
    @NotNull
    @Singular
    List<Violation> violations;

    @Value
    @Builder(toBuilder = true)
    @Jacksonized
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"type", "subject", "description"})
    public static class Violation {

        /**
         * Тип нарушенного предусловия ({@code UPPER_SNAKE_CASE}).
         * Например: {@code "TERMS_OF_SERVICE"}, {@code "STATUS_TRANSITION"}, {@code "QUOTA"}.
         */
        @NotNull
        String type;

        /**
         * Субъект, относительно которого нарушено предусловие.
         * Например: {@code "order/42"}, {@code "user/123"}.
         */
        @NotNull
        String subject;

        /**
         * Человеко-читаемое описание нарушения.
         */
        @NotNull
        String description;
    }
}
