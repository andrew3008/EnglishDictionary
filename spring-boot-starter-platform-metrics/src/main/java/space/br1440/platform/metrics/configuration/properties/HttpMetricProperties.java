package space.br1440.platform.metrics.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки HTTP-метрик (сервлетный стек и WebFlux): в первую очередь режим значения тега {@code path}.
 *
 * <p>Пример полной конфигурации (значения совпадают с дефолтами модуля):</p>
 * <pre>{@code
 * platform:
 *   metrics:
 *     http:
 *       path-mode: NORMALIZED
 * }</pre>
 *
 * <p>В YAML допустимы как {@code NORMALIZED}/{@code RAW}, так и {@code normalized}/{@code raw}
 * — Spring Boot сопоставляет их с {@link PathMode}.</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = HttpMetricProperties.PREFIX)
public class HttpMetricProperties {

    public static final String PREFIX = "platform.metrics.http";

    /**
     * Имя Timer HTTP-запросов (латентность). По умолчанию задаётся в {@code default.properties}.
     */
    private String timerMetricName;
    /**
     * Имя Counter HTTP-запросов. По умолчанию задаётся в {@code default.properties}.
     */
    private String counterMetricName;

    /**
     * Режим формирования значения тега {@code path} для HTTP timer/counter.
     * По умолчанию {@link PathMode#NORMALIZED}.
     */
    private PathMode pathMode = PathMode.NORMALIZED;

    public enum PathMode {
        /**
         * В метрику попадает шаблон endpoint’а из Spring MVC/WebFlux mapping (нормализованный path).
         */
        NORMALIZED,

        /**
         * В метрику попадает исходный request path без нормализации.
         */
        RAW
    }
}
