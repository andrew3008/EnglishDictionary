package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Структурированный машиночитаемый идентификатор ошибки ({@code google.rpc.ErrorInfo}).
 *
 * <h2>Дизайн</h2>
 * Иммутабельный value-объект (Lombok {@link Value @Value}): все поля {@code final}, без сеттеров,
 * с {@code equals}/{@code hashCode}/{@code toString}. Сборка только через
 * {@link #builder()} / {@link #toBuilder()}. После {@code build()} ни {@code reason}/{@code domain},
 * ни мапы метаданных не могут быть изменены.
 *
 * <h2>Defensive copy для метаданных</h2>
 * Кастомные builder-методы (см. {@link ErrorInfoDetailBuilder#clientMetadata(Map)} и
 * {@link ErrorInfoDetailBuilder#diagnosticMetadata(Map)}) копируют входной {@link Map} в
 * {@link Collections#unmodifiableMap(Map) unmodifiableMap} -- защита от внешних мутаций
 * после {@code build()}. {@code null} нормализуется в {@link Collections#emptyMap()}.
 * Геттеры {@code getClientMetadata()} / {@code getDiagnosticMetadata()} возвращают
 * unmodifiable-маpу by construction.
 *
 * <h2>Сериализация</h2>
 * {@link Jacksonized @Jacksonized} -- Jackson десериализует через builder, а не через
 * package-private конструктор {@link Value @Value}. Это устраняет неявную зависимость от
 * конфигурации {@code ObjectMapper} (видимость конструкторов, {@code AUTO_DETECT_CREATORS}).
 *
 * @see <a href="https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto">google.rpc.ErrorInfo</a>
 */
@Value
@Builder(toBuilder = true)
@Jacksonized
public class ErrorInfoDetail implements ErrorDetail {

    /**
     * Машиночитаемый код причины ({@code UPPER_SNAKE_CASE}, max 63 символа).
     * Уникален в рамках {@link #domain}.
     */
    @Nullable
    String reason;

    /**
     * Домен, к которому относится {@link #reason}. Обычно -- имя сервиса или платформы
     * (например, {@code "order-service.platform.br1440.space"}).
     */
    @Nullable
    String domain;

    /**
     * Метаданные, безопасные для передачи клиенту (UI/DTO).
     * Ключи в {@code lowerCamelCase}, значения без sensitive data.
     * Стабильный API-контракт: однажды добавленный ключ не удаляется между релизами (AIP-193).
     */
    @NotNull
    @Builder.Default
    Map<String, String> clientMetadata = Collections.emptyMap();

    /**
     * Диагностические метаданные для SRE и разработчиков -- логи, трейсинг, дашборды.
     * <p>
     * Может содержать: класс исключения, полное сообщение {@code cause.getMessage()},
     * remote host/port, stack hint (1-3 фрейма), vendor-specific коды.
     * <b>Никогда не включается в DTO</b> -- передаётся только внутри {@code ErrorEntry}.
     */
    @NotNull
    @Builder.Default
    Map<String, String> diagnosticMetadata = Collections.emptyMap();

    /**
     * Расширение Lombok-сгенерированного builder-а: defensive copy для {@code Map}-полей.
     *
     * <p>Lombok видит уже объявленный inner-класс {@code ErrorInfoDetailBuilder} и дописывает
     * в него все остальные сгенерированные методы ({@code reason}, {@code domain}, {@code build}, ...).
     */
    public static class ErrorInfoDetailBuilder {

        /**
         * Устанавливает {@code clientMetadata} с защитной копией:
         * <ul>
         *   <li>{@code null} или пустой {@link Map} -> {@link Collections#emptyMap()};</li>
         *   <li>иначе -> {@link Collections#unmodifiableMap(Map) unmodifiableMap} над
         *       {@link LinkedHashMap} (сохраняет порядок ключей).</li>
         * </ul>
         * Защита от внешней мутации переданной {@link Map} после вызова {@code build()}.
         */
        public ErrorInfoDetailBuilder clientMetadata(@Nullable Map<String, String> map) {
            this.clientMetadata$value = (map == null || map.isEmpty())
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(map));
            this.clientMetadata$set = true;
            return this;
        }

        /**
         * Устанавливает {@code diagnosticMetadata} с защитной копией.
         * Семантика идентична {@link #clientMetadata(Map)}.
         */
        public ErrorInfoDetailBuilder diagnosticMetadata(@Nullable Map<String, String> map) {
            this.diagnosticMetadata$value = (map == null || map.isEmpty())
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(map));
            this.diagnosticMetadata$set = true;
            return this;
        }
    }
}
