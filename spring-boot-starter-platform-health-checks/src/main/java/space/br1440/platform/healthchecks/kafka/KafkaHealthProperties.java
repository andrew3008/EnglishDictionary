package space.br1440.platform.healthchecks.kafka;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;
import space.br1440.platform.healthchecks.async.AsyncHealthRefreshPolicy;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.kafka.validation.ValidConsumerCheck;
import space.br1440.platform.healthchecks.validation.PositiveDuration;
import space.br1440.platform.healthchecks.validation.ValidAsyncTimeout;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Kafka health check выполняется в фоновом потоке с кэшированием результата,
 * поскольку {@code AdminClient} операции ({@code listTopics}, {@code describeCluster})
 * используют {@code request.timeout.ms=30s} и {@code default.api.timeout.ms=60s}
 * по умолчанию — значения, несовместимые с Kubernetes readiness probe
 * (<a href="https://kafka.apache.org/documentation/#adminclientconfigs">
 * Apache Kafka — AdminClient Configs</a>).
 * Async-модель позволяет применять fast-fail {@code requestTimeout=5s}
 * без риска блокировки потока Actuator.
 * <p>
 * Kubernetes probe ──→ Actuator /health ──→ кэш (мгновенно) ──→ ответ
 * <p>
 *                      параллельно, независимо:
 * <p>
 * Scheduler ──→ каждые 30s (refreshInterval) ──→ async задача
 *                                                ├─ AdminClient.listTopics() (≤ 5s requestTimeout)
 *                                                ├─ JVM/scheduling overhead
 *                                                └─ всё это ограничено healthCheckTimeoutSeconds (10s)
 *                                                └─ результат пишется в кэш
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = KafkaHealthProperties.PREFIX)
public class KafkaHealthProperties {

    public static final String PREFIX = "platform.health.kafka";

    private boolean enabled = true;

    @NotNull(message = "Probes cannot be null or empty")
    @Size(min = 1, message = "At least one probe must be specified")
    private Set<HealthProbe> probes = EnumSet.of(HealthProbe.READINESS);

    @Valid
    @NestedConfigurationProperty
    private final Async async = new Async();

    @Valid
    @NestedConfigurationProperty
    private final Timeout timeout = new Timeout();

    @Valid
    @NestedConfigurationProperty
    private final Cluster cluster = new Cluster();

    @Valid
    @NestedConfigurationProperty
    private final Topics topics = new Topics();

    @Valid
    @NestedConfigurationProperty
    private final Producer producer = new Producer();

    @Valid
    @NestedConfigurationProperty
    private final Consumer consumer = new Consumer();

    @ValidAsyncTimeout
    @Getter @Setter @Accessors(chain = true)
    public static class Async implements AsyncHealthRefreshPolicy {

        private boolean enabled = true;

        /**
         * Максимальное время выполнения одного фонового Kafka health check.
         *
         * <p>Должен превышать {@code requestTimeout} (5s) с запасом на внутренние
         * операции {@code AdminClient}: metadata refresh, координатор брокера, leader lookup.
         * Эти операции выполняются неявно при каждом вызове {@code listTopics()} / {@code describeCluster()}
         * и могут увеличить фактическое время ответа относительно сетевого round-trip.
         * См. <a href="https://cwiki.apache.org/confluence/display/KAFKA/KIP-533">
         * KIP-533 — Add default api timeout to AdminClient</a>.
         *
         * <p>Значение {@code 10s} = {@code requestTimeout (5s)} × 2 — стандартный коэффициент
         * запаса для async wrapper над операциями с недетерминированным overhead.
         */
        @NotNull(message = "Async check timeout must not be null")
        @PositiveDuration(message = "Async check timeout must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration healthCheckTimeout = Duration.ofSeconds(10);

        /**
         * Интервал между фоновыми обновлениями результата Kafka health check.
         *
         * <p>Значение {@code 30s} обеспечивает актуальность данных в пределах трёх
         * Kubernetes probe-периодов (при {@code periodSeconds=10}), не создавая
         * избыточной нагрузки на Kafka-брокер административными запросами.
         * AWS MSK рекомендует минимизировать частоту AdminClient операций в production-окружении.
         * См. <a href="https://docs.aws.amazon.com/msk/latest/developerguide/bestpractices-kafka-client.html">
         * AWS MSK — Best practices for Apache Kafka clients</a>.
         */
        @NotNull(message = "Async refresh interval must not be null")
        @PositiveDuration(message = "Async refresh interval must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration refreshInterval = Duration.ofSeconds(30);

        /**
         * Время жизни устаревшего результата Kafka health check.
         * По истечении, если фоновый поток не обновил результат,
         * health check возвращает {@code DOWN} вместо последнего известного статуса.
         *
         * <p>Значение {@code 120s} = 4 × {@code refreshInterval (30s)}: допускает
         * до 3 пропущенных циклов обновления подряд (например, при кратковременной
         * перегрузке брокера или thread pool starvation) до перехода в {@code DOWN}.
         * Это согласуется с Kubernetes {@code failureThreshold=3} как стандартным
         * значением для readiness probe
         * (<a href="https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/">
         * Kubernetes — Configure Liveness, Readiness and Startup Probes</a>).
         */
        @NotNull(message = "Async stale TTL must not be null")
        @PositiveDuration(message = "Async stale TTL must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration staleResultTtl = Duration.ofSeconds(120);
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Timeout {

        /**
         * Максимальное время ожидания ответа от Kafka-брокера на health check запрос
         * (например, {@code AdminClient.listTopics()} или {@code describeCluster()}).
         *
         * <p><b>Обоснование дефолта 5s.</b>
         * Apache Kafka {@code AdminClient} имеет следующие дефолтные значения:
         * <ul>
         *   <li>{@code request.timeout.ms = 30000} (30s) — таймаут одного сетевого запроса;</li>
         *   <li>{@code default.api.timeout.ms = 60000} (60s) — таймаут всей операции с retry.</li>
         * </ul>
         * Оба значения непригодны для health check в контексте Kubernetes readiness probe:
         * зависание AdminClient на 30–60s вызовет каскадные рестарты пода.
         * <b>Значение 5s — осознанное уменьшение для fast-fail семантики health check</b>,
         * что является стандартной практикой при использовании AdminClient
         * для мониторинга, а не для бизнес-операций.
         * См. <a href="https://kafka.apache.org/documentation/#adminclientconfigs">
         * Apache Kafka — AdminClient Configs (request.timeout.ms, default.api.timeout.ms)</a>.
         *
         * <p><b>Ограничение:</b> значение должно быть меньше {@code timeoutSeconds} Kubernetes readiness probe.
         * AWS MSK рекомендует {@code request.timeout.ms >= 10s} для production AdminClient операций
         * (<a href="https://docs.aws.amazon.com/msk/latest/developerguide/bestpractices-kafka-client.html">
         * AWS MSK — Best practices for Apache Kafka clients</a>);
         * для health check fast-fail допустимо меньшее значение, так как цель —
         * обнаружение недоступности брокера, а не гарантия доставки.
         */
        @NotNull(message = "Request timeout must not be null")
        @PositiveDuration(message = "Request timeout must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration requestTimeout = Duration.ofSeconds(5);
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Cluster {
        @Min(value = 1, message = "Minimum broker count must be at least 1")
        private int minBrokerCount = 1;
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Topics {
        private boolean enabled = false;

        @Valid
        @Size(max = 100, message = "Monitored topics list must not exceed 100 entries")
        private List<@NotBlank(message = "Topic name must not be blank") String> monitoredTopics;
    }

    @Getter @Setter @Accessors(chain = true)
    public static class Producer {
        private boolean enabled = false;
    }

    @ValidConsumerCheck
    @Getter @Setter @Accessors(chain = true)
    public static class Consumer {
        private boolean enabled = false;

        private String groupId;
    }
}
