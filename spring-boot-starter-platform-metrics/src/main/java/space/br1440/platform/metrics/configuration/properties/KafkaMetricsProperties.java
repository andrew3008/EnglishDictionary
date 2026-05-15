package space.br1440.platform.metrics.configuration.properties;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.Set;

/**
 * Настройки платформенных Kafka-метрик.
 *
 * <p>В том же префиксе {@value #PREFIX} задаются <b>имена</b> метрик (для переопределения
 * в {@code application.yml}) и операционные подсекции (кардинальность, гистограммы, offset-lag).</p>
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = KafkaMetricsProperties.PREFIX)
public class KafkaMetricsProperties {

    public static final String PREFIX = "platform.metrics.kafka";

    // --- Имена метрик (дефолты в classpath:default.properties) ---

    private String consumerLagMetricName;
    private String consumerWaitMetricName;
    private String consumerTotalMetricName;
    private String consumerProcessingMetricName;
    private String batchProcessingMetricName;
    private String batchMessagesTotalMetricName;
    /** Имя Gauge offset-lag (если используется binder'ом вне стартера). */
    private String consumerOffsetLagMetricName;
    private String producerLagMetricName;
    private String producerTotalMetricName;

    /** Настройки защиты от взрыва кардинальности тегов. */
    @NestedConfigurationProperty
    private CardinalityGuard cardinalityGuard = new CardinalityGuard();

    /** Настройки histogram'ов и percentiles для Kafka-Timer'ов. */
    @NestedConfigurationProperty
    private Histogram histogram = new Histogram();

    /** Настройки {@code ConsumerLagMeterBinder} (Gauge offset-lag). */
    @NestedConfigurationProperty
    private ConsumerLag consumerLag = new ConsumerLag();

    /**
     * Защита от взрыва кардинальности значений тегов.
     * Значения сверх лимита заменяются на {@link #quarantineValue}.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class CardinalityGuard {

        /**
         * Включить подмену high-cardinality значений тегов.
         * По умолчанию {@code true}.
         */
        private boolean enabled = true;

        /**
         * Максимум уникальных значений на пару (имя метрики, имя тега).
         * По умолчанию {@code 1000}.
         */
        private int maxUniqueValues = 1000;

        /**
         * Карантинное значение тега при превышении лимита.
         * По умолчанию {@code "high_cardinality_other"}.
         */
        private String quarantineValue = "high_cardinality_other";
    }

    /**
     * Настройки histogram'ов: server-side percentile-histogram и client-side percentiles.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class Histogram {

        /**
         * Публиковать HDR percentile-histogram — необходимо для server-side вычисления
         * квантилей в Prometheus и SLO-алертов.
         * По умолчанию {@code true}.
         */
        private boolean percentileHistogramEnabled = true;

        /**
         * Client-side percentiles (публикуются как отдельные Gauge-серии).
         * По умолчанию пусто — рекомендуется server-side через
         * {@link #percentileHistogramEnabled}.
         */
        private double[] percentiles = new double[]{};
    }

    /**
     * Настройки опроса consumer lag через {@code AdminClient}
     * (метрика {@code platform_consumer_offset_lag}).
     */
    @Getter @Setter @Accessors(chain = true)
    public static class ConsumerLag {

        /**
         * Включить регулярный опрос offset-lag.
         * По умолчанию {@code true}.
         */
        private boolean enabled = true;

        /** Период опроса в секундах. По умолчанию {@code 30}. */
        private long pollIntervalSeconds = 30;

        /**
         * Задержка перед первым опросом после старта (секунды).
         * По умолчанию {@code 0}.
         */
        private long initialPollDelaySeconds = 0;

        /**
         * Начальный backoff при ошибке broker'а (секунды).
         * Удваивается при каждой последующей ошибке до {@link #maxBackoffSeconds}.
         */
        private long initialBackoffSeconds = 5;

        /** Верхняя граница backoff'а. По умолчанию {@code 300} (5 минут). */
        private long maxBackoffSeconds = 300;

        /**
         * Группы, для которых offset-lag не рассчитывается.
         * Полезно для исключения тестовых или служебных групп.
         */
        private Set<String> excludedGroups = Collections.emptySet();

        /**
         * Включить активную чистку stale-Gauge'ев при исчезновении consumer-группы.
         * По умолчанию {@code true}.
         */
        private boolean evictStaleEnabled = true;

        /**
         * Количество подряд пропущенных poll-циклов до удаления stale-серии.
         * По умолчанию {@code 2}; минимум {@code 1}.
         */
        @Min(1)
        private int staleEvictionMissedPolls = 2;
    }
}
