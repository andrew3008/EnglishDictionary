package space.br1440.platform.healthchecks.postgres;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.validation.PositiveDuration;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

/**
 * <b>Синхронная модель выполнения</b> — в отличие от object storage (MinIO),
 * PostgreSQL health check выполняется синхронно в потоке Kubernetes probe.
 * Это обосновано следующим:
 * <ol>
 *   <li>Проверка ({@code SELECT 1}) выполняется через существующее соединение
 *       из пула (HikariCP), без установки нового TCP-соединения — типичное
 *       время ответа 1–5ms.</li>
 *   <li>Таймаут {@code healthCheckTimeout} (3s) детерминированно ограничивает
 *       время ожидания и укладывается в {@code timeoutSeconds} Kubernetes readiness probe.</li>
 *   <li>Spring Boot {@code DataSourceHealthIndicator} является синхронным
 *       по дизайну; async необходим только для зависимостей с непредсказуемым временем ответа (HTTP, object storage).
 *       См. <a href="https://github.com/spring-projects/spring-boot/issues/5910"> Spring Boot #5910</a>.</li>
 * </ol>
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = PostgresHealthProperties.PREFIX)
public class PostgresHealthProperties {

    public static final String PREFIX = "platform.health.postgres";

    private boolean enabled = true;

    @NotNull(message = "Probes cannot be null or empty")
    @Size(min = 1, message = "At least one probe must be specified")
    private Set<HealthProbe> probes = EnumSet.of(HealthProbe.READINESS);

    /**
     * Максимальное время ожидания ответа от PostgreSQL при выполнении health check
     * (validation query: SELECT 1).
     * Если за указанное время запрос не завершился — health check возвращает DOWN.
     * <p>
     * Ограничение: значение должно быть меньше {@code timeoutSeconds} Kubernetes readiness probe
     * (Kubernetes default: 1s, см. <a href="https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/">...</a>).
     * Рекомендуется устанавливать не более 80% от {@code timeoutSeconds} probe,
     * чтобы Spring Boot успел вернуть ответ до истечения Kubernetes timeout.
     * <p>
     * Дефолт 3s — разумный компромисс для случая, когда probe timeoutSeconds=5, что является типичной production-настройкой для Postgres.
     * see: <a href="https://www.apptio.com/blog/kubernetes-readiness-probe/">...</a>
     */
    @NotNull(message = "Health check timeout cannot be null")
    @PositiveDuration(message = "Health check timeout must be positive")
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration healthCheckTimeout = Duration.ofSeconds(3);

    @Valid
    @NestedConfigurationProperty
    private final ConnectionPool connectionPool = new ConnectionPool();

    @Getter @Setter @Accessors(chain = true)
    public static class ConnectionPool {

        private boolean monitoringEnabled = true;

        @Min(value = 0, message = "Min available connections percent must be >= 0")
        @Max(value = 100, message = "Min available connections percent must be <= 100")
        private int minAvailablePercent = 10;

        @PositiveOrZero(message = "Max waiting threads must be >= 0")
        private int maxWaitingThreads = 5;
    }
}
