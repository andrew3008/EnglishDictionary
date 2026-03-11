package space.br1440.platform.healthchecks.postgres;

import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.lang.Nullable;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.HealthStatuses;
import space.br1440.platform.healthchecks.common.ProbeAware;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Индикатор здоровья для проверки доступности PostgreSQL и состояния пула соединений.
 *
 * <h3>Интеграция с пробами Kubernetes</h3>
 * <ul>
 *   <li>{@link HealthProbe#READINESS READINESS} (по умолчанию) —
 *       POD исключается из балансировки, <strong>перезапуск не выполняется</strong></li>
 *   <li>{@link HealthProbe#LIVENESS LIVENESS} — POD <strong>перезапускается</strong> kubelet.
 *       <em>Использовать только по явному решению владельца сервиса.</em></li>
 * </ul>
 *
 * <p><strong>ВНИМАНИЕ:</strong> Привязка внешней зависимости (БД, брокер сообщений)
 * к {@code LIVENESS} противоречит общепринятой практике Kubernetes — держать liveness-пробы
 * свободными от внешних зависимостей. Один сбой БД приведёт к <em>одновременному</em>
 * перезапуску всех подов и может вызвать каскадный отказ.
 *
 * @see <a href="https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/actuate/jdbc/DataSourceHealthIndicator.html">
 *      Spring Boot DataSourceHealthIndicator (Javadoc)</a>
 * @see <a href="https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/">
 *      Kubernetes — Liveness, Readiness, and Startup Probes</a>
 * @see <a href="https://srcco.de/posts/kubernetes-liveness-probes-are-dangerous.html">
 *      Henning Jacobs — Liveness Probes are Dangerous</a>
 * @see <a href="https://github.com/brettwooldridge/HikariCP/wiki/MBean-(JMX)-Monitoring-and-Management">
 *      HikariCP — MBean (JMX) Monitoring and Management</a>
 * @see <a href="https://spring.io/blog/2020/03/25/liveness-and-readiness-probes-with-spring-boot">
 *      Spring Blog — Liveness and Readiness Probes with Spring Boot</a>
 */
@RequiredArgsConstructor
public class PostgresHealthIndicator implements HealthIndicator, ProbeAware {

    @SuppressWarnings("SqlNoDataSourceInspection")
    private static final String VALIDATION_QUERY = "SELECT 1";

    private final DataSource dataSource;
    private final PostgresHealthProperties properties;
    private final PoolHealthEvaluator poolHealthEvaluator;

    @Override
    public Set<HealthProbe> getProbes() {
        return properties.getProbes();
    }

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        try {
            checkConnectivity(details);
            PoolHealthEvaluator.PoolHealthResult poolResult = checkPool(details);
            details.put("durationMs", System.currentTimeMillis() - start);
            return buildHealth(poolResult, details);
        } catch (Exception e) {
            details.put("durationMs", System.currentTimeMillis() - start);
            details.put("error", e.getMessage());
            details.put("errorType", e.getClass().getSimpleName());
            return Health.down(e).withDetails(details).build();
        }
    }

    private Health buildHealth(@Nullable PoolHealthEvaluator.PoolHealthResult poolResult,
                               Map<String, Object> details) {
        if (poolResult != null && poolResult.isDegraded()) {
            return Health.status(HealthStatuses.DEGRADED)
                    .withDetails(details)
                    .build();
        }

        return Health.up().withDetails(details).build();
    }

    private void checkConnectivity(Map<String, Object> details) throws SQLException {
        int timeoutSeconds = (int) properties.getHealthCheckTimeout().toSeconds();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(VALIDATION_QUERY)) {
            ps.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Validation query returned no rows");
                }
            }
        }

        details.put("database", "PostgreSQL");
        details.put("validationQuery", VALIDATION_QUERY);
        details.put("queryTimeoutSeconds", timeoutSeconds);
    }

    @Nullable
    private PoolHealthEvaluator.PoolHealthResult checkPool(Map<String, Object> details) {
        if (!properties.getConnectionPool().isMonitoringEnabled()) {
            return null;
        }

        HikariDataSource hikari = resolveHikariDataSource(dataSource);
        if (hikari == null) {
            return null;
        }

        try {
            PoolHealthEvaluator.PoolHealthResult result = poolHealthEvaluator.check(hikari);
            details.put("pool", result.metrics());
            return result;
        } catch (Exception e) {
            details.put("poolError", e.getMessage());
            return null;
        }
    }

    private HikariDataSource resolveHikariDataSource(DataSource dataSource) {
        if (AopUtils.isAopProxy(dataSource)) {
            try {
                Object target = ((Advised) dataSource).getTargetSource().getTarget();
                if (target instanceof HikariDataSource hikari) {
                    return hikari;
                }
            } catch (Exception ignored) {}
        }

        return (dataSource instanceof HikariDataSource hikari) ? hikari : null;
    }
}
