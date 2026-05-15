package space.br1440.platform.errorhandling.model.error_detail.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

@Value
@EqualsAndHashCode
@Builder(toBuilder = true)
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"reason", "domain", "metadata"})
public class ErrorInfoDetailDTO implements ErrorDetailDTO {

    /**
     * Значение-индикатор (sentinel) для поля {@code reason} в сериализованном теле ошибки, если во
     * внутренней модели нет осмысленной причины, заданной доменом.
     *
     * <p>Согласовано с {@code google.rpc.ErrorInfo.reason}: короткая строка в стиле {@code SCREAMING_SNAKE_CASE},
     * описывающая суть ошибки. Литерал {@code UNSPECIFIED} означает, что исходный домен не указал причину.
     *
     * <p><strong>Стабильность сериализованного контракта:</strong> изменение этой строки — несовместимое
     * изменение (breaking change), требующее повышения major-версии модели ошибок.
     *
     * @see <a href="https://google.aip.dev/193">AIP-193: Errors</a>
     */
    public static final String REASON_UNSPECIFIED = "UNSPECIFIED";

    /**
     * Значение-индикатор (sentinel) для поля {@code domain}, если во внутренней модели не задан
     * исходный домен (в JSON — пустая строка).
     *
     * <p>Согласовано с {@code google.rpc.ErrorInfo.domain} (reverse-DNS или логический идентификатор сервиса).
     * Пустая строка означает, что ошибка не отнесена к конкретному домену.
     *
     * <p><strong>Стабильность сериализованного контракта:</strong> изменение этой конвенции —
     * несовместимое изменение (breaking change), требующее повышения major-версии модели ошибок.
     */
    public static final String DOMAIN_UNSPECIFIED = "";

    /**
     * Машиночитаемый код причины ошибки ({@code UPPER_SNAKE_CASE}, max 63 символа).
     * Уникален в рамках {@link #domain}.
     * Например: {@code "API_DISABLED"}, {@code "STOCKOUT"}, {@code "RATE_LIMIT_EXCEEDED"}.
     * <p>
     * Если причина не задана во внутренней модели, при маппинге подставляется
     * {@link #REASON_UNSPECIFIED} (см. {@link space.br1440.platform.errorhandling.model.mapping.ErrorEntryToDtoMapper}).
     */
    @NotNull
    String reason;

    /**
     * Домен, к которому относится {@link #reason}.
     * Обычно — имя сервиса или платформы.
     * Например: {@code "order-service.platform.br1440.space"}, {@code "billing.platform.br1440.space"}.
     * <p>
     * Если домен не задан, в JSON попадает {@link #DOMAIN_UNSPECIFIED}.
     */
    @NotNull
    String domain;

    /**
     * Дополнительные структурированные метаданные.
     * Ключи: {@code lowerCamelCase}, max 64 символа.
     * Стабильны между релизами — однажды добавленный ключ не удаляется.
     */
    @Nullable
    @Builder.Default
    Map<String, String> metadata = Collections.emptyMap();

}
