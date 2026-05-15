package space.br1440.platform.metrics.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Правила тегов метрик: отключение выбранных тегов для конкретных имён метрик в Micrometer.
 *
 * <p>Пример конфигурации:</p>
 * <pre>{@code
 * platform:
 *   metrics:
 *     labels:
 *       metrics:
 *         "[platform.http.requests]":
 *           disabled-tags:
 *             - status
 *         "[platform.grpc.server.sent]":
 *           disabled-tags:
 *             - rpc_status_code
 * }</pre>
 *
 * <p>Ключ верхнего уровня карты {@link #metrics} — полное имя метрики, как оно зарегистрировано
 * в {@link io.micrometer.core.instrument.MeterRegistry} (в YAML чаще указывают в кавычках).</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = MetricLabelRulesProperties.PREFIX)
public class MetricLabelRulesProperties {

    public static final String PREFIX = "platform.metrics.labels";

    /**
     * Карта {@code имя_метрики → правило}. Ключ совпадает с именем метрики в Micrometer.
     * По умолчанию пустая; Spring Boot дополняет/заменяет записи из {@code application.yml}.
     */
    private Map<String, MetricRule> metrics = new HashMap<>();

    /**
     * Правило отключения тегов для одной именованной метрики.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class MetricRule {

        /**
         * Имена тегов, исключаемые из финального набора тегов метрики.
         * Пустой набор или {@code null} — фильтрация по тегам не применяется.
         */
        private Set<String> disabledTags;
    }
}
