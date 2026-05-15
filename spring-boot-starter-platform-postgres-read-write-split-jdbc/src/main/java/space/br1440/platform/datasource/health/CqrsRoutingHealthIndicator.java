package space.br1440.platform.datasource.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Actuator {@link HealthIndicator} для CQRS-маршрутизации.
 *
 * <p><b>Ключ Actuator</b>: бин регистрируется под именем {@code routingHealthIndicator},
 * что даёт ключ {@code /actuator/health/routing}. Это публичное API — k8s liveness/readiness
 * probes полагаются на этот путь.
 *
 * <p><b>Что проверяется.</b> Для каждого из двух пулов (write / read) параллельно выполняется
 * {@code SELECT pg_is_in_recovery()}. Один SQL-запрос даёт сразу два сигнала:
 * <ul>
 *   <li>UP-статус (сам факт успешного выполнения)</li>
 *   <li>Физическую роль ноды, к которой в данный момент подключён пул:
 *       {@code false} → {@code primary}, {@code true} → {@code standby}</li>
 * </ul>
 *
 * <p>Это важно для {@link space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties.ReadReplica.Strategy#LOAD_BALANCED}:
 * read-пул может подключиться и к Primary, и к Standby — health отражает факт, а не конфигурацию.
 *
 * <p>Структура деталей:
 * <pre>
 * {
 *   "write": { "status": "UP",   "pgRole": "primary" },
 *   "read":  { "status": "UP",   "pgRole": "standby" }
 * }
 * </pre>
 *
 * <p>Статусы агрегата:
 * <ul>
 *   <li>{@code UP} — оба пула доступны</li>
 *   <li>{@code DEGRADED} — доступен только один пул</li>
 *   <li>{@code DOWN} — оба пула недоступны</li>
 * </ul>
 *
 * <p>Использует {@code Executors.newVirtualThreadPerTaskExecutor()}, чтобы не блокировать
 * {@code ForkJoinPool.commonPool()} блокирующими JDBC-операциями.
 */
@Slf4j
public class CqrsRoutingHealthIndicator implements HealthIndicator {

    private static final String INTENT_WRITE = "write";
    private static final String INTENT_READ = "read";

    private static final String PG_ROLE_PRIMARY = "primary";
    private static final String PG_ROLE_STANDBY = "standby";
    private static final String PG_ROLE_UNKNOWN = "unknown";

    private static final String DETAIL_STATUS = "status";
    private static final String DETAIL_PG_ROLE = "pgRole";

    private static final String QUERY_PG_ROLE = "SELECT pg_is_in_recovery()";

    private static final Status DEGRADED = new Status("DEGRADED");
    private static final long CHECK_TIMEOUT_SECONDS = 5;

    private static final Executor HEALTH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final DataSource writeDataSource;
    private final DataSource readDataSource;

    public CqrsRoutingHealthIndicator(DataSource writeDataSource, DataSource readDataSource) {
        this.writeDataSource = writeDataSource;
        this.readDataSource = readDataSource;
    }

    @Override
    public Health health() {
        CompletableFuture<CheckResult> writeFuture = checkAsync(writeDataSource, INTENT_WRITE);
        CompletableFuture<CheckResult> readFuture = checkAsync(readDataSource, INTENT_READ);

        CompletableFuture.allOf(writeFuture, readFuture).join();
        CheckResult writeResult = writeFuture.join();
        CheckResult readResult = readFuture.join();

        return buildHealth(writeResult, readResult);
    }

    private CompletableFuture<CheckResult> checkAsync(DataSource ds, String intent) {
        return CompletableFuture
                .supplyAsync(() -> check(ds, intent), HEALTH_EXECUTOR)
                .orTimeout(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.warn("Health check timed out for {} intent: {}", intent, ex.getMessage());
                    return CheckResult.failed();
                });
    }

    private Health buildHealth(CheckResult write, CheckResult read) {
        Map<String, Object> writeDetails = toDetails(write);
        Map<String, Object> readDetails = toDetails(read);

        Health.Builder builder;
        if (write.up && read.up) {
            builder = Health.up();
        } else if (write.up || read.up) {
            builder = Health.status(DEGRADED);
        } else {
            builder = Health.down();
        }

        return builder
                .withDetail(INTENT_WRITE, writeDetails)
                .withDetail(INTENT_READ, readDetails)
                .build();
    }

    private static Map<String, Object> toDetails(CheckResult result) {
        // LinkedHashMap чтобы сохранить порядок ключей: сначала status, потом pgRole —
        // читается легче и более предсказуемо для тестов, сравнивающих JSON.
        Map<String, Object> map = new LinkedHashMap<>(2);
        map.put(DETAIL_STATUS, result.up ? Status.UP.getCode() : Status.DOWN.getCode());
        map.put(DETAIL_PG_ROLE, result.pgRole);
        return map;
    }

    @SuppressWarnings("SqlNoDataSourceInspection")
    private CheckResult check(DataSource ds, String intent) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(QUERY_PG_ROLE)) {

            if (!rs.next()) {
                // Теоретически невозможно для pg_is_in_recovery(), но защищаемся для робастности.
                log.warn("Empty result for '{}' on {} intent", QUERY_PG_ROLE, intent);
                return new CheckResult(true, PG_ROLE_UNKNOWN);
            }

            boolean isInRecovery = rs.getBoolean(1);
            return new CheckResult(true, isInRecovery ? PG_ROLE_STANDBY : PG_ROLE_PRIMARY);
        } catch (Exception ex) {
            log.warn("Health check failed for {} intent: {}", intent, ex.getMessage());
            return CheckResult.failed();
        }
    }

    /**
     * Результат health-проверки одного пула: факт доступности + физическая роль ноды.
     */
    private record CheckResult(boolean up, String pgRole) {
        static CheckResult failed() {
            return new CheckResult(false, PG_ROLE_UNKNOWN);
        }
    }
}
