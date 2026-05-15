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
@JsonPropertyOrder({"links"})
public class HelpDetailDTO implements ErrorDetailDTO {

    /**
     * Список ссылок на документацию.
     */
    @NotNull
    @Singular
    List<Link> links;

    @Value
    @Builder(toBuilder = true)
    @Jacksonized
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"description", "url"})
    public static class Link {

        /**
         * Текстовое описание ссылки (plain text, не HTML/Markdown).
         * Пригодно для отображения как текст гиперссылки в UI.
         */
        @NotNull
        String description;

        /**
         * Абсолютный URL (включая схему {@code https://}).
         */
        @NotNull
        String url;
    }
}
