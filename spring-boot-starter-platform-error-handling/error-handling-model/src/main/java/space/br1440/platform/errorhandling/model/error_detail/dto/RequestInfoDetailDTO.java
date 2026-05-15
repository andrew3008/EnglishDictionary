package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.Nullable;

/**
 * Wire-контракт детали {@code REQUEST_INFO} (см.
 * {@code space.br1440.platform.errorhandling.model.error_detail.RequestInfoDetail}).
 *
 * <p>Оба поля nullable и включаются в сериализацию только если заданы
 * ({@link JsonInclude JsonInclude.NON_NULL}).
 */
@Value
@EqualsAndHashCode
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"requestId", "servingData"})
public class RequestInfoDetailDTO implements ErrorDetailDTO {

    /**
     * Идентификатор запроса, который клиент должен процитировать при обращении в поддержку.
     */
    @Nullable
    String requestId;

    /**
     * Internal serving-data. Клиент <b>не должен</b> его парсить; значение передаётся
     * для удобства серверной диагностики.
     */
    @Nullable
    String servingData;
}
