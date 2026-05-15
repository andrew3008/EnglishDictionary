package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Доменная модель детали ошибки {@code RESOURCE_INFO} -- сведения о ресурсе, к которому
 * относится ошибка (см. <a href="https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto">google.rpc.ResourceInfo</a>).
 *
 * <h2>Дизайн</h2>
 * Иммутабельный value-объект (Lombok {@link Value @Value}): поля {@code final}, без сеттеров,
 * с {@code equals}/{@code hashCode}/{@code toString}. Конструируется только через статический
 * factory {@link #of(String, String, String)} -- он закрепляет контракт
 * «все три поля указываются за один шаг» и не допускает частично-инициализированных экземпляров.
 *
 * <h2>Использование</h2>
 * <pre>{@code
 * throw new ResourceNotFoundException(MY_ERROR_CODE, extra,
 *     List.of(ResourceInfoDetail.of("order", "orders/42", "Order not found or access denied")));
 * }</pre>
 */
@Value
public class ResourceInfoDetail implements ErrorDetail {

    /**
     * Тип ресурса в нижнем регистре, например {@code "order"}, {@code "user"}.
     * <p>
     * Допускает {@code null} для случаев, когда сервер не может или не должен
     * раскрывать тип ресурса (например, по соображениям безопасности).
     */
    @Nullable
    String resourceType;

    /**
     * Идентификатор ресурса, например {@code "orders/42"}, {@code "users/abc"}.
     * <p>
     * Допускает {@code null} для случаев, когда конкретный ресурс не определён
     * (например, при отказе из-за rate-limit на коллекции).
     */
    @Nullable
    String resourceName;

    /**
     * Человеко-читаемое описание ситуации с ресурсом, например {@code "Resource not found"}.
     * <p>
     * Допускает {@code null}, если описание избыточно при наличии {@code resourceType}/{@code resourceName}.
     */
    @Nullable
    String description;

    /**
     * Статический factory для создания детали о ресурсе.
     *
     * <p>Все три поля доступны через единый аргументный список -- исключает
     * частично-инициализированный объект и упрощает чтение call-site-а.
     *
     * <pre>{@code
     * ResourceInfoDetail.of("order", "orders/42", "Order not found or access denied")
     * }</pre>
     */
    @NotNull
    public static ResourceInfoDetail of(@Nullable String resourceType,
                                        @Nullable String resourceName,
                                        @Nullable String description) {
        return new ResourceInfoDetail(resourceType, resourceName, description);
    }
}
