package space.br1440.platform.errorhandling.model.error_detail;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Доменная модель детали ошибки {@code HELP} -- ссылки на документацию или каталог ошибок
 * (см. <a href="https://cloud.google.com/apis/design/errors#standard_error_payloads">google.rpc.Help</a>).
 * <p>
 * Кастомный shortcut {@code addLink(description, url)} в {@link HelpDetailBuilder}
 * позволяет писать пары без явного создания {@link Link}:
 *
 * <pre>{@code
 * throw new SomeBusinessException(MY_ERROR_CODE, extra,
 *     List.of(HelpDetail.builder()
 *         .addLink("Migration guide", "https://docs.example.com/migration/v2")
 *         .addLink("API reference",   "https://docs.example.com/api/orders")
 *         .build()));
 * }</pre>
 *
 * <h2>Каталог ошибок</h2>
 * Для типового сценария «одна ссылка на каталог ошибок по {@code errorCode}» используется
 * статический factory {@link #of(String, String)} -- закрепляет общеплатформенный формат
 * URL-а ({@code baseUrl + "/" + errorCode}) и единое описание.
 */
@Value
@Builder(toBuilder = true)
public class HelpDetail implements ErrorDetail {

    /**
     * Список ссылок на документацию. Гарантированно non-null и immutable (см. {@link Singular @Singular}).
     */
    @NotNull
    @Singular
    List<Link> links;

    /**
     * Расширение Lombok-сгенерированного builder-а: shortcut для добавления
     * {@link Link} двумя строковыми аргументами без явного построения объекта.
     *
     * <p>Lombok видит уже объявленный inner-класс {@code HelpDetailBuilder}
     * и дописывает в него все остальные сгенерированные методы (включая
     * {@code link(Link)}, {@code links(Collection)}, {@code clearLinks()}, {@code build()}).
     */
    public static class HelpDetailBuilder {

        public HelpDetailBuilder addLink(String description, String url) {
            return link(new Link(description, url));
        }
    }

    public static HelpDetail of(String baseUrl, String errorCode) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");

        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return HelpDetail.builder()
                .addLink("See error catalog for details and resolution steps", normalizedBaseUrl + "/" + errorCode)
                .build();
    }

    /**
     * Иммутабельный value-объект единичной ссылки на документацию.
     */
    @Value
    public static class Link {

        /**
         * Человеко-читаемое описание ссылки. Например: {@code "Migration guide"}, {@code "API reference"}.
         */
        @NotNull
        String description;

        /**
         * Абсолютный URL.
         */
        @NotNull
        String url;
    }
}
