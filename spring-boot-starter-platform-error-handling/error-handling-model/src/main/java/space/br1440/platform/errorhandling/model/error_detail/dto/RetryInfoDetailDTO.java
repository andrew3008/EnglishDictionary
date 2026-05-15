package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.Nullable;

@Value
@EqualsAndHashCode
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"retryDelay"})
public class RetryInfoDetailDTO implements ErrorDetailDTO {

    /**
     * Минимальная рекомендуемая задержка до следующего повтора в ISO-8601 формате.
     * Например: {@code PT30S}, {@code PT2M}. Значение {@code null} означает
     * "retryable без фиксированной задержки".
     */
    @Nullable
    String retryDelay;
}
