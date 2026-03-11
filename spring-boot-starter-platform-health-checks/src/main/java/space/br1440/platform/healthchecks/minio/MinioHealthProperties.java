package space.br1440.platform.healthchecks.minio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;
import space.br1440.platform.healthchecks.async.AsyncHealthRefreshPolicy;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.minio.validation.ValidBucketName;
import space.br1440.platform.healthchecks.minio.validation.ValidSentinelCheck;
import space.br1440.platform.healthchecks.validation.PositiveDuration;
import space.br1440.platform.healthchecks.validation.ValidAsyncTimeout;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Архитектурная схема одного вызова doHealthCheck()
 * doHealthCheck()
 * │
 * ├─ [1] Connectivity probe  ← probeBucket / listBuckets
 * │       DOWN при ошибке
 * │
 * ├─ [2] Bucket monitoring   ← monitoredBuckets (всегда выполняется после [1] опционально)
 * │       Warning в details, статус не меняется
 * │
 * └─ [3] Sentinel check      ← опционально
 *         Warning/error в details
 *
 * <p><b>Асинхронная модель выполнения.</b> В отличии, например, от JDBC-зависимостей (PostgreSQL),
 * health check MinIO выполняется в фоновом потоке с кэшированием результата. Это обосновано следующим:
 * <ol>
 *   <li>Каждая проверка требует HTTP round-trip (TCP + TLS handshake при отсутствии keep-alive).</li>
 *   <li>Суммарный клиентский timeout ({@code connectTimeout=3s + healthCheckTimeout=5s})
 *       превышает Kubernetes readiness probe default ({@code timeoutSeconds=1s})
 *       и может не уложиться даже в расширенный {@code timeoutSeconds=5s}
 *       (<a href="https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/">
 *       Kubernetes — Configure Liveness, Readiness and Startup Probes</a>).</li>
 *   <li>Выполнение медленных зависимостей в потоке Kubernetes probe
 *       блокирует Actuator и может вызвать каскадные рестарты пода.
 *       Async-модель с кэшированием результата — признанный паттерн для таких зависимостей.
 *       См. <a href="https://github.com/spring-projects/spring-boot/issues/2652">
 *       Spring Boot #5910 — Asynchronous Scheduled Health Indicators</a>.</li>
 * </ol>
 *
 * @see <a href="https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html">
 * AWS SDK for Java — Configuring Timeouts</a>
 * @see <a href="https://artifacthub.io/packages/helm/bitnami/minio">
 * Bitnami MinIO Helm Chart — ArtifactHub</a>
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = MinioHealthProperties.PREFIX)
public class MinioHealthProperties {

    public static final String PREFIX = "platform.health.minio";

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
    private final Buckets buckets = new Buckets();

    @Valid
    @NestedConfigurationProperty
    private final SentinelCheck sentinelCheck = new SentinelCheck();

    @ValidAsyncTimeout
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Async implements AsyncHealthRefreshPolicy {

        private boolean enabled = true;

        /**
         * Максимальное время выполнения одного фонового health check.
         *
         * <p>Должен превышать суммарный timeout синхронного клиента:
         * {@code connectTimeout + healthCheckTimeout} (по умолчанию 3s + 5s = 8s).
         * Запас необходим для учёта JVM-накладных расходов, внутренних retry
         * HTTP-клиента и сетевых флуктуаций. Рекомендуемый запас — не менее 50%
         * от суммарного клиентского timeout.
         *
         * <p>При {@code healthCheckTimeout < connectTimeout + healthCheckTimeout}
         * async-поток будет прерван до получения ответа от MinIO,
         * что приведёт к ложному {@code UNKNOWN} статусу.
         */
        @NotNull(message = "Async health check timeout must not be null")
        @PositiveDuration(message = "Async health check timeout must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration healthCheckTimeout = Duration.ofSeconds(15);

        /**
         * Интервал между фоновыми обновлениями результата health check.
         *
         * <p>Определяет, как часто async-поток заново проверяет MinIO.
         * Значение {@code 30s} — разумный баланс между актуальностью данных и нагрузкой
         * на MinIO: при periodSeconds=10 Kubernetes probe получит свежий результат
         * не старше трёх probe-периодов.
         */
        @NotNull(message = "Async refresh interval must not be null")
        @PositiveDuration(message = "Async refresh interval must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration refreshInterval = Duration.ofSeconds(30);

        /**
         * Время жизни устаревшего результата health check.
         * По истечении, если фоновый поток не обновил результат,
         * health check возвращает {@code DOWN} вместо последнего известного статуса.
         *
         * <p>Должен быть кратно больше {@code refreshInterval}.
         * Значение {@code 120s} = 4 × {@code refreshInterval (30s)}: допускает до 3 пропущенных
         * обновлений подряд (например, при кратковременной перегрузке пула потоков)
         * до перехода в {@code DOWN}.
         */
        @NotNull(message = "Async stale result TTL must not be null")
        @PositiveDuration(message = "Async stale result TTL must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration staleResultTtl = Duration.ofSeconds(120);
    }

    /**
     * Настройки таймаутов HTTP-клиента при обращении к MinIO в процессе health check.
     *
     * <p>Все значения должны в совокупности укладываться в {@code timeoutSeconds}
     * Kubernetes readiness probe (рекомендуемое значение для object storage: >= 10s).
     */
    @Getter @Setter  @Accessors(chain = true)
    public static class Timeout {

        /**
         * Максимальное время установки TCP-соединения с MinIO.
         *
         * <p>Ограничивает фазу TCP handshake (SYN → SYN-ACK → ACK). После истечения
         * соединение разрывается немедленно, health check возвращает {@code DOWN}.
         *
         * <p>Значение 3s — de-facto стандарт для клиентских TCP connect timeout
         * при обращении к internal-сервисам в стабильной сети:
         * AWS SDK for C++ использует 3s как дефолтный connect timeout для tier-1 сервисов
         * (<a href="https://github.com/aws/aws-sdk-cpp/issues/57">aws-sdk-cpp #57</a>).
         * MinIO Java Client API явно разделяет {@code connectTimeout}, {@code writeTimeout}
         * и {@code readTimeout} как самостоятельные параметры
         * (<a href="https://minio-java.min.io/index.html?io/minio/MinioClient.html">MinioClient API</a>).
         * RFC 1122 (Host Requirements) определяет начальный RTO в 3s как базовый порог
         * для TCP retransmission, что соответствует интуиции «3s — граница fast-fail»
         * (<a href="https://datatracker.ietf.org/doc/html/rfc1122">IETF RFC 1122</a>).
         */
        @NotNull(message = "Connect timeout must not be null")
        @PositiveDuration(message = "Connect timeout must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration connectTimeout = Duration.ofSeconds(3);

        /**
         * Максимальное время ожидания ответа от MinIO на health check запрос
         * ({@code statObject} / {@code bucketExists} / {@code listBuckets}).
         *
         * <p>Отсчитывается после установки соединения: включает отправку HTTP-запроса
         * и получение ответа (заголовки + тело). Превышение → {@code DOWN}.
         *
         * <p>Значение 5s обосновано следующим:
         * AWS SDK for Java 2.x рекомендует раздельно задавать {@code connectionTimeout}
         * и {@code apiCallAttemptTimeout}.
         *
         * <p><b>Требование к Kubernetes probe:</b> значение этого поля плюс {@code connectTimeout}
         * должны суммарно укладываться в {@code timeoutSeconds} Kubernetes readiness probe.
         * Kubernetes default — 1s
         * (<a href="https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/">
         * Kubernetes — Configure Liveness, Readiness and Startup Probes</a>);
         * Bitnami MinIO Helm chart также использует 1s по умолчанию
         * (<a href="https://artifacthub.io/packages/helm/bitnami/minio">
         * Bitnami MinIO — ArtifactHub</a>).
         * При использовании данного стартера с дефолтом 5s необходимо явно задать
         * {@code readinessProbe.timeoutSeconds >= 10} в Helm values.
         */
        @NotNull(message = "Health check timeout must not be null")
        @PositiveDuration(message = "Health check timeout must be positive")
        @DurationUnit(ChronoUnit.SECONDS)
        private Duration healthCheckTimeout = Duration.ofSeconds(5);
    }

    /**
     * Настройка проверок на уровне бакетов MinIO.
     *
     * <p>Поддерживает два режима:
     * <ul>
     *   <li><b>probeBucket</b> — структурная проверка: MinIO считается доступным тогда
     *       и только тогда, когда указанный бакет существует. Влияет на статус health check.</li>
     *   <li><b>monitoredBuckets</b> — наблюдаемость (observability): проверяет наличие
     *       бизнес-бакетов и публикует результат в {@code details}, не влияя на итоговый статус.
     *       Отсутствие бакета фиксируется как предупреждение, но не вызывает перезапуск пода,
     *       поскольку перезапуск не устраняет причину (бакет восстанавливается вручную).</li>
     * </ul>
     */
    @Getter @Setter @Accessors(chain = true)
    public static class Buckets {

        /**
         * Имя бакета для структурной проверки доступности MinIO ({@code bucketExists}).
         * <p>
         * Если задан — health check возвращает {@code DOWN}, когда бакет недоступен.
         * Если не задан — выполняется облегчённая проверка через {@code listBuckets()}.
         */
        @ValidBucketName
        private String probeBucket;

        /**
         * Список бизнес-бакетов для мониторинга.
         * <p>
         * Результат проверки каждого бакета публикуется в {@code details} ответа Actuator
         * в виде предупреждения ({@code bucketsWarning}), но не изменяет итоговый статус
         * health check. Используется исключительно для observability.
         */
        @Valid
        @Size(max = 100, message = "Monitored buckets list must not exceed 100 entries")
        private List<@ValidBucketName String> monitoredBuckets;
    }

    /**
     * Настройка sentinel-проверки: углублённая верификация полного пути чтения до конкретного объекта.
     *
     * <p>В отличие от {@link Buckets}, которая проверяет доступность MinIO на уровне бакетов
     * ({@code bucketExists}, {@code listBuckets}), sentinel-проверка использует
     * {@code statObject(bucket, objectKey)} — аналог HTTP HEAD: запрос метаданных объекта
     * без скачивания содержимого. Это позволяет верифицировать полный путь чтения:
     * <ol>
     *   <li>TCP-соединение с MinIO установлено.</li>
     *   <li>Бакет существует и доступен.</li>
     *   <li>Объект существует по указанному ключу.</li>
     *   <li>У учётных данных приложения есть право на чтение ({@code s3:GetObject} / {@code StatObject}).</li>
     * </ol>
     *
     * <p>Соответствует паттерну <i>Synthetic Resource Health Check</i>: для проверки
     * используется выделенный технический объект, а не бизнес-данные.
     *
     * @see <a href="https://microservices.io/patterns/observability/health-check-api.html">
     * Health Check API Pattern — microservices.io</a>
     */
    @ValidSentinelCheck @Getter @Setter @Accessors(chain = true)
    public static class SentinelCheck {

        private boolean enabled = false;

        @ValidBucketName
        private String sentinelBucket;

        /**
         * Ключ (путь) sentinel-объекта в {@code sentinelBucket}.
         *
         * <p>Объект должен быть предварительно создан. Health check обращается к нему
         * через {@code statObject()} — только метаданные, без скачивания содержимого,
         * что делает операцию минимально затратной.
         *
         * <p>Рекомендуется использовать имя, начинающееся с точки по Unix-конвенции,
         * чтобы объект не обрабатывался бизнес-логикой приложения.
         *
         * @see <a href="https://helpcenter.veeam.com/docs/agentforwindows/userguide/health_check_object_storage.html">
         * Health Check for Object Storage — Veeam Documentation</a>
         */
        @NotBlank(message = "Sentinel object key must not be blank")
        private String sentinelObjectKey = ".health-sentinel";
    }
}
