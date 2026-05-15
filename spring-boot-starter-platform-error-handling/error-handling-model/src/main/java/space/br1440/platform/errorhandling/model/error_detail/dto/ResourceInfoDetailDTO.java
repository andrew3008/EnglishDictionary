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
@JsonPropertyOrder({"resourceType", "resourceName", "description"})
public class ResourceInfoDetailDTO implements ErrorDetailDTO {

    /**
     * Тип ресурса. Например: {@code "order"}, {@code "user"}, {@code "template"}.
     * Может быть {@code null}, если сервер не раскрывает тип ресурса.
     */
    @Nullable
    String resourceType;

    /**
     * Имя или идентификатор ресурса. Например: {@code "orders/42"}, {@code "users/abc123"}.
     * Может быть {@code null}, если конкретный ресурс не определён.
     */
    @Nullable
    String resourceName;

    /**
     * Человеко-читаемое описание ошибки доступа к ресурсу.
     */
    @Nullable
    String description;
}
