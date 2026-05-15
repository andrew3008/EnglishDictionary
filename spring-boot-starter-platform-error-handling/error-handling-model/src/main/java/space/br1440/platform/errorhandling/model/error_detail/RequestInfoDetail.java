package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Доменная модель детали ошибки {@code REQUEST_INFO} -- идентификаторы запроса,
 * позволяющие пользователю/SRE процитировать конкретный вызов при обращении в
 * поддержку
 * (см. <a href="https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto">
 * google.rpc.RequestInfo</a>).
 *
 * <h2>Дизайн</h2>
 * Иммутабельный value-объект (Lombok {@link Value @Value}): поля {@code final}, без сеттеров,
 * с {@code equals}/{@code hashCode}/{@code toString}. Конструируется через статический factory
 * {@link #of(String, String)} -- единый аргументный список исключает частично
 * инициализированный экземпляр.
 *
 * <h2>Семантика полей</h2>
 * <ul>
 *   <li>{@link #requestId} -- уникальный идентификатор запроса, достаточный для того, чтобы
 *       клиент мог привести его при контакте с поддержкой (typically correlation-id
 *       {@code X-Correlation-Id} / trace-id W3C Trace Context).</li>
 *   <li>{@link #servingData} -- произвольная internal-метка, позволяющая серверной стороне
 *       воспроизвести контекст запроса в диагностике. Не предназначена для машиночитаемой
 *       обработки клиентом; может быть пустой.</li>
 * </ul>
 *
 * <h2>Использование</h2>
 * <pre>{@code
 * throw new InternalException(MY_ERROR_CODE, extra,
 *     List.of(RequestInfoDetail.of(MDC.get("X-Correlation-Id"),
 *                                   "region=eu-west-1;replica=42")));
 * }</pre>
 *
 * @see <a href="https://google.aip.dev/193">Google AIP-193: Errors</a>
 */
@Value
public class RequestInfoDetail implements ErrorDetail {

    /**
     * Идентификатор запроса, на который клиенту следует ссылаться при обращении в поддержку.
     * Типично -- correlation-id или trace-id (W3C {@code traceparent}, Zipkin B3, UUID v4).
     *
     * <p>Допускает {@code null} -- если идентификатор не зарезолвлен, сервер может передать
     * detail только со {@link #servingData}.
     */
    @Nullable
    String requestId;

    /**
     * Непрозрачный internal-маркер, помогающий серверной стороне воспроизвести контекст
     * обработанного запроса (например, партиция/сегмент/реплика).
     *
     * <p>Клиент <b>не должен</b> ни парсить, ни модифицировать это значение; оно -- часть
     * диагностического контракта сервера с самим собой. Допускает {@code null}.
     */
    @Nullable
    String servingData;

    /**
     * Статический factory для построения {@link RequestInfoDetail} в одну строку.
     *
     * <p>Оба параметра nullable -- практический compromise: иногда доступен только
     * {@code requestId} (например, первичный лог-инцидент), иногда только {@code servingData}
     * (асинхронный дамп без correlation-id).
     *
     * @param requestId   идентификатор запроса для клиентского support-обращения (nullable)
     * @param servingData internal диагностический маркер (nullable)
     * @return новый immutable {@link RequestInfoDetail}
     */
    @NotNull
    public static RequestInfoDetail of(@Nullable String requestId, @Nullable String servingData) {
        return new RequestInfoDetail(requestId, servingData);
    }
}
